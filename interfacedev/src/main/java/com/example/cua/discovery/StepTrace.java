package com.example.cua.discovery;

import com.example.cua.artifact.Artifact;
import com.example.cua.surface.Surface;

/** One executed action during a discovery run, plus the context the ArtifactBuilder needs. */
public record StepTrace(
        String id,
        Surface.Action action,
        Artifact.LocatorSpec locator,      // durable target computed from the resolved element
        String modelIntent,                // the agent's stated reason for this step
        String modelTargetRationale,       // the agent's stated reason this target is robust
        String observedTextBefore,         // text digest before the action
        String observedTextAfter,          // text digest after the action (for checkpoint synthesis)
        String locationBefore,
        String locationAfter,
        String extractedText,              // for EXTRACT steps
        String outputName,
        Artifact.ValueType outputType,
        Artifact.ValueSpec boundValue,     // resolved value binding for TYPE/SELECT steps
        Artifact.RiskClass risk
) {}
