package com.example.cua.replay;

import com.example.cua.artifact.Artifact;
import com.example.cua.artifact.Artifact.*;
import com.example.cua.core.Ids;
import com.example.cua.evidence.EvidenceRecorder;
import com.example.cua.escalation.Escalation;
import com.example.cua.policy.PolicyEngine;
import com.example.cua.surface.Surface;
import com.example.cua.tenant.TenantProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The production execution path: replay a saved {@link Artifact} against a live surface with NO LLM
 * in the decision loop. Determinism comes from the ordered locator chain, explicit waits, and a
 * checkpoint assertion after every step. Robustness comes from evaluating the artifact's
 * {@code knownOutcomes} (business results) and {@code recoveries} (transient/interstitial states)
 * before treating anything as a hard failure.
 */
public final class ReplayEngine {
    private static final Logger log = LoggerFactory.getLogger(ReplayEngine.class);

    public record Options(
            boolean escalateOnHardFailure,
            List<String> preApprovedStepIds,
            boolean allowUnattendedDraft
    ) {
        public static Options defaults() { return new Options(true, List.of(), false); }
    }

    private final Surface surface;
    private final PolicyEngine policy;
    private final EvidenceRecorder evidence;
    private final Escalation.EscalationBroker escalation;
    private final DetectorEngine detectors;

    public ReplayEngine(Surface surface, PolicyEngine policy, EvidenceRecorder evidence,
                        Escalation.EscalationBroker escalation) {
        this.surface = surface;
        this.policy = policy;
        this.evidence = evidence;
        this.escalation = escalation;
        this.detectors = new DetectorEngine(surface);
    }

