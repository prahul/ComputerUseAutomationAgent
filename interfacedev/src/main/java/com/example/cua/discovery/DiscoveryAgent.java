package com.example.cua.discovery;

import com.example.cua.artifact.Artifact;
import com.example.cua.core.Ids;
import com.example.cua.core.Json;
import com.example.cua.evidence.EvidenceRecorder;
import com.example.cua.escalation.Escalation;
import com.example.cua.policy.PolicyEngine;
import com.example.cua.surface.Surface;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The goal-driven observe -> decide -> act loop. An LLM sees a normalized element list plus a
 * screenshot and calls one of a small set of tools. The loop enforces policy on every action,
 * records evidence, escalates to a human when the model asks or when a stopping condition is hit,
 * and on {@code finish} produces a {@link StepTrace} sequence the {@link ArtifactBuilder} turns
 * into a reusable capability.
 */
public final class DiscoveryAgent {
    private static final Logger log = LoggerFactory.getLogger(DiscoveryAgent.class);

    public record Result(boolean success, Artifact artifact, String runId, String summary, List<StepTrace> traces) {}

    private final LlmClient llm;
    private final Surface surface;
    private final PolicyEngine policy;
    private final EvidenceRecorder evidence;
    private final Escalation.EscalationBroker escalation;
    private final int maxSteps;

    public DiscoveryAgent(LlmClient llm, Surface surface, PolicyEngine policy, EvidenceRecorder evidence,
                          Escalation.EscalationBroker escalation, int maxSteps) {
        this.llm = llm;
        this.surface = surface;
        this.policy = policy;
        this.evidence = evidence;
        this.escalation = escalation;
        this.maxSteps = maxSteps;
    }

    public Result run(GoalSpec spec, String runId) {
        List<LlmClient.ToolDef> tools = tools();
        List<LlmClient.Turn> history = new ArrayList<>();
        List<StepTrace> traces = new ArrayList<>();

        surface.act(Surface.Action.navigate(spec.entryUrl()));
        Surface.Observation obs = surface.observe();
        evidence.event("discovery.start", Map.of("goal", spec.resolvedGoal(), "entryUrl", spec.entryUrl()));
        evidence.screenshot("start", surface.screenshot());

        history.add(LlmClient.Turn.user(List.of(
                LlmClient.Block.text(renderState(obs, spec, 0)),
                LlmClient.Block.image(surface.screenshot()))));

        for (int stepNo = 1; stepNo <= maxSteps; stepNo++) {
            LlmClient.Reply reply = llm.next(systemPrompt(spec), history, tools);
            history.add(new LlmClient.Turn(LlmClient.Turn.Role.ASSISTANT, reply.rawAssistantBlocks()));
            if (!reply.assistantText().isBlank()) {
                evidence.event("agent.thought", Map.of("step", stepNo, "text", reply.assistantText()));
            }

            if (reply.toolCalls().isEmpty()) {
                // model narrated without acting - nudge it once, then treat as stuck
                history.add(LlmClient.Turn.user(List.of(LlmClient.Block.text(
                        "You did not call a tool. Call exactly one tool to make progress, or call `escalate` if you are stuck."))));
                continue;
            }

            List<LlmClient.Block> results = new ArrayList<>();
            boolean finished = false;
            for (LlmClient.Block call : reply.toolCalls()) {
                ToolOutcome outcome = dispatch(call, spec, obs, traces, stepNo, runId);
                results.add(LlmClient.Block.toolResult(call.toolUseId(), outcome.message(), outcome.isError()));
                if (outcome.finished()) {
                    finished = true;
                }
            }

            if (finished) {
                obs = surface.observe();
                Artifact artifact = new ArtifactBuilder().build(spec, traces, obs, runId, lastFinishSignal);
                evidence.writeText(evidence.dir().resolve("artifact.json"), Json.write(artifact));
                evidence.event("discovery.finish", Map.of("steps", traces.size(), "capability", artifact.meta().name()));
                return new Result(true, artifact, runId, lastFinishSummary, traces);
            }

            obs = surface.observe();
            evidence.screenshot("step-" + stepNo, surface.screenshot());
            results.add(LlmClient.Block.text(renderState(obs, spec, stepNo)));
            results.add(LlmClient.Block.image(surface.screenshot()));
            history.add(LlmClient.Turn.user(results));
        }

        // ran out of steps -> escalate
        Escalation.HandoffResult handoff = escalate(Escalation.Trigger.MAX_STEPS, runId, spec,
                "Discovery did not reach the goal within " + maxSteps + " steps.", null, traces);
        evidence.event("discovery.exhausted", Map.of("resolution", handoff.resolution().toString()));
        return new Result(false, null, runId, "did not reach goal in " + maxSteps + " steps", traces);
    }

