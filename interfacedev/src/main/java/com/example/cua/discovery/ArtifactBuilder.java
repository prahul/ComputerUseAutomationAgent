package com.example.cua.discovery;

import com.example.cua.artifact.Artifact;
import com.example.cua.artifact.Artifact.*;
import com.example.cua.core.Ids;
import com.example.cua.surface.Surface;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Turns a discovery {@link StepTrace} sequence into a reusable {@link Artifact}: synthesizes a
 * checkpoint per step, derives the success condition, attaches the domain's known business outcomes
 * and recovery rules, and lifts typed parameters/outputs to the top-level contract.
 */
public final class ArtifactBuilder {

    public Artifact build(GoalSpec spec, List<StepTrace> traces, Surface.Observation finalObs,
                          String runId, String successSignal) {
        List<Step> steps = new ArrayList<>();

        // The discovery harness navigates to the entry point before handing control to the agent;
        // replay has no such implicit step, so record it explicitly as the first step.
        String landingPath = traces.isEmpty() ? "/" : pathOf(traces.get(0).locationBefore());
        steps.add(new Step("s0", ActionKind.NAVIGATE, "open the application entry point",
                null, null, templateEntryUrl(spec.entryUrl()), null, null,
                new Checkpoint(Condition.urlMatches(landingPath), 15_000, "loaded " + landingPath),
                RiskClass.SAFE, StepPolicy.ALLOW));

        for (int i = 0; i < traces.size(); i++) {
            StepTrace next = i + 1 < traces.size() ? traces.get(i + 1) : null;
            steps.add(toStep(traces.get(i), next));
        }

        List<OutputSpec> outputs = new ArrayList<>();
        for (StepTrace t : traces) {
            if (t.outputName() != null && !t.outputName().isBlank()) {
                outputs.add(new OutputSpec(
                        t.outputName(),
                        t.outputType() == null ? ValueType.STRING : t.outputType(),
                        "Read from step " + t.id() + " during the discovery run.",
                        new OutputSource(t.id(), transformFor(t.outputType()),
                                t.outputType() == ValueType.MONEY ? "([$]?[0-9,]+(?:\\.[0-9]{2})?)" : null)));
            }
        }

        Condition success = successSignal != null && !successSignal.isBlank()
                ? Condition.textPresent(stableLabel(successSignal))
                : Condition.textPresent(lastMeaningfulToken(finalObs.textDigest()));

        String entryUrl = spec.entryUrl();
        Meta meta = new Meta(
                Ids.uuid(), spec.capabilityName(), "1.0.0",
                titleCase(spec.capabilityName()),
                "Discovered capability: " + spec.goal(),
                runId, Instant.now(), "discovery-agent",
                ApprovalState.DRAFT, null);

        return new Artifact(
                Artifact.SCHEMA_VERSION,
                meta,
                new SurfaceSpec("web", "playwright-chromium", finalObs.viewportWidth(), finalObs.viewportHeight()),
                new TargetSpec("cu-servicing-console",
                        spec.vendorProduct() == null ? "acme-servicing" : spec.vendorProduct(),
                        templateEntryUrl(entryUrl), "default"),
                new ArrayList<>(spec.parameters()),
                outputs,
                steps,
                success,
                DomainLibrary.knownOutcomes(),
                DomainLibrary.recoveries(),
                new Redaction(
                        List.of("credentials.password"),
                        spec.parameters().stream().filter(ParamSpec::sensitive).map(ParamSpec::name).toList(),
                        Redaction.defaults().patterns()));
    }

    private Step toStep(StepTrace t, StepTrace next) {
        Surface.Action a = t.action();
        ActionKind kind = switch (a.type()) {
            case NAVIGATE -> ActionKind.NAVIGATE;
            case CLICK -> ActionKind.CLICK;
            case TYPE -> ActionKind.TYPE;
            case SELECT_OPTION -> ActionKind.SELECT_OPTION;
            case PRESS_KEY -> ActionKind.PRESS_KEY;
            case EXTRACT -> ActionKind.EXTRACT;
            case SCROLL_TO -> ActionKind.SCROLL_TO;
            case WAIT -> ActionKind.WAIT;
            default -> ActionKind.CLICK;
        };

        ValueSpec value = t.boundValue();
        String url = kind == ActionKind.NAVIGATE ? templateEntryUrl(a.url()) : null;
        Checkpoint checkpoint = synthesizeCheckpoint(t, kind, next);
        StepPolicy policy = t.risk() == RiskClass.IRREVERSIBLE ? StepPolicy.REQUIRE_APPROVAL : StepPolicy.ALLOW;

        return new Step(t.id(), kind, describeIntent(t), t.locator(), value, url, a.key(), a.waitMs(),
                checkpoint, t.risk(), policy);
    }

