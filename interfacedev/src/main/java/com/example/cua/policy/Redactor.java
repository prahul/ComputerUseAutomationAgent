package com.example.cua.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Redacts regulated data before anything is persisted (artifacts, evidence, logs, the operator
 * console payload). Two layers:
 * <ol>
 *   <li><b>Known values.</b> Concrete secret / sensitive-parameter values registered for this run
 *       are replaced with a stable token, so a member's SSN typed during discovery never lands in
 *       the transcript.</li>
 *   <li><b>Pattern sweep.</b> A configurable regex list masks anything that looks like an SSN, PAN,
 *       bearer token or API key even if we did not know the value up front.</li>
 * </ol>
 */
public final class Redactor {
    private final List<Map.Entry<String, String>> knownValues = new ArrayList<>();
    private final List<Pattern> patterns = new ArrayList<>();

    public Redactor(List<String> patternStrings) {
        for (String p : patternStrings) {
            try { patterns.add(Pattern.compile(p)); } catch (RuntimeException ignored) {}
        }
    }

    /** Register a concrete value to always mask, e.g. a password or a member SSN. */
    public Redactor withValue(String label, String value) {
        if (value != null && value.length() >= 3) {
            knownValues.add(Map.entry(value, "‹" + label + ":redacted›"));
        }
        return this;
    }

    public String scrub(String input) {
        if (input == null) return null;
        String s = input;
        for (var e : knownValues) {
            s = s.replace(e.getKey(), e.getValue());
        }
        for (Pattern p : patterns) {
            s = p.matcher(s).replaceAll("‹redacted›");
        }
        return s;
    }

    public boolean masksAnythingIn(String input) {
        return input != null && !input.equals(scrub(input));
    }
}
