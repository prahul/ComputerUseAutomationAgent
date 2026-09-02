package com.example.cua.surface;

import java.util.List;
import java.util.Optional;

/**
 * The perceive/act seam. Everything above this interface (the agent loop, the artifact schema,
 * the replay engine) is surface-agnostic; everything below it is surface-specific.
 *
 * <p>A {@code Surface} exposes a normalized element model (the {@link Observation}) and a small,
 * semantic {@link Action} vocabulary. A web adapter, a legacy-frameset adapter, or a desktop
 * accessibility-API adapter all implement the same contract and produce the same types, so a
 * recorded flow does not encode "how we talk to Chromium" - only "what an operator does".
 */
public interface Surface {

    /** Snapshot the current UI state as a normalized, addressable element tree. */
    Observation observe();

    /** Execute one semantic action. Implementations resolve targets against the latest observation. */
    ActionResult act(Action action);

    /** Raw evidence signal for a run (PNG bytes for web; platform-specific elsewhere). */
    byte[] screenshot();

    /** A serializable dump of the underlying surface for failure diagnostics (DOM HTML for web). */
    String rawSnapshot();

    /** Current location identifier (URL for web). */
    String location();

    /** Resolve a locator spec to a concrete element in the current DOM, if present and visible. */
    Optional<UiElement> resolve(com.example.cua.artifact.Artifact.LocatorSpec spec);

    /** Report whether a locator resolves and, if so, which strategy in the chain matched. */
    ProbeResult probe(com.example.cua.artifact.Artifact.LocatorSpec spec);

    record ProbeResult(boolean found, com.example.cua.artifact.Artifact.LocatorStrategy.Kind strategyUsed) {
        public static ProbeResult miss() { return new ProbeResult(false, null); }
    }

    void close();

    /** A normalized snapshot of what a human operator would see. */
    record Observation(
            String location,
            String title,
            List<UiElement> elements,
            /** Flattened, deduplicated visible text - the cheap signal for detectors and the LLM. */
            String textDigest,
            int viewportWidth,
            int viewportHeight
    ) {}

    /**
     * One interactive or informative node. {@code ref} is stable only within a single observation;
     * the durable identity lives in {@code locatorCandidates}, which the recorder copies into the artifact.
     */
    record UiElement(
            String ref,
            String role,
            String name,
            String value,
            String text,
            boolean enabled,
            boolean editable,
            Rect bounds,
            List<com.example.cua.artifact.Artifact.LocatorStrategy> locatorCandidates
    ) {}

    record Rect(double x, double y, double width, double height) {
        public double centerX() { return x + width / 2; }
        public double centerY() { return y + height / 2; }
    }

    enum ActionType { NAVIGATE, CLICK, TYPE, PRESS_KEY, SELECT_OPTION, WAIT, SCROLL_TO, EXTRACT, ACCEPT_DIALOG, DISMISS_DIALOG }

    /**
     * A semantic action. {@code targetRef} points at a {@link UiElement#ref} from the latest
     * observation (discovery time); {@code locator} carries the durable target (replay time).
     */
    record Action(
            ActionType type,
            String targetRef,
            com.example.cua.artifact.Artifact.LocatorSpec locator,
            String value,
            String url,
            String key,
            Long waitMs
    ) {
        public static Action navigate(String url) { return new Action(ActionType.NAVIGATE, null, null, null, url, null, null); }
        public static Action click(String ref) { return new Action(ActionType.CLICK, ref, null, null, null, null, null); }
        public static Action type(String ref, String value) { return new Action(ActionType.TYPE, ref, null, value, null, null, null); }
        public static Action selectOption(String ref, String value) { return new Action(ActionType.SELECT_OPTION, ref, null, value, null, null, null); }
        public static Action pressKey(String key) { return new Action(ActionType.PRESS_KEY, null, null, null, null, key, null); }
        public static Action waitFor(long ms) { return new Action(ActionType.WAIT, null, null, null, null, null, ms); }
        public static Action extract(String ref) { return new Action(ActionType.EXTRACT, ref, null, null, null, null, null); }

        public Action withLocator(com.example.cua.artifact.Artifact.LocatorSpec l) {
            return new Action(type, targetRef, l, value, url, key, waitMs);
        }
    }

    record ActionResult(boolean ok, String detail, String extractedText, UiElement resolvedElement) {
        public static ActionResult ok(String detail) { return new ActionResult(true, detail, null, null); }
        public static ActionResult ok(String detail, UiElement el) { return new ActionResult(true, detail, null, el); }
        public static ActionResult extracted(String text, UiElement el) { return new ActionResult(true, "extracted", text, el); }
        public static ActionResult fail(String detail) { return new ActionResult(false, detail, null, null); }
    }
}
