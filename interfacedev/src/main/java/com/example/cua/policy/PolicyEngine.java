package com.example.cua.policy;

import com.example.cua.artifact.Artifact;
import com.example.cua.core.Json;
import com.example.cua.surface.Surface;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The guardrail layer. Every action - during discovery AND during replay - passes through
 * {@link #check}. It enforces:
 * <ul>
 *   <li>an <b>allowlist</b> of origins / URL patterns and permitted action types;</li>
 *   <li>a <b>risk policy</b>: SAFE actions proceed; IRREVERSIBLE / flagged actions are blocked,
 *       gated on approval, or flagged, per configuration.</li>
 * </ul>
 * The engine never performs side effects; it returns a {@link Decision} the caller acts on
 * (proceed / escalate for approval / hard stop).
 */
public final class PolicyEngine {

    public record Allowlist(
            String id,
            List<String> allowedOrigins,
            List<String> allowedUrlPatterns,   // regex, matched against full URL
            List<String> allowedActionTypes,   // Surface.ActionType names
            String writeActionPolicy,          // ALLOW | REQUIRE_APPROVAL | BLOCK
            int maxSteps,
            long maxWallClockMs,
            boolean allowUnattendedDraft       // may a DRAFT artifact run without a human?
    ) {
        public static Allowlist load(Path file) {
            return Json.readFile(file, Allowlist.class);
        }
    }

    public enum Effect { PROCEED, REQUIRE_APPROVAL, BLOCK }

    public record Decision(Effect effect, String reason) {
        public boolean proceed() { return effect == Effect.PROCEED; }
        public static Decision proceed(String r) { return new Decision(Effect.PROCEED, r); }
        public static Decision approval(String r) { return new Decision(Effect.REQUIRE_APPROVAL, r); }
        public static Decision block(String r) { return new Decision(Effect.BLOCK, r); }
    }

    private final Allowlist allowlist;

    public PolicyEngine(Allowlist allowlist) {
        this.allowlist = allowlist;
    }

    public Allowlist allowlist() { return allowlist; }

    /**
     * @param action        the action about to run
     * @param risk          declared/derived risk of this step (null => derive from action type)
     * @param stepPolicy    per-step override from the artifact (null => none)
     * @param preApproved   whether an operator already approved this specific step this run
     */
    public Decision check(Surface.Action action, Artifact.RiskClass risk, Artifact.StepPolicy stepPolicy, boolean preApproved) {
        String actionType = action.type().name();
        if (allowlist.allowedActionTypes() != null && !allowlist.allowedActionTypes().isEmpty()
                && !allowlist.allowedActionTypes().contains(actionType)) {
            return Decision.block("action type " + actionType + " is not on the allowlist");
        }

        String url = action.type() == Surface.ActionType.NAVIGATE ? action.url() : null;
        if (url != null) {
            Decision urlDecision = checkUrl(url);
            if (!urlDecision.proceed()) return urlDecision;
        }

        Artifact.RiskClass effectiveRisk = risk != null ? risk : deriveRisk(action);

        if (stepPolicy == Artifact.StepPolicy.BLOCK) {
            return Decision.block("step is marked BLOCK in the artifact");
        }
        if (stepPolicy == Artifact.StepPolicy.REQUIRE_APPROVAL && !preApproved) {
            return Decision.approval("step is marked REQUIRE_APPROVAL in the artifact");
        }

        if (effectiveRisk == Artifact.RiskClass.IRREVERSIBLE && !preApproved) {
            return switch (allowlist.writeActionPolicy() == null ? "REQUIRE_APPROVAL" : allowlist.writeActionPolicy().toUpperCase(Locale.ROOT)) {
                case "ALLOW" -> Decision.proceed("irreversible action permitted by policy (writeActionPolicy=ALLOW)");
                case "BLOCK" -> Decision.block("irreversible action blocked by policy (writeActionPolicy=BLOCK)");
                default -> Decision.approval("irreversible action requires human approval (writeActionPolicy=REQUIRE_APPROVAL)");
            };
        }

        return Decision.proceed("within policy (risk=" + effectiveRisk + ")");
    }

    public Decision checkUrl(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (RuntimeException e) {
            return Decision.block("unparseable URL: " + url);
        }
        String origin = uri.getScheme() + "://" + uri.getAuthority();
        boolean originOk = allowlist.allowedOrigins() == null || allowlist.allowedOrigins().isEmpty()
                || allowlist.allowedOrigins().stream().anyMatch(o -> o.equalsIgnoreCase(origin));
        if (!originOk) {
            return Decision.block("origin " + origin + " is not on the allowlist");
        }
        if (allowlist.allowedUrlPatterns() != null && !allowlist.allowedUrlPatterns().isEmpty()) {
            boolean patternOk = allowlist.allowedUrlPatterns().stream().anyMatch(p -> Pattern.compile(p).matcher(url).find());
            if (!patternOk) {
                return Decision.block("URL " + url + " matches no allowed pattern");
            }
        }
        return Decision.proceed("URL within allowlist");
    }

    /** Conservative default classification when the artifact / caller does not specify one. */
    public static Artifact.RiskClass deriveRisk(Surface.Action action) {
        return switch (action.type()) {
            case NAVIGATE, WAIT, SCROLL_TO, EXTRACT, PRESS_KEY -> Artifact.RiskClass.SAFE;
            case TYPE, SELECT_OPTION -> Artifact.RiskClass.SAFE;
            case CLICK -> Artifact.RiskClass.SENSITIVE; // could be a submit - caller should refine
            case ACCEPT_DIALOG, DISMISS_DIALOG -> Artifact.RiskClass.SENSITIVE;
        };
    }
}
