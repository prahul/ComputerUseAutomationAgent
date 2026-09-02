package com.example.cua.cli;

import com.example.cua.artifact.Artifact;
import com.example.cua.artifact.ArtifactStore;
import com.example.cua.core.Ids;
import com.example.cua.core.Json;
import com.example.cua.discovery.AnthropicLlmClient;
import com.example.cua.discovery.DiscoveryAgent;
import com.example.cua.discovery.GoalSpec;
import com.example.cua.evidence.EvidenceRecorder;
import com.example.cua.escalation.Escalation;
import com.example.cua.policy.PolicyEngine;
import com.example.cua.policy.Redactor;
import com.example.cua.replay.ReplayEngine;
import com.example.cua.replay.ReplayResult;
import com.example.cua.server.AppServer;
import com.example.cua.server.CapabilityApi;
import com.example.cua.server.LiveSession;
import com.example.cua.surface.web.WebSurface;
import com.example.cua.tenant.TenantProfile;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "cua", mixinStandardHelpOptions = true, version = "cua-lab 0.1.0",
        description = "Computer-Use Automation: discover a UI flow with an LLM, save it as a typed capability, replay it deterministically.",
        subcommands = {Cli.Serve.class, Cli.Discover.class, Cli.Replay.class, Cli.Approve.class, Cli.Catalog.class, Cli.Invoke.class})
public final class Cli implements Runnable {

    static final Path ARTIFACTS = Path.of("artifacts");
    static final Path EVIDENCE = Path.of("evidence");
    static final Path CONFIG = Path.of("config");