    public ReplayResult replay(Artifact artifact, BindingContext binding, TenantProfile tenant, Options options) {
        String runId = evidence.runId();
        Instant start = Instant.now();
        List<ReplayResult.StepResult> stepResults = new ArrayList<>();
        List<ReplayResult.DriftSignal> drift = new ArrayList<>();

        evidence.event("replay.start", Map.of(
                "capability", artifact.meta().name(), "version", artifact.meta().version(),
                "tenant", tenant == null ? "base" : tenant.tenantId(),
                "params", redactParams(artifact, binding)));

        if (artifact.meta().approval() == ApprovalState.DRAFT && !options.allowUnattendedDraft()
                && !policy.allowlist().allowUnattendedDraft()) {
            return terminal(ReplayResult.Status.FAILED, null, null,
                    "Artifact is DRAFT and unattended replay of draft capabilities is disabled by policy.",
                    Map.of(), new ReplayResult.Failure(null, "precondition", "APPROVED artifact", "DRAFT artifact", null),
                    stepResults, drift, runId, artifact, tenant, start);
        }

        for (Step step : artifact.steps()) {
            long t0 = System.currentTimeMillis();
            List<String> recoveries = new ArrayList<>();

            // 1. business outcome already on screen? (e.g. a prior step surfaced "no such member")
            Optional<KnownOutcome> pre = firstMatchingOutcome(artifact);
            if (pre.isPresent()) {
                return finishWithOutcome(artifact, pre.get(), step.id(), stepResults, drift, runId, tenant, start);
            }

            // 2. recover from known transient / interstitial conditions before acting
            applyRecoveries(artifact, recoveries);

            LocatorSpec locator = effectiveLocator(step, tenant);
            Surface.Action action = toAction(step, locator, binding);

            // 3. policy gate
            boolean preApproved = options.preApprovedStepIds().contains(step.id());
            PolicyEngine.Decision decision = policy.check(action, step.risk(), step.policy(), preApproved);
            if (decision.effect() == PolicyEngine.Effect.BLOCK) {
                evidence.event("replay.policy_block", Map.of("step", step.id(), "reason", decision.reason()));
                return hardFailure(artifact, step, "policy", "action permitted by policy", decision.reason(),
                        stepResults, drift, runId, tenant, start, options, recoveries);
            }
            if (decision.effect() == PolicyEngine.Effect.REQUIRE_APPROVAL) {
                Escalation.HandoffResult h = raise(Escalation.Trigger.POLICY_APPROVAL, runId, artifact, step,
                        "Replay needs human approval: " + decision.reason(), null);
                if (h.resolution() == Escalation.Resolution.ABORT) {
                    return hardFailure(artifact, step, "policy", "operator approval", "operator declined",
                            stepResults, drift, runId, tenant, start, options, recoveries);
                }
                if (h.resolution() == Escalation.Resolution.RESUME_SKIP_STEP) {
                    stepResults.add(stepResult(step, "SKIPPED", null, false, recoveries, t0, "operator skipped after approval prompt"));
                    continue;
                }
            }

            // 4. resolve target (except for NAVIGATE / WAIT / PRESS_KEY)
            Surface.ProbeResult probe = null;
            if (needsTarget(step)) {
                probe = surface.probe(locator);
                if (!probe.found()) {
                    // maybe this is actually a business outcome (record not found -> detail element absent)
                    Optional<KnownOutcome> outcome = firstMatchingOutcome(artifact);
                    if (outcome.isPresent()) {
                        return finishWithOutcome(artifact, outcome.get(), step.id(), stepResults, drift, runId, tenant, start);
                    }
                    return hardFailure(artifact, step, "locate", "element matching " + describe(locator),
                            "no element resolved from the strategy chain", stepResults, drift, runId, tenant, start, options, recoveries);
                }
                recordDrift(step, locator, probe, drift);
            }

            // 5. act
            Surface.ActionResult ar = surface.act(action);
            if (!ar.ok()) {
                applyRecoveries(artifact, recoveries);
                ar = surface.act(action); // one retry after recovery sweep
            }
            if (!ar.ok()) {
                return hardFailure(artifact, step, "act", "action to succeed", ar.detail(),
                        stepResults, drift, runId, tenant, start, options, recoveries);
            }

            // 6. checkpoint
            if (step.checkpoint() != null) {
                boolean passed = detectors.waitFor(step.checkpoint().condition(), step.checkpoint().timeoutMs());
                if (!passed) {
                    Optional<KnownOutcome> outcome = firstMatchingOutcome(artifact);
                    if (outcome.isPresent()) {
                        return finishWithOutcome(artifact, outcome.get(), step.id(), stepResults, drift, runId, tenant, start);
                    }
                    return hardFailure(artifact, step, "checkpoint",
                            step.checkpoint().description(), "checkpoint condition not met within "
                                    + step.checkpoint().timeoutMs() + "ms", stepResults, drift, runId, tenant, start, options, recoveries);
                }
            }

            String usedStrategy = probe == null ? null : String.valueOf(probe.strategyUsed());
            boolean fallback = probe != null && locator.primary() != null
                    && probe.strategyUsed() != locator.primary().kind();
            stepResults.add(stepResult(step, recoveries.isEmpty() ? "OK" : "RECOVERED", usedStrategy, fallback, recoveries, t0,
                    ar.detail()));
            evidence.event("replay.step", Map.of("step", step.id(), "action", step.action().toString(),
                    "strategy", usedStrategy == null ? "" : usedStrategy, "recoveries", recoveries, "detail", ar.detail()));
        }

        // 7. final business-outcome check + success verification
        Optional<KnownOutcome> late = firstMatchingOutcome(artifact);
        if (late.isPresent() && late.get().outcomeClass() != OutcomeClass.BUSINESS) {
            return finishWithOutcome(artifact, late.get(), null, stepResults, drift, runId, tenant, start);
        }

        boolean success = detectors.waitFor(artifact.success(), 8_000);
        Map<String, Object> outputs = success ? collectOutputs(artifact, tenant) : Map.of();
        if (!success) {
            return hardFailure(artifact, null, "success", "success condition " + describeCond(artifact.success()),
                    "success condition not satisfied at end of flow", stepResults, drift, runId, tenant, start,
                    options, List.of());
        }

        evidence.screenshot("replay-success", surface.screenshot());
        ReplayResult result = new ReplayResult(ReplayResult.Status.SUCCESS, null, null, null, outputs, null,
                stepResults, drift, runId, artifact.meta().name(), artifact.meta().version(),
                tenant == null ? "base" : tenant.tenantId(), start, Instant.now());
        evidence.result(result);
        return result;
    }

    // --- outcome + failure helpers ------------------------------------------------------------

    private Optional<KnownOutcome> firstMatchingOutcome(Artifact a) {
        if (a.knownOutcomes() == null) return Optional.empty();
        Surface.Observation obs = surface.observe();
        return a.knownOutcomes().stream().filter(o -> detectors.matches(o.detect(), obs)).findFirst();
    }

    private ReplayResult finishWithOutcome(Artifact a, KnownOutcome o, String atStep,
                                           List<ReplayResult.StepResult> steps, List<ReplayResult.DriftSignal> drift,
                                           String runId, TenantProfile tenant, Instant start) {
        evidence.screenshot("business-outcome", surface.screenshot());
        evidence.event("replay.business_outcome", Map.of("code", o.code(), "class", o.outcomeClass().toString(),
                "atStep", atStep == null ? "" : atStep, "message", o.message()));
        ReplayResult r = new ReplayResult(ReplayResult.Status.BUSINESS_OUTCOME, o.code(), o.outcomeClass(), o.message(),
                Map.of(), null, steps, drift, runId, a.meta().name(), a.meta().version(),
                tenant == null ? "base" : tenant.tenantId(), start, Instant.now());
        evidence.result(r);
        return r;
    }