    // --- tool dispatch --------------------------------------------------------------------------

    private String lastFinishSummary = "";
    private String lastFinishSignal = "";

    private record ToolOutcome(String message, boolean isError, boolean finished) {
        static ToolOutcome ok(String m) { return new ToolOutcome(m, false, false); }
        static ToolOutcome error(String m) { return new ToolOutcome(m, true, false); }
        static ToolOutcome done(String m) { return new ToolOutcome(m, false, true); }
    }

    private ToolOutcome dispatch(LlmClient.Block call, GoalSpec spec, Surface.Observation obs,
                                 List<StepTrace> traces, int stepNo, String runId) {
        JsonNode in = call.toolInput();
        String tool = call.toolName();
        String traceId = "s" + (traces.size() + 1);
        try {
            switch (tool) {
                case "finish" -> {
                    lastFinishSummary = text(in, "summary");
                    lastFinishSignal = text(in, "success_signal_text");
                    return ToolOutcome.done("recorded finish");
                }
                case "escalate" -> {
                    Escalation.HandoffResult h = escalate(Escalation.Trigger.AGENT_STUCK, runId, spec,
                            text(in, "reason"), text(in, "question"), traces);
                    return ToolOutcome.ok("operator " + h.resolution() + "; " + h.actions().size()
                            + " manual action(s) recorded. Re-observe and continue.");
                }
                case "navigate" -> {
                    String url = text(in, "url");
                    Surface.Action a = Surface.Action.navigate(url);
                    PolicyEngine.Decision d = policy.check(a, Artifact.RiskClass.SAFE, null, false);
                    if (!d.proceed()) return blocked(a, d, runId, spec, traces);
                    surface.act(a);
                    traces.add(trace(traceId, a, null, "navigate", text(in, "why"), Artifact.RiskClass.SAFE, null, null, null, null));
                    return ToolOutcome.ok("navigated to " + surface.location());
                }
                case "press_key" -> {
                    Surface.Action a = Surface.Action.pressKey(text(in, "key"));
                    surface.act(a);
                    traces.add(trace(traceId, a, null, "press key", text(in, "why"), Artifact.RiskClass.SAFE, null, null, null, null));
                    return ToolOutcome.ok("pressed " + text(in, "key"));
                }
                case "click", "type", "select_option", "extract" -> {
                    String ref = text(in, "target_ref");
                    Optional<Surface.UiElement> el = obs.elements().stream().filter(e -> e.ref().equals(ref)).findFirst();
                    if (el.isEmpty()) return ToolOutcome.error("no element " + ref + " in the current screen; re-check the list");
                    Artifact.LocatorSpec locator = LocatorBuilder.forElement(el.get());

                    Surface.Action a;
                    Artifact.RiskClass risk;
                    Artifact.ValueSpec bound = null;
                    String outName = null;
                    Artifact.ValueType outType = null;
                    switch (tool) {
                        case "click" -> {
                            a = Surface.Action.click(ref).withLocator(locator);
                            risk = classifyClick(el.get());
                        }
                        case "type" -> {
                            String raw = text(in, "text");
                            String resolved = raw;
                            if (raw != null && raw.startsWith("__SECRET__:")) {
                                String secretName = raw.substring("__SECRET__:".length());
                                resolved = spec.secrets().get(secretName);
                                if (resolved == null) return ToolOutcome.error("unknown secret " + secretName);
                                bound = Artifact.ValueSpec.secret(secretName);
                            } else {
                                bound = bindValue(raw, spec);
                            }
                            a = Surface.Action.type(ref, resolved).withLocator(locator);
                            risk = Artifact.RiskClass.SAFE;
                        }
                        case "select_option" -> {
                            String opt = text(in, "option_label");
                            bound = bindValue(opt, spec);
                            a = Surface.Action.selectOption(ref, opt).withLocator(locator);
                            risk = Artifact.RiskClass.SAFE;
                        }
                        default -> { // extract
                            a = Surface.Action.extract(ref).withLocator(locator);
                            risk = Artifact.RiskClass.SAFE;
                            outName = text(in, "output_name");
                            outType = parseType(text(in, "output_type"));
                        }
                    }

                    PolicyEngine.Decision d = policy.check(a, risk, null, false);
                    if (d.effect() == PolicyEngine.Effect.BLOCK) return blocked(a, d, runId, spec, traces);
                    if (d.effect() == PolicyEngine.Effect.REQUIRE_APPROVAL) {
                        Escalation.HandoffResult h = escalate(Escalation.Trigger.POLICY_APPROVAL, runId, spec,
                                d.reason() + " (" + tool + " on " + describe(el.get()) + ")", null, traces);
                        if (h.resolution() == Escalation.Resolution.ABORT) {
                            return ToolOutcome.error("operator declined the " + tool + " action");
                        }
                        evidence.event("policy.approved", Map.of("action", tool, "by", h.operator()));
                    }

                    String beforeUrl = surface.location();
                    Surface.ActionResult r = surface.act(a);
                    if (!r.ok()) return ToolOutcome.error("action failed: " + r.detail());

                    String extracted = r.extractedText();
                    if ("extract".equals(tool)) {
                        evidence.event("agent.extract", Map.of("name", String.valueOf(outName), "value", String.valueOf(extracted)));
                    }
                    traces.add(trace(traceId, a, locator, toolIntent(tool), text(in, "why_this_target"),
                            risk, extracted, outName, outType, bound, beforeUrl, obs.textDigest()));
                    return ToolOutcome.ok(tool + " ok on " + describe(el.get())
                            + (extracted != null && !extracted.isBlank() ? " -> \"" + trim(extracted, 120) + "\"" : ""));
                }
                default -> {
                    return ToolOutcome.error("unknown tool " + tool);
                }
            }
        } catch (RuntimeException e) {
            log.warn("tool dispatch failed", e);
            evidence.event("tool.exception", Map.of("tool", tool, "error", String.valueOf(e.getMessage())));
            return ToolOutcome.error(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private ToolOutcome blocked(Surface.Action a, PolicyEngine.Decision d, String runId, GoalSpec spec, List<StepTrace> traces) {
        evidence.event("policy.block", Map.of("action", a.type().toString(), "reason", d.reason()));
        escalate(Escalation.Trigger.POLICY_APPROVAL, runId, spec, "Blocked by policy: " + d.reason(), null, traces);
        return ToolOutcome.error("blocked by policy: " + d.reason());
    }

    private Escalation.HandoffResult escalate(Escalation.Trigger trigger, String runId, GoalSpec spec,
                                              String reason, String question, List<StepTrace> traces) {
        byte[] png = surface.screenshot();
        evidence.screenshot("escalation", png);
        String shot = "steps/" + "escalation.png";
        var req = new Escalation.InterventionRequest(
                Ids.shortUuid(), runId, spec.capabilityName(), trigger,
                traces.isEmpty() ? null : traces.get(traces.size() - 1).id(),
                reason, question, surface.location(), shot,
                java.time.Instant.now(), null, null, null, List.of());
        evidence.event("escalation.raised", Map.of("trigger", trigger.toString(), "reason", reason,
                "question", question == null ? "" : question));
        Escalation.HandoffResult result = escalation.raiseAndWait(req);
        for (Escalation.HumanAction ha : result.actions()) {
            evidence.event("human.action", Map.of("kind", ha.kind(), "detail", ha.detail()));
        }
        evidence.event("escalation.resolved", Map.of("resolution", result.resolution().toString(),
                "operator", result.operator(), "note", result.note() == null ? "" : result.note()));
        return result;
    }

    // --- prompt + rendering -------------------------------------------------------------------

    private String systemPrompt(GoalSpec spec) {
        return """
            You are a computer-use agent operating a back-office web application the way a human
            operator would. You perceive the screen as a numbered list of UI elements plus a
            screenshot, and you act by calling exactly one tool per turn.

            GOAL: %s

            ENTRY POINT: %s

            RULES
            - Prefer targeting elements by their visible role and accessible name. Every time you act
              on an element, briefly justify in `why_this_target` why that identification is robust
              (e.g. "the button label 'Search' is a stable functional control").
            - Work in small, verifiable steps. After each action you will see the new screen state.
            - Read values off the screen with `extract` (give each a clear output_name and type).
              Target the cell/field that HOLDS the value, not the printed number itself, so the
              capability still works for other parameter values.
            - Do NOT take irreversible actions (submitting a form that creates/deletes data,
              confirming a dialog) unless the goal explicitly requires it. If one is required, take
              it deliberately - the guardrail layer may ask a human to approve.
            - If you are stuck, looping, or the screen shows something you cannot safely handle,
              call `escalate` with a clear reason and (if useful) a question for the operator.
            - When the goal is achieved and the success state is visible on screen, call `finish`.
              `success_signal_text` must be a STABLE on-screen label that proves you reached the
              right screen (e.g. "Savings balance", "Sub-Account Confirmation") - not a specific
              value or name that changes per run.

            CREDENTIALS
            - If you reach a sign-in form, type the exact literal string "__SECRET__:credentials.username"
              into the username field and "__SECRET__:credentials.password" into the password field.
              The harness substitutes the real values and never records them. Never invent credentials.

            The parameters this capability will expose: %s
            """.formatted(spec.resolvedGoal(), spec.entryUrl(), paramSummary(spec));
    }

    private String paramSummary(GoalSpec spec) {
        if (spec.parameters().isEmpty()) return "(none)";
        StringBuilder sb = new StringBuilder();
        for (Artifact.ParamSpec p : spec.parameters()) {
            sb.append(p.name()).append(" (").append(p.type()).append(", current run value = \"")
              .append(spec.paramValues().getOrDefault(p.name(), "")).append("\"); ");
        }
        return sb.toString();
    }

    private String renderState(Surface.Observation obs, GoalSpec spec, int step) {
        StringBuilder sb = new StringBuilder();
        sb.append("SCREEN @ step ").append(step).append('\n');
        sb.append("url: ").append(obs.location()).append('\n');
        sb.append("title: ").append(obs.title()).append('\n');
        sb.append("visible text (excerpt): ").append(trim(obs.textDigest(), 600)).append("\n\n");
        sb.append("ELEMENTS (use the [ref] token as target_ref):\n");
        for (Surface.UiElement e : obs.elements()) {
            if (e.role().isBlank() && e.name().isBlank() && e.text().isBlank()) continue;
            sb.append("  [").append(e.ref()).append("] ");
            if (!e.role().isBlank()) sb.append(e.role()).append(' ');
            if (!e.name().isBlank()) sb.append('"').append(trim(e.name(), 60)).append('"');
            else if (!e.text().isBlank()) sb.append('"').append(trim(e.text(), 60)).append('"');
            if (e.editable()) sb.append(" [editable]");
            if (!e.enabled()) sb.append(" [disabled]");
            if (e.value() != null && !e.value().isBlank()) sb.append(" value=\"").append(trim(e.value(), 40)).append('"');
            sb.append('\n');
        }
        return sb.toString();
    }

    // --- helpers ------------------------------------------------------------------------------

    private List<LlmClient.ToolDef> tools() {
        List<LlmClient.ToolDef> t = new ArrayList<>();
        t.add(new LlmClient.ToolDef("click", "Click an element (button, link, checkbox).",
                AnthropicLlmClient.objectSchema(Map.of(
                        "target_ref", AnthropicLlmClient.stringProp("the [ref] token of the element"),
                        "why_this_target", AnthropicLlmClient.stringProp("why this identification is robust"),
                        "why", AnthropicLlmClient.stringProp("why this step is needed")),
                        List.of("target_ref", "why_this_target"))));
        t.add(new LlmClient.ToolDef("type", "Type text into a text field (clears it first).",
                AnthropicLlmClient.objectSchema(Map.of(
                        "target_ref", AnthropicLlmClient.stringProp("the [ref] token of the field"),
                        "text", AnthropicLlmClient.stringProp("text to type"),
                        "why_this_target", AnthropicLlmClient.stringProp("why this identification is robust"),
                        "why", AnthropicLlmClient.stringProp("why this step is needed")),
                        List.of("target_ref", "text", "why_this_target"))));
        t.add(new LlmClient.ToolDef("select_option", "Choose an option in a dropdown by its visible label.",
                AnthropicLlmClient.objectSchema(Map.of(
                        "target_ref", AnthropicLlmClient.stringProp("the [ref] token of the <select>"),
                        "option_label", AnthropicLlmClient.stringProp("the visible option text"),
                        "why_this_target", AnthropicLlmClient.stringProp("why this identification is robust"),
                        "why", AnthropicLlmClient.stringProp("why this step is needed")),
                        List.of("target_ref", "option_label", "why_this_target"))));
        t.add(new LlmClient.ToolDef("press_key", "Press a keyboard key (e.g. Enter, Tab).",
                AnthropicLlmClient.objectSchema(Map.of(
                        "key", AnthropicLlmClient.stringProp("key name"),
                        "why", AnthropicLlmClient.stringProp("why this step is needed")),
                        List.of("key"))));
        t.add(new LlmClient.ToolDef("navigate", "Navigate directly to a URL within the allowed target.",
                AnthropicLlmClient.objectSchema(Map.of(
                        "url", AnthropicLlmClient.stringProp("absolute URL"),
                        "why", AnthropicLlmClient.stringProp("why this step is needed")),
                        List.of("url"))));
        t.add(new LlmClient.ToolDef("extract", "Read a value off the current screen and declare it as a typed output.",
                AnthropicLlmClient.objectSchema(Map.of(
                        "target_ref", AnthropicLlmClient.stringProp("the [ref] token of the element holding the value"),
                        "output_name", AnthropicLlmClient.stringProp("camelCase name for this output"),
                        "output_type", AnthropicLlmClient.stringProp("one of STRING, INTEGER, NUMBER, BOOLEAN, MONEY, DATE"),
                        "why_this_target", AnthropicLlmClient.stringProp("why this identification is robust")),
                        List.of("target_ref", "output_name", "output_type", "why_this_target"))));
        t.add(new LlmClient.ToolDef("finish", "Declare the goal achieved.",
                AnthropicLlmClient.objectSchema(Map.of(
                        "summary", AnthropicLlmClient.stringProp("one-line summary of what was accomplished"),
                        "success_signal_text", AnthropicLlmClient.stringProp("exact on-screen text proving success")),
                        List.of("summary", "success_signal_text"))));
        t.add(new LlmClient.ToolDef("escalate", "Hand control to a human operator.",
                AnthropicLlmClient.objectSchema(Map.of(
                        "reason", AnthropicLlmClient.stringProp("why you are stuck / what is unsafe"),
                        "question", AnthropicLlmClient.stringProp("optional question for the operator")),
                        List.of("reason"))));
        return t;
    }

    private Artifact.ValueSpec bindValue(String value, GoalSpec spec) {
        if (value == null) return Artifact.ValueSpec.literal("");
        for (var e : spec.paramValues().entrySet()) {
            if (value.equals(e.getValue())) return Artifact.ValueSpec.param(e.getKey());
        }
        for (var e : spec.secrets().entrySet()) {
            if (value.equals(e.getValue())) return Artifact.ValueSpec.secret(e.getKey());
        }
        return Artifact.ValueSpec.literal(value);
    }

    private Artifact.RiskClass classifyClick(Surface.UiElement e) {
        String n = (e.name() + " " + e.text()).toLowerCase();
        if (n.matches(".*\\b(submit|confirm|create|delete|remove|approve|transfer|pay|withdraw|close account|post)\\b.*")) {
            return Artifact.RiskClass.IRREVERSIBLE;
        }
        if ("button".equals(e.role()) && n.matches(".*\\b(search|look ?up|find|view|next|continue|go|sign in|log ?in)\\b.*")) {
            return Artifact.RiskClass.SAFE;
        }
        return Artifact.RiskClass.SENSITIVE;
    }

    private StepTrace trace(String id, Surface.Action a, Artifact.LocatorSpec locator, String intent, String targetRationale,
                            Artifact.RiskClass risk, String extracted, String outName, Artifact.ValueType outType,
                            Artifact.ValueSpec bound) {
        return trace(id, a, locator, intent, targetRationale, risk, extracted, outName, outType, bound,
                surface.location(), "");
    }

    private StepTrace trace(String id, Surface.Action a, Artifact.LocatorSpec locator, String intent, String targetRationale,
                            Artifact.RiskClass risk, String extracted, String outName, Artifact.ValueType outType,
                            Artifact.ValueSpec bound, String locationBefore, String textBefore) {
        return new StepTrace(id, a, locator, intent, targetRationale,
                trim(textBefore, 4000), trim(surface.observe().textDigest(), 4000), locationBefore, surface.location(),
                extracted, outName, outType, bound, risk);
    }

    private static String toolIntent(String tool) {
        return switch (tool) {
            case "click" -> "click"; case "type" -> "enter value"; case "select_option" -> "choose option";
            case "extract" -> "read value"; default -> tool;
        };
    }

    private static Artifact.ValueType parseType(String s) {
        try { return Artifact.ValueType.valueOf(s.trim().toUpperCase()); }
        catch (RuntimeException e) { return Artifact.ValueType.STRING; }
    }

    private static String describe(Surface.UiElement e) {
        String label = !e.name().isBlank() ? e.name() : e.text();
        return (e.role().isBlank() ? "element" : e.role()) + " \"" + trim(label, 40) + "\"";
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