    public static void main(String[] args) {
        System.setProperty("org.slf4j.simpleLogger.showDateTime", "true");
        System.setProperty("org.slf4j.simpleLogger.log.com.microsoft.playwright", "warn");
        int code = new CommandLine(new Cli()).execute(args);
        System.exit(code);
    }

    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }

    // --- shared wiring -----------------------------------------------------------------------

    static PolicyEngine.Allowlist allowlist() {
        Path f = CONFIG.resolve("allowlist.json");
        if (Files.exists(f)) return PolicyEngine.Allowlist.load(f);
        return new PolicyEngine.Allowlist("default",
                List.of("http://localhost:8080"),
                List.of("^http://localhost:8080(/.*)?$"),
                List.of("NAVIGATE", "CLICK", "TYPE", "SELECT_OPTION", "PRESS_KEY", "SCROLL_TO", "EXTRACT", "WAIT"),
                "REQUIRE_APPROVAL", 25, 300_000, false);
    }

    @SuppressWarnings("unchecked")
    static Map<String, String> secrets() {
        Path f = CONFIG.resolve("secrets.json");
        if (Files.exists(f)) return Json.readFile(f, Map.class);
        return new LinkedHashMap<>(Map.of(
                "credentials.username", "demo.operator",
                "credentials.password", "demo-pass"));
    }

    static Map<String, String> parseKv(List<String> pairs) {
        Map<String, String> m = new LinkedHashMap<>();
        if (pairs != null) for (String p : pairs) {
            int i = p.indexOf('=');
            if (i < 0) throw new IllegalArgumentException("expected key=value, got: " + p);
            m.put(p.substring(0, i).trim(), p.substring(i + 1).trim());
        }
        return m;
    }

    static Redactor redactor(Map<String, String> secrets, Map<String, String> sensitiveParams) {
        Redactor r = new Redactor(Artifact.Redaction.defaults().patterns());
        secrets.forEach(r::withValue);
        sensitiveParams.forEach((k, v) -> r.withValue("param:" + k, v));
        return r;
    }

    static long operatorMillis(String mode) {
        return switch (mode == null ? "auto" : mode) {
            case "manual" -> 0;
            case "auto-abort" -> 12_000;
            default -> 20_000;
        };
    }

    static Escalation.Resolution operatorResolution(String mode) {
        return "auto-abort".equals(mode) ? Escalation.Resolution.ABORT : Escalation.Resolution.RESUME;
    }

    // --- serve ------------------------------------------------------------------------------

    @Command(name = "serve", description = "Start the target app + operator console + capability API and block.")
    static final class Serve implements Callable<Integer> {
        @Option(names = "--port", defaultValue = "8080") int port;

        @Override
        public Integer call() throws Exception {
            ArtifactStore store = new ArtifactStore(ARTIFACTS);
            var invoker = new com.example.cua.server.ReplayInvoker(store, CONFIG, EVIDENCE, port, false);
            AppServer server = new AppServer(port, store, invoker);
            server.start();
            System.out.println("Target app:       " + server.baseUrl());
            System.out.println("Alt tenant:       " + server.baseUrl() + "/altcu");
            System.out.println("Operator console: " + server.baseUrl() + "/operator");
            System.out.println("Capabilities API: " + server.baseUrl() + "/capabilities");
            System.out.println("Ctrl-C to stop.");
            Thread.currentThread().join();
            return 0;
        }
    }

    // --- discover ---------------------------------------------------------------------------

    @Command(name = "discover", description = "Run the LLM-driven discovery loop against a goal and emit a capability artifact.")
    static final class Discover implements Callable<Integer> {
        @Option(names = "--goal", required = true, description = "natural-language goal; may contain {paramName} placeholders") String goal;
        @Option(names = "--name", required = true, description = "machine name for the capability, e.g. lookup_member_savings_balance") String name;
        @Option(names = "--entry", description = "entry URL; defaults to the in-process target app") String entry;
        @Option(names = "--param", description = "param binding key=value (repeatable)") List<String> params;
        @Option(names = "--sensitive", description = "param name to treat as sensitive (repeatable)") List<String> sensitive;
        @Option(names = "--vendor-product", defaultValue = "acme-servicing") String vendorProduct;
        @Option(names = "--model", defaultValue = "claude-sonnet-5") String model;
        @Option(names = "--api-key", description = "Anthropic API key; falls back to ANTHROPIC_API_KEY") String apiKey;
        @Option(names = "--max-steps", defaultValue = "22") int maxSteps;
        @Option(names = "--headed", description = "show the browser window") boolean headed;
        @Option(names = "--port", defaultValue = "8080") int port;
        @Option(names = "--operator", defaultValue = "auto", description = "manual | auto | auto-abort") String operator;

        @Override
        public Integer call() throws Exception {
            Map<String, String> paramValues = parseKv(params);
            List<String> sensitiveNames = sensitive == null ? List.of() : sensitive;
            List<Artifact.ParamSpec> specs = new ArrayList<>();
            paramValues.forEach((k, v) -> specs.add(new Artifact.ParamSpec(
                    k, Artifact.ValueType.STRING, true, sensitiveNames.contains(k),
                    "Supplied by the calling agent.", v, null, null)));

            Map<String, String> secrets = secrets();
            Map<String, String> sensitiveParamValues = new LinkedHashMap<>();
            sensitiveNames.forEach(n -> { if (paramValues.containsKey(n)) sensitiveParamValues.put(n, paramValues.get(n)); });
            Redactor redactor = redactor(secrets, sensitiveParamValues);

            ArtifactStore store = new ArtifactStore(ARTIFACTS);
            String runId = Ids.runId("discovery");

            Escalation.SessionController controller = new Escalation.SessionController();
            Escalation.EscalationBroker broker = new Escalation.EscalationBroker(controller);
            broker.autoRespondAfter(operatorMillis(operator), operatorResolution(operator),
                    "auto-operator fallback (no human claimed the intervention)");

            AppServer server = new AppServer(port, store, null);
            server.start();
            String entryUrl = entry != null ? entry : server.baseUrl() + "/";

            broker.onOpen(req -> System.out.println("\n>>> ESCALATION: " + req.trigger() + " — " + req.reason()
                    + "\n>>> Operator console: " + server.baseUrl() + "/operator\n"));

            WebSurface surface = new WebSurface(WebSurface.Config.defaults().headed(headed || "manual".equals(operator)));
            try (EvidenceRecorder evidence = new EvidenceRecorder(EVIDENCE, runId, redactor)) {
                server.bindLiveSession(new LiveSession(runId, surface, evidence, broker));
                PolicyEngine policy = new PolicyEngine(allowlist());

                GoalSpec spec = new GoalSpec(goal, entryUrl, name, vendorProduct, specs, paramValues, secrets);
                DiscoveryAgent agent = new DiscoveryAgent(
                        new AnthropicLlmClient(apiKey, model), surface, policy, evidence, broker, maxSteps);

                DiscoveryAgent.Result result = agent.run(spec, runId);
                if (result.success()) {
                    Path saved = store.save(result.artifact());
                    System.out.println("\nDISCOVERY OK  (" + result.traces().size() + " steps)");
                    System.out.println("  capability: " + result.artifact().meta().name() + " v" + result.artifact().meta().version());
                    System.out.println("  artifact:   " + saved);
                    System.out.println("  evidence:   " + evidence.dir());
                    return 0;
                }
                System.out.println("\nDISCOVERY DID NOT COMPLETE: " + result.summary());
                System.out.println("  evidence: " + evidence.dir());
                return 2;
            } finally {
                surface.close();
                server.clearLiveSession();
                server.close();
            }
        }
    }

    // --- replay -----------------------------------------------------------------------------

    @Command(name = "replay", description = "Deterministically replay a saved capability artifact (no LLM).")
    static final class Replay implements Callable<Integer> {
        @Option(names = "--capability", description = "capability name from the local store") String capability;
        @Option(names = "--artifact", description = "path to an artifact JSON file") Path artifactPath;
        @Option(names = "--param", description = "param binding key=value (repeatable)") List<String> params;
        @Option(names = "--tenant", defaultValue = "base", description = "base | altcu") String tenant;
        @Option(names = "--inject", defaultValue = "none", description = "none | slow | interstitial | session_timeout | error") String inject;
        @Option(names = "--headed") boolean headed;
        @Option(names = "--port", defaultValue = "8080") int port;
        @Option(names = "--operator", defaultValue = "auto", description = "manual | auto | auto-abort") String operator;
        @Option(names = "--allow-draft", description = "permit unattended replay of a DRAFT artifact") boolean allowDraft;

        @Override
        public Integer call() throws Exception {
            ArtifactStore store = new ArtifactStore(ARTIFACTS);
            Artifact artifact = artifactPath != null ? store.load(artifactPath) : store.require(capability);

            Map<String, String> paramValues = parseKv(params);
            Map<String, String> secrets = secrets();
            List<String> sensitiveNames = artifact.redaction() == null ? List.of()
                    : artifact.redaction().sensitiveParams() == null ? List.of() : artifact.redaction().sensitiveParams();
            Map<String, String> sensitiveParamValues = new LinkedHashMap<>();
            sensitiveNames.forEach(n -> { if (paramValues.containsKey(n)) sensitiveParamValues.put(n, paramValues.get(n)); });
            Redactor redactor = redactor(secrets, sensitiveParamValues);

            String runId = Ids.runId("replay");
            Escalation.SessionController controller = new Escalation.SessionController();
            Escalation.EscalationBroker broker = new Escalation.EscalationBroker(controller);
            broker.autoRespondAfter(operatorMillis(operator), operatorResolution(operator),
                    "auto-operator fallback during replay");

            AppServer server = new AppServer(port, store, null);
            server.start();
            server.setInject(tenant, inject);
            broker.onOpen(req -> System.out.println("\n>>> ESCALATION: " + req.trigger() + " — " + req.reason()
                    + "\n>>> Operator console: " + server.baseUrl() + "/operator\n"));

            TenantProfile profile = loadTenant(tenant);
            String tenantBaseUrl = server.tenantBaseUrl(tenant);

            WebSurface surface = new WebSurface(WebSurface.Config.defaults().headed(headed || "manual".equals(operator)));
            try (EvidenceRecorder evidence = new EvidenceRecorder(EVIDENCE, runId, redactor)) {
                server.bindLiveSession(new LiveSession(runId, surface, evidence, broker));
                PolicyEngine policy = new PolicyEngine(allowlist());
                ReplayEngine engine = new ReplayEngine(surface, policy, evidence, broker);

                Artifact.BindingContext binding = new Artifact.BindingContext(paramValues, secrets, tenantBaseUrl);
                ReplayEngine.Options opts = new ReplayEngine.Options(
                        !"auto-abort".equals(operator), List.of(), allowDraft);
                ReplayResult result = engine.replay(artifact, binding, profile, opts);

                System.out.println();
                System.out.println(Json.write(result));
                System.out.println();
                System.out.println("RESULT: " + result.status()
                        + (result.outcomeCode() != null ? " (" + result.outcomeCode() + ")" : ""));
                System.out.println("  evidence: " + evidence.dir());
                return switch (result.status()) {
                    case SUCCESS, BUSINESS_OUTCOME -> 0;
                    case FAILED -> 3;
                };
            } finally {
                surface.close();
                server.clearLiveSession();
                server.close();
            }
        }
    }

    static TenantProfile loadTenant(String tenant) {
        Path f = CONFIG.resolve("tenants").resolve(tenant + ".json");
        return Files.exists(f) ? TenantProfile.load(f) : null;
    }

    // --- approve ----------------------------------------------------------------------------

    @Command(name = "approve", description = "Promote a reviewed capability from DRAFT to APPROVED so agents may invoke it unattended.")
    static final class Approve implements Callable<Integer> {
        @Option(names = "--capability", required = true) String capability;

        @Override
        public Integer call() {
            ArtifactStore store = new ArtifactStore(ARTIFACTS);
            Artifact a = store.require(capability);
            Artifact.Meta m = a.meta();
            Artifact approved = new Artifact(a.schemaVersion(),
                    new Artifact.Meta(m.id(), m.name(), m.version(), m.title(), m.description(), m.sourceRunId(),
                            m.createdAt(), m.createdBy(), Artifact.ApprovalState.APPROVED, m.replayConfidence()),
                    a.surface(), a.target(), a.parameters(), a.outputs(), a.steps(), a.success(),
                    a.knownOutcomes(), a.recoveries(), a.redaction());
            store.save(approved);
            System.out.println("approved " + capability + " v" + m.version());
            return 0;
        }
    }

    // --- catalog ----------------------------------------------------------------------------

    @Command(name = "catalog", description = "List saved capabilities as agent-invocable tool specs.")
    static final class Catalog implements Callable<Integer> {
        @Override
        public Integer call() {
            ArtifactStore store = new ArtifactStore(ARTIFACTS);
            List<Map<String, Object>> specs = store.list().stream().map(CapabilityApi::toolSpec).toList();
            System.out.println(Json.write(Map.of("capabilities", specs)));
            return 0;
        }
    }

    // --- invoke -----------------------------------------------------------------------------

    @Command(name = "invoke", description = "Invoke an APPROVED capability by name with typed args (the agent-facing path; runs a deterministic replay).")
    static final class Invoke implements Callable<Integer> {
        @Option(names = "--capability", required = true) String capability;
        @Option(names = "--param", description = "key=value (repeatable)") List<String> params;
        @Option(names = "--tenant", defaultValue = "base") String tenant;
        @Option(names = "--port", defaultValue = "8080") int port;

        @Override
        public Integer call() throws Exception {
            ArtifactStore store = new ArtifactStore(ARTIFACTS);
            AppServer server = new AppServer(port, store, null);
            server.start();
            try {
                var invoker = new com.example.cua.server.ReplayInvoker(store, CONFIG, EVIDENCE, port, false);
                Object result = invoker.invoke(capability, parseKv(params), tenant);
                System.out.println(Json.write(result));
                return result instanceof Map<?, ?> m && "FAILED".equals(String.valueOf(m.get("status"))) ? 3 : 0;
            } finally {
                server.close();
            }
        }
    }
}
