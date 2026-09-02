package com.example.cua.artifact;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The capability artifact: a typed, versioned, serializable description of a UI flow that an AI
 * agent can invoke by name. It is deliberately decoupled from the raw model transcript - the
 * transcript is evidence, the artifact is the contract.
 *
 * <p>Design goals baked into the shape:
 * <ul>
 *   <li><b>Callable contract, not a macro.</b> {@code parameters} and {@code outputs} are typed so a
 *       calling agent (and a human reviewer) can understand what it needs and what it returns without
 *       reading the steps.</li>
 *   <li><b>Durable targeting.</b> Every {@link Step} carries a {@link LocatorSpec} with an ordered
 *       strategy chain and a human-readable rationale, so replay never depends on a single brittle
 *       selector.</li>
 *   <li><b>Explicit outcomes.</b> {@code knownOutcomes} lets replay separate legitimate business
 *       results ("no such member") from failures; {@code recoveries} handles transient/interstitial
 *       conditions without an LLM.</li>
 *   <li><b>Surface-agnostic core + surface hints.</b> {@code surface} describes the perception/action
 *       backend; the steps themselves reference roles, names and text, which generalize across a web
 *       app, a legacy frameset, or a desktop accessibility tree.</li>
 *   <li><b>Multi-tenant reuse.</b> The artifact is keyed by {@code vendorProduct} + {@code name} +
 *       {@code version}, never by tenant. A {@code TenantProfile} supplies base URL and per-step
 *       locator overrides at replay time.</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Artifact(
        String schemaVersion,
        Meta meta,
        SurfaceSpec surface,
        TargetSpec target,
        List<ParamSpec> parameters,
        List<OutputSpec> outputs,
        List<Step> steps,
        Condition success,
        List<KnownOutcome> knownOutcomes,
        List<Recovery> recoveries,
        Redaction redaction
) {
    public static final String SCHEMA_VERSION = "1.0";

    public Step step(String id) {
        return steps.stream().filter(s -> s.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no step " + id));
    }

    // ---------------------------------------------------------------------------------------------

    public record Meta(
            String id,
            String name,               // stable machine name, e.g. "lookup_member_savings_balance"
            String version,            // semver; bump on any behavioural change
            String title,
            String description,        // what the capability does, for humans and calling agents
            String sourceRunId,        // the discovery run that produced it
            Instant createdAt,
            String createdBy,
            ApprovalState approval,    // draft flows may not run unattended (see PolicyEngine)
            Double replayConfidence    // 0..1, updated by multi-run stability checks; null if unknown
    ) {}

    public enum ApprovalState { DRAFT, APPROVED, DEPRECATED }

    /** Describes the perception/action backend the flow was recorded against. */
    public record SurfaceSpec(
            String kind,        // "web" | "legacy-web" | "desktop"
            String engine,      // "playwright-chromium"
            int viewportWidth,
            int viewportHeight
    ) {}

    public record TargetSpec(
            String appId,          // logical app, e.g. "cu-servicing-console"
            String vendorProduct,  // the shared underlying product, e.g. "acme-servicing"
            String entryUrl,       // may be "${tenant.baseUrl}/..." - resolved per invocation
            String allowlistId     // which allowlist policy governs this capability
    ) {}

    /** A typed input the calling agent supplies per invocation. */
    public record ParamSpec(
            String name,
            ValueType type,
            boolean required,
            boolean sensitive,     // never persisted raw into artifacts, logs or evidence
            String description,
            String example,
            String pattern,        // optional regex the value must satisfy
            String defaultValue
    ) {}

    /** A typed value the caller gets back. */
    public record OutputSpec(
            String name,
            ValueType type,
            String description,
            OutputSource source
    ) {}

    /** Where an output is read from during replay. */
    public record OutputSource(
            String stepId,             // the EXTRACT step that produced it
            String transform,          // optional: "money" | "trim" | "digits" | null
            String regex               // optional: capture group 1 from the element text
    ) {}

    public enum ValueType { STRING, INTEGER, NUMBER, BOOLEAN, MONEY, DATE }

    // ---------------------------------------------------------------------------------------------

    public record Step(
            String id,
            ActionKind action,
            String intent,             // one line, why this step exists (for reviewers)
            LocatorSpec target,        // null for NAVIGATE / WAIT / PRESS_KEY
            ValueSpec value,           // for TYPE / SELECT_OPTION
            String url,                // for NAVIGATE (may reference ${tenant.baseUrl})
            String key,                // for PRESS_KEY
            Long waitMs,               // for WAIT
            Checkpoint checkpoint,     // asserted after the action; null => no assertion
            RiskClass risk,
            StepPolicy policy          // how the guardrail layer treats this step
    ) {}

    public enum ActionKind { NAVIGATE, CLICK, TYPE, SELECT_OPTION, PRESS_KEY, WAIT, SCROLL_TO, EXTRACT }

    public enum RiskClass {
        /** No lasting effect; freely replayable (navigate, read, type into a search box). */
        SAFE,
        /** Reveals or mutates data but reversible / low blast radius. */
        SENSITIVE,
        /** Creates, submits, deletes, or confirms - not safely undoable. */
        IRREVERSIBLE
    }

    public enum StepPolicy { ALLOW, REQUIRE_APPROVAL, BLOCK }

    // ---------------------------------------------------------------------------------------------

    /**
     * An ordered chain of ways to identify one control. Replay tries {@code primary}, then each
     * {@code fallbacks} entry in order, and records which one actually resolved (a drift signal
     * if it was not the primary).
     */
    public record LocatorSpec(
            LocatorStrategy primary,
            List<LocatorStrategy> fallbacks,
            String rationale           // why this ordering is robust, for a human reviewer
    ) {}

    /**
     * A single targeting method. Only the fields relevant to {@code kind} are populated.
     * Ordered here roughly most-durable to least-durable.
     */
    public record LocatorStrategy(
            Kind kind,
            String role,          // ROLE_NAME
            String name,          // ROLE_NAME (accessible name)
            String text,          // TEXT (normalized visible text, exact)
            String labelText,     // LABEL (associated <label>)
            String placeholder,   // PLACEHOLDER
            String testId,        // TEST_ID (data-testid / data-test / id) - rare in legacy apps
            String css,           // CSS
            String xpath,         // XPATH
            String near,          // ANCHORED: accessible name of a nearby stable element
            Integer nth,          // ANCHORED / TEXT: disambiguating index among matches
            Double normX,         // BBOX: viewport-normalized center (0..1)
            Double normY
    ) {
        public enum Kind { ROLE_NAME, LABEL, PLACEHOLDER, TEXT, TEST_ID, ANCHORED, CSS, XPATH, BBOX }

        public static LocatorStrategy roleName(String role, String name) {
            return new LocatorStrategy(Kind.ROLE_NAME, role, name, null, null, null, null, null, null, null, null, null, null);
        }
        public static LocatorStrategy text(String text) {
            return new LocatorStrategy(Kind.TEXT, null, null, text, null, null, null, null, null, null, null, null, null);
        }
        public static LocatorStrategy label(String labelText) {
            return new LocatorStrategy(Kind.LABEL, null, null, null, labelText, null, null, null, null, null, null, null, null);
        }
        public static LocatorStrategy placeholder(String ph) {
            return new LocatorStrategy(Kind.PLACEHOLDER, null, null, null, null, ph, null, null, null, null, null, null, null);
        }
        public static LocatorStrategy testId(String id) {
            return new LocatorStrategy(Kind.TEST_ID, null, null, null, null, null, id, null, null, null, null, null, null);
        }
        public static LocatorStrategy css(String css) {
            return new LocatorStrategy(Kind.CSS, null, null, null, null, null, null, css, null, null, null, null, null);
        }
        public static LocatorStrategy bbox(double nx, double ny) {
            return new LocatorStrategy(Kind.BBOX, null, null, null, null, null, null, null, null, null, null, nx, ny);
        }
    }

    // ---------------------------------------------------------------------------------------------

    /** A condition asserted after a step to confirm we reached the expected state. */
    public record Checkpoint(
            Condition condition,
            long timeoutMs,
            String description
    ) {}

    /**
     * A boolean predicate over the current observation. Composable via {@code allOf} / {@code anyOf}.
     * Used for checkpoints, success conditions, known-outcome detectors and recovery triggers.
     */
    public record Condition(
            Kind kind,
            String text,               // TEXT_PRESENT / TEXT_ABSENT / URL_MATCHES (substring or regex)
            boolean regex,
            LocatorSpec target,        // ELEMENT_VISIBLE / ELEMENT_ENABLED / VALUE_EQUALS
            String expectedValue,      // VALUE_EQUALS
            List<Condition> allOf,
            List<Condition> anyOf
    ) {
        public enum Kind { TEXT_PRESENT, TEXT_ABSENT, URL_MATCHES, ELEMENT_VISIBLE, ELEMENT_ENABLED, VALUE_EQUALS, ALL_OF, ANY_OF }

        public static Condition textPresent(String t) {
            return new Condition(Kind.TEXT_PRESENT, t, false, null, null, null, null);
        }
        public static Condition textAbsent(String t) {
            return new Condition(Kind.TEXT_ABSENT, t, false, null, null, null, null);
        }
        public static Condition urlMatches(String t) {
            return new Condition(Kind.URL_MATCHES, t, false, null, null, null, null);
        }
        public static Condition elementVisible(LocatorSpec l) {
            return new Condition(Kind.ELEMENT_VISIBLE, null, false, l, null, null, null);
        }
        public static Condition allOf(List<Condition> cs) {
            return new Condition(Kind.ALL_OF, null, false, null, null, cs, null);
        }
        public static Condition anyOf(List<Condition> cs) {
            return new Condition(Kind.ANY_OF, null, false, null, null, null, cs);
        }
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * A legitimate, caller-relevant result that is NOT a failure. The single most common design
     * mistake in this space is conflating "no such member" with a crash - this type prevents that.
     */
    public record KnownOutcome(
            String code,               // stable, e.g. "MEMBER_NOT_FOUND"
            OutcomeClass outcomeClass,
            Condition detect,
            boolean terminal,          // if true, replay stops here and reports this outcome
            String message
    ) {}

    public enum OutcomeClass {
        /** The caller asked a question and this is a valid answer. */
        BUSINESS,
        /** The environment refused (permission, policy) - caller may need to route elsewhere. */
        DENIED
    }

    /** A recoverable condition replay handles autonomously (bounded), without escalating. */
    public record Recovery(
            String code,               // e.g. "TRANSIENT_LOAD", "KNOWN_INTERSTITIAL"
            Condition detect,
            Strategy strategy,
            int maxAttempts,
            long backoffMs,
            Step dismissAction         // for DISMISS: the action to take (usually a CLICK)
    ) {
        public enum Strategy { WAIT_RETRY, DISMISS, RELOAD }
    }

    // ---------------------------------------------------------------------------------------------

    /** A value bound at invocation time. Exactly one of the fields is set. */
    public record ValueSpec(
            String literal,            // constant captured at record time (non-sensitive only)
            String paramRef,           // name of a ParamSpec
            String secretRef           // logical secret name, resolved from a local secret store
    ) {
        public static ValueSpec literal(String v) { return new ValueSpec(v, null, null); }
        public static ValueSpec param(String name) { return new ValueSpec(null, name, null); }
        public static ValueSpec secret(String name) { return new ValueSpec(null, null, name); }
    }

    public record Redaction(
            List<String> secretsNeverLogged,   // logical secret names
            List<String> sensitiveParams,      // ParamSpec names
            List<String> patterns              // extra regexes masked in evidence + serialized artifact
    ) {
        public static Redaction defaults() {
            return new Redaction(
                    List.of("credentials.password"),
                    List.of(),
                    List.of(
                            "\\b\\d{3}-\\d{2}-\\d{4}\\b",              // SSN
                            "\\b(?:\\d[ -]*?){13,16}\\b",              // PAN
                            "(?i)bearer\\s+[a-z0-9._\\-]+",            // bearer token
                            "sk-ant-[a-zA-Z0-9_\\-]+"                  // Anthropic key
                    ));
        }
    }

    public record BindingContext(Map<String, String> params, Map<String, String> secrets, String tenantBaseUrl) {}
}
