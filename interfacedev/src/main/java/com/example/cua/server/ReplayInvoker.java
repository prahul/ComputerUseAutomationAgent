package com.example.cua.server;

import com.example.cua.artifact.Artifact;
import com.example.cua.artifact.ArtifactStore;
import com.example.cua.core.Ids;
import com.example.cua.evidence.EvidenceRecorder;
import com.example.cua.escalation.Escalation;
import com.example.cua.policy.PolicyEngine;
import com.example.cua.policy.Redactor;
import com.example.cua.replay.ReplayEngine;
import com.example.cua.replay.ReplayResult;
import com.example.cua.surface.web.WebSurface;
import com.example.cua.tenant.TenantProfile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs a deterministic replay for an agent-invoked capability. Used by the {@code /capabilities/{name}/invoke}
 * HTTP endpoint and the {@code cua invoke} CLI. Only {@code APPROVED} artifacts may be invoked this
 * way (unattended); {@code cua replay --allow-draft} is the path for testing an unreviewed one.
 */
public final class ReplayInvoker implements CapabilityApi.Invoker {

    private final ArtifactStore store;
    private final Path configDir;
    private final Path evidenceRoot;
    private final int targetPort;
    private final boolean headed;

    public ReplayInvoker(ArtifactStore store, Path configDir, Path evidenceRoot, int targetPort, boolean headed) {
        this.store = store;
        this.configDir = configDir;
        this.evidenceRoot = evidenceRoot;
        this.targetPort = targetPort;
        this.headed = headed;
    }

    @Override
    public Object invoke(String name, Map<String, String> args, String tenantId) {
        Artifact artifact = store.require(name);
        if (artifact.meta().approval() != Artifact.ApprovalState.APPROVED) {
            return Map.of("error", "capability '" + name + "' is " + artifact.meta().approval()
                    + "; only APPROVED capabilities may be invoked. Run `cua approve --capability " + name + "` after review.");
        }

        Map<String, String> secrets = loadSecrets();
        List<String> sensitive = artifact.redaction() == null || artifact.redaction().sensitiveParams() == null
                ? List.of() : artifact.redaction().sensitiveParams();
        Redactor redactor = new Redactor(Artifact.Redaction.defaults().patterns());
        secrets.forEach(redactor::withValue);
        sensitive.forEach(n -> { if (args.containsKey(n)) redactor.withValue("param:" + n, args.get(n)); });

        String runId = Ids.runId("invoke");
        TenantProfile profile = loadTenant(tenantId);
        String tenantBaseUrl = "altcu".equals(tenantId)
                ? "http://localhost:" + targetPort + "/altcu"
                : "http://localhost:" + targetPort;

        WebSurface surface = new WebSurface(WebSurface.Config.defaults().headed(headed));
        try (EvidenceRecorder evidence = new EvidenceRecorder(evidenceRoot, runId, redactor)) {
            Escalation.SessionController controller = new Escalation.SessionController();
            Escalation.EscalationBroker broker = new Escalation.EscalationBroker(controller);
            broker.autoRespondAfter(10_000, Escalation.Resolution.ABORT, "unattended invoke: no operator");

            PolicyEngine policy = new PolicyEngine(loadAllowlist());
            ReplayEngine engine = new ReplayEngine(surface, policy, evidence, broker);
            Artifact.BindingContext binding = new Artifact.BindingContext(args, secrets, tenantBaseUrl);
            ReplayResult result = engine.replay(artifact, binding, profile,
                    new ReplayEngine.Options(false, List.of(), false));

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("capability", name);
            out.put("version", artifact.meta().version());
            out.put("status", result.status().toString());
            out.put("outcomeCode", result.outcomeCode());
            out.put("outcomeMessage", result.outcomeMessage());
            out.put("outputs", result.outputs());
            out.put("failure", result.failure());
            out.put("evidence", evidence.dir().toString());
            return out;
        } finally {
            surface.close();
        }
    }

    private PolicyEngine.Allowlist loadAllowlist() {
        Path f = configDir.resolve("allowlist.json");
        if (Files.exists(f)) return PolicyEngine.Allowlist.load(f);
        return new PolicyEngine.Allowlist("default",
                List.of("http://localhost:" + targetPort),
                List.of("^http://localhost:" + targetPort + "(/.*)?$"),
                List.of("NAVIGATE", "CLICK", "TYPE", "SELECT_OPTION", "PRESS_KEY", "SCROLL_TO", "EXTRACT", "WAIT"),
                "REQUIRE_APPROVAL", 25, 300_000, false);
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> loadSecrets() {
        Path f = configDir.resolve("secrets.json");
        if (Files.exists(f)) return com.example.cua.core.Json.readFile(f, Map.class);
        return new LinkedHashMap<>(Map.of("credentials.username", "demo.operator", "credentials.password", "demo-pass"));
    }

    private TenantProfile loadTenant(String tenantId) {
        Path f = configDir.resolve("tenants").resolve(tenantId + ".json");
        return Files.exists(f) ? TenantProfile.load(f) : null;
    }
}
