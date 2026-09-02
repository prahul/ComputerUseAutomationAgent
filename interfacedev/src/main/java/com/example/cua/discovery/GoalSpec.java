package com.example.cua.discovery;

import com.example.cua.artifact.Artifact;

import java.util.List;
import java.util.Map;

/**
 * The input to a discovery run: a natural-language goal, the entry point, and the typed parameters
 * the resulting capability should expose (with concrete values used for this one discovery run).
 */
public record GoalSpec(
        String goal,
        String entryUrl,
        String capabilityName,
        String vendorProduct,
        List<Artifact.ParamSpec> parameters,
        /** concrete values for this discovery run, keyed by param name */
        Map<String, String> paramValues,
        Map<String, String> secrets
) {
    public String resolvedGoal() {
        String g = goal;
        for (var e : paramValues.entrySet()) {
            g = g.replace("{" + e.getKey() + "}", e.getValue());
        }
        return g;
    }
}
