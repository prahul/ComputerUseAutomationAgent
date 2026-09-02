package com.example.cua.replay;

import com.example.cua.artifact.Artifact.Condition;
import com.example.cua.surface.Surface;

import java.util.regex.Pattern;

/** Evaluates artifact {@link Condition}s against the live surface. No LLM involved. */
public final class DetectorEngine {
    private final Surface surface;

    public DetectorEngine(Surface surface) {
        this.surface = surface;
    }

    public boolean matches(Condition c) {
        if (c == null) return true;
        Surface.Observation obs = surface.observe();
        return eval(c, obs);
    }

    public boolean matches(Condition c, Surface.Observation obs) {
        return c == null || eval(c, obs);
    }

    /** Poll a condition until it holds or the timeout elapses. */
    public boolean waitFor(Condition c, long timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(timeoutMs, 200);
        RuntimeException last = null;
        do {
            try {
                if (matches(c)) return true;
            } catch (RuntimeException e) {
                last = e;
            }
            sleep(250);
        } while (System.currentTimeMillis() < deadline);
        return false;
    }

    private boolean eval(Condition c, Surface.Observation obs) {
        return switch (c.kind()) {
            case TEXT_PRESENT -> textContains(obs, c);
            case TEXT_ABSENT -> !textContains(obs, c);
            case URL_MATCHES -> c.regex()
                    ? Pattern.compile(c.text()).matcher(obs.location()).find()
                    : obs.location().contains(c.text());
            case ELEMENT_VISIBLE -> surface.resolve(c.target()).isPresent();
            case ELEMENT_ENABLED -> surface.resolve(c.target()).map(Surface.UiElement::enabled).orElse(false);
            case VALUE_EQUALS -> surface.resolve(c.target())
                    .map(e -> normalize(e.value()).equals(normalize(c.expectedValue())))
                    .orElse(false);
            case ALL_OF -> c.allOf() != null && c.allOf().stream().allMatch(sub -> eval(sub, obs));
            case ANY_OF -> c.anyOf() != null && c.anyOf().stream().anyMatch(sub -> eval(sub, obs));
        };
    }

    private boolean textContains(Surface.Observation obs, Condition c) {
        String hay = obs.textDigest();
        if (c.regex()) return Pattern.compile(c.text(), Pattern.CASE_INSENSITIVE).matcher(hay).find();
        return hay.toLowerCase().contains(c.text().toLowerCase());
    }

    private static String normalize(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