    private Checkpoint synthesizeCheckpoint(StepTrace t, ActionKind kind, StepTrace next) {
        return switch (kind) {
            case NAVIGATE -> new Checkpoint(Condition.urlMatches(pathOf(t.locationAfter())), 10_000,
                    "landed on " + pathOf(t.locationAfter()));
            case TYPE -> {
                if (t.boundValue() != null && t.boundValue().literal() != null && !t.boundValue().literal().isBlank()) {
                    yield new Checkpoint(new Condition(Condition.Kind.VALUE_EQUALS, null, false,
                            t.locator(), t.boundValue().literal(), null, null), 5_000, "field holds the typed value");
                }
                yield new Checkpoint(Condition.elementVisible(t.locator()), 5_000, "field is present");
            }
            case EXTRACT -> new Checkpoint(Condition.elementVisible(t.locator()), 8_000, "value element is visible");
            case CLICK -> {
                String beforePath = pathOf(t.locationBefore());
                String afterPath = pathOf(t.locationAfter());
                if (!afterPath.equals(beforePath)) {
                    yield new Checkpoint(Condition.urlMatches(afterPath), 10_000, "navigated to " + afterPath);
                }
                // a click that reveals data on the same page: assert the thing we are about to
                // read/act on is now visible - stable regardless of the parameter values
                if (next != null && next.locator() != null
                        && (next.action().type() == Surface.ActionType.EXTRACT
                            || next.action().type() == Surface.ActionType.CLICK)) {
                    yield new Checkpoint(Condition.elementVisible(next.locator()), 10_000,
                            "the next target is visible after the click");
                }
                String token = newDistinctiveToken(t.observedTextBefore(), t.observedTextAfter());
                yield token == null
                        ? new Checkpoint(Condition.urlMatches(afterPath), 10_000, "page settled")
                        : new Checkpoint(Condition.textPresent(token), 10_000, "post-click screen shows \"" + token + "\"");
            }
            default -> null;
        };
    }

    private static String describeIntent(StepTrace t) {
        String base = t.modelIntent() == null || t.modelIntent().isBlank() ? t.action().type().toString().toLowerCase() : t.modelIntent();
        return t.modelTargetRationale() == null || t.modelTargetRationale().isBlank()
                ? base : base + " — " + shorten(t.modelTargetRationale());
    }

    private static String transformFor(ValueType type) {
        if (type == null) return null;
        return switch (type) {
            case MONEY -> "money";
            case INTEGER -> "digits";
            default -> "trim";
        };
    }

    // --- text heuristics ---------------------------------------------------------------------

    private static final Set<String> STOP = Set.of("the", "and", "for", "with", "you", "your", "this", "that",
            "from", "have", "not", "are", "was", "can", "will", "all", "page", "please", "click", "here", "back",
            "home", "logout", "menu", "search");

    private static String distinctiveToken(String text) {
        if (text == null) return null;
        for (String w : text.split("[^A-Za-z]+")) {
            String lw = w.toLowerCase();
            if (w.length() >= 5 && !STOP.contains(lw)) return w;
        }
        return null;
    }

    /** First token that appears AFTER an action but not before it - excludes persistent chrome. */
    private static String newDistinctiveToken(String before, String after) {
        if (after == null) return null;
        java.util.Set<String> beforeSet = new java.util.HashSet<>();
        if (before != null) for (String w : before.toLowerCase().split("[^a-z]+")) beforeSet.add(w);
        for (String w : after.split("[^A-Za-z]+")) {
            String lw = w.toLowerCase();
            if (w.length() >= 5 && !STOP.contains(lw) && !beforeSet.contains(lw) && !w.matches(".*\\d.*")) {
                return w;
            }
        }
        return distinctiveToken(after);
    }

    /** Strip trailing run-specific values (money, numbers) from a success signal to keep it stable. */
    private static String stableLabel(String signal) {
        String s = signal.replaceAll("\\s+", " ").trim();
        s = s.replaceAll("[$£€]?\\s*[0-9][0-9,]*(?:\\.[0-9]+)?%?\\s*$", "").trim();
        s = s.replaceAll("[:\\-–]\\s*$", "").trim();
        return s.isBlank() ? shorten(signal) : shorten(s);
    }

    private static String lastMeaningfulToken(String text) {
        String t = distinctiveToken(text);
        return t == null ? "" : t;
    }

    private static String pathOf(String url) {
        try {
            String p = URI.create(url).getPath();
            return p == null || p.isBlank() ? "/" : p;
        } catch (RuntimeException e) {
            return url;
        }
    }

    private static String templateEntryUrl(String url) {
        if (url == null) return null;
        try {
            URI u = URI.create(url);
            String origin = u.getScheme() + "://" + u.getAuthority();
            return url.replace(origin, "${tenant.baseUrl}");
        } catch (RuntimeException e) {
            return url;
        }
    }

    private static String shorten(String s) {
        s = s == null ? "" : s.replaceAll("\\s+", " ").trim();
        return s.length() <= 80 ? s : s.substring(0, 80);
    }

    private static String titleCase(String snake) {
        String[] parts = snake.split("[_\\s]+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1)).append(' ');
        }
        return sb.toString().trim();
    }
}