    private ReplayResult hardFailure(Artifact a, Step step, String phase, String expected, String observed,
                                     List<ReplayResult.StepResult> steps, List<ReplayResult.DriftSignal> drift,
                                     String runId, TenantProfile tenant, Instant start, Options options,
                                     List<String> recoveries) {
        byte[] png = surface.screenshot();
        evidence.failure(step == null ? null : step.id(), expected, observed, png, safeDom());
        if (step != null) {
            steps.add(stepResult(step, "FAILED", null, false, recoveries, System.currentTimeMillis(), phase + ": " + observed));
        }
        ReplayResult.Failure f = new ReplayResult.Failure(step == null ? null : step.id(), phase, expected, observed, "failure.png");

        if (options.escalateOnHardFailure() && escalation != null) {
            Escalation.HandoffResult h = raise(Escalation.Trigger.REPLAY_HARD_FAILURE, runId, a, step,
                    "Replay hard failure at " + phase + ": " + observed, null);
            evidence.event("replay.escalated", Map.of("resolution", h.resolution().toString(), "operator", h.operator()));
            if (h.resolution() == Escalation.Resolution.RESUME || h.resolution() == Escalation.Resolution.RESUME_SKIP_STEP) {
                evidence.event("replay.resumed_after_human", Map.of("humanActions", h.actions().size()));
            }
        }

        ReplayResult r = new ReplayResult(ReplayResult.Status.FAILED, null, null, null, Map.of(), f,
                steps, drift, runId, a.meta().name(), a.meta().version(),
                tenant == null ? "base" : tenant.tenantId(), start, Instant.now());
        evidence.result(r);
        return r;
    }

    private ReplayResult terminal(ReplayResult.Status s, String code, OutcomeClass oc, String msg,
                                  Map<String, Object> outputs, ReplayResult.Failure f,
                                  List<ReplayResult.StepResult> steps, List<ReplayResult.DriftSignal> drift,
                                  String runId, Artifact a, TenantProfile tenant, Instant start) {
        ReplayResult r = new ReplayResult(s, code, oc, msg, outputs, f, steps, drift, runId,
                a.meta().name(), a.meta().version(), tenant == null ? "base" : tenant.tenantId(), start, Instant.now());
        evidence.result(r);
        return r;
    }

    // --- recoveries -------------------------------------------------------------------------

    private void applyRecoveries(Artifact a, List<String> applied) {
        if (a.recoveries() == null) return;
        for (Recovery rec : a.recoveries()) {
            for (int attempt = 0; attempt < Math.max(1, rec.maxAttempts()); attempt++) {
                if (!detectors.matches(rec.detect())) break;
                evidence.event("replay.recovery", Map.of("code", rec.code(), "strategy", rec.strategy().toString(), "attempt", attempt + 1));
                applied.add(rec.code());
                switch (rec.strategy()) {
                    case WAIT_RETRY -> sleep(rec.backoffMs());
                    case RELOAD -> { surface.act(Surface.Action.navigate(surface.location())); sleep(rec.backoffMs()); }
                    case DISMISS -> {
                        if (rec.dismissAction() != null) {
                            surface.act(new Surface.Action(Surface.ActionType.CLICK, null,
                                    rec.dismissAction().target(), null, null, null, null));
                        }
                        sleep(rec.backoffMs());
                    }
                }
            }
        }
    }

    // --- mapping ---------------------------------------------------------------------------

    private LocatorSpec effectiveLocator(Step step, TenantProfile tenant) {
        return tenant == null ? step.target() : tenant.locatorFor(step);
    }

    private Surface.Action toAction(Step step, LocatorSpec locator, BindingContext binding) {
        String value = resolveValue(step.value(), binding);
        return switch (step.action()) {
            case NAVIGATE -> Surface.Action.navigate(resolveUrl(step.url(), binding));
            case CLICK -> new Surface.Action(Surface.ActionType.CLICK, null, locator, null, null, null, null);
            case TYPE -> new Surface.Action(Surface.ActionType.TYPE, null, locator, value, null, null, null);
            case SELECT_OPTION -> new Surface.Action(Surface.ActionType.SELECT_OPTION, null, locator, value, null, null, null);
            case PRESS_KEY -> Surface.Action.pressKey(step.key());
            case WAIT -> Surface.Action.waitFor(step.waitMs() == null ? 500 : step.waitMs());
            case SCROLL_TO -> new Surface.Action(Surface.ActionType.SCROLL_TO, null, locator, null, null, null, null);
            case EXTRACT -> new Surface.Action(Surface.ActionType.EXTRACT, null, locator, null, null, null, null);
        };
    }

    private boolean needsTarget(Step step) {
        return switch (step.action()) {
            case NAVIGATE, WAIT, PRESS_KEY -> false;
            default -> step.target() != null;
        };
    }

    private String resolveValue(ValueSpec v, BindingContext b) {
        if (v == null) return null;
        if (v.literal() != null) return v.literal();
        if (v.paramRef() != null) {
            String val = b.params().get(v.paramRef());
            if (val == null) throw new IllegalArgumentException("missing required parameter: " + v.paramRef());
            return val;
        }
        if (v.secretRef() != null) {
            String val = b.secrets().get(v.secretRef());
            if (val == null) throw new IllegalArgumentException("missing secret: " + v.secretRef());
            return val;
        }
        return null;
    }

    private String resolveUrl(String url, BindingContext b) {
        if (url == null) return null;
        return url.replace("${tenant.baseUrl}", b.tenantBaseUrl() == null ? "" : b.tenantBaseUrl());
    }

    private Map<String, Object> collectOutputs(Artifact a, TenantProfile tenant) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (a.outputs() == null) return out;
        for (OutputSpec o : a.outputs()) {
            Step src = a.steps().stream().filter(s -> s.id().equals(o.source().stepId())).findFirst().orElse(null);
            if (src == null) continue;
            LocatorSpec loc = tenant == null ? src.target() : tenant.locatorFor(src);
            String raw = surface.resolve(loc).map(Surface.UiElement::text).orElse(null);
            if (raw == null) { out.put(o.name(), null); continue; }
            out.put(o.name(), transform(raw, o));
        }
        return out;
    }

    private Object transform(String raw, OutputSpec o) {
        String s = raw.replaceAll("\\s+", " ").trim();
        if (o.source() != null && o.source().regex() != null) {
            var m = java.util.regex.Pattern.compile(o.source().regex()).matcher(s);
            if (m.find()) s = m.group(1);
        }
        String tf = o.source() == null ? null : o.source().transform();
        if (tf == null) return s;
        return switch (tf) {
            case "money" -> Double.parseDouble(s.replaceAll("[^0-9.]", ""));
            case "digits" -> Long.parseLong(s.replaceAll("\\D", ""));
            case "trim" -> s;
            default -> s;
        };
    }

    // --- misc -----------------------------------------------------------------------------

    private void recordDrift(Step step, LocatorSpec locator, Surface.ProbeResult probe, List<ReplayResult.DriftSignal> drift) {
        if (locator.primary() == null || probe.strategyUsed() == null) return;
        if (probe.strategyUsed() != locator.primary().kind()) {
            drift.add(new ReplayResult.DriftSignal(step.id(), locator.primary().kind().toString(),
                    probe.strategyUsed().toString(),
                    "primary locator strategy did not resolve; a fallback was used - possible per-tenant/version drift"));
            evidence.event("replay.drift", Map.of("step", step.id(),
                    "expected", locator.primary().kind().toString(), "actual", probe.strategyUsed().toString()));
        }
    }

    private Escalation.HandoffResult raise(Escalation.Trigger trigger, String runId, Artifact a, Step step,
                                           String reason, String question) {
        byte[] png = surface.screenshot();
        evidence.screenshot("escalation", png);
        var req = new Escalation.InterventionRequest(Ids.shortUuid(), runId, a.meta().name(), trigger,
                step == null ? null : step.id(), reason, question, surface.location(), "steps/escalation.png",
                Instant.now(), null, null, null, List.of());
        evidence.event("escalation.raised", Map.of("trigger", trigger.toString(), "reason", reason));
        Escalation.HandoffResult r = escalation.raiseAndWait(req);
        for (Escalation.HumanAction ha : r.actions()) {
            evidence.event("human.action", Map.of("kind", ha.kind(), "detail", ha.detail()));
        }
        return r;
    }

    private ReplayResult.StepResult stepResult(Step step, String status, String strategy, boolean fallback,
                                               List<String> recoveries, long t0, String detail) {
        return new ReplayResult.StepResult(step.id(), step.action(), status, strategy, fallback,
                new ArrayList<>(recoveries), System.currentTimeMillis() - t0, detail);
    }

    private Map<String, Object> redactParams(Artifact a, BindingContext b) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (ParamSpec p : a.parameters()) {
            m.put(p.name(), p.sensitive() ? "‹redacted›" : b.params().get(p.name()));
        }
        return m;
    }

    private String safeDom() {
        try { return surface.rawSnapshot(); } catch (RuntimeException e) { return null; }
    }

    private static String describe(LocatorSpec s) {
        return s == null || s.primary() == null ? "<none>" : s.primary().kind() + "(" +
                (s.primary().name() != null ? s.primary().name() : s.primary().text()) + ")";
    }

    private static String describeCond(Condition c) {
        return c == null ? "<none>" : c.kind() + (c.text() != null ? "('" + c.text() + "')" : "");
    }

    private static void sleep(long ms) {
        try { Thread.sleep(Math.max(0, ms)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
