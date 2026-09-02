package com.example.cua.replay;

import com.example.cua.artifact.Artifact;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The structured result contract for a replay. It deliberately separates three outcomes the caller
 * must treat differently:
 * <ul>
 *   <li>{@link Status#SUCCESS} - the flow completed and the success condition verified; {@code outputs} carry data.</li>
 *   <li>{@link Status#BUSINESS_OUTCOME} - a legitimate answer the caller needs ("no such member",
 *       "permission denied", "validation rejected"). Not a crash. {@code outcomeCode}/{@code outcomeMessage} say which.</li>
 *   <li>{@link Status#FAILED} - a hard failure with enough detail to debug: which step, what was
 *       expected, what was observed, and a pointer to the richer evidence.</li>
 * </ul>
 */
public record ReplayResult(
        Status status,
        String outcomeCode,
        Artifact.OutcomeClass outcomeClass,
        String outcomeMessage,
        Map<String, Object> outputs,
        Failure failure,
        List<StepResult> steps,
        List<DriftSignal> driftSignals,
        String runId,
        String artifactName,
        String artifactVersion,
        String tenantId,
        Instant startedAt,
        Instant finishedAt
) {
    public enum Status { SUCCESS, BUSINESS_OUTCOME, FAILED }

    public record Failure(String stepId, String phase, String expected, String observed, String evidenceRef) {}

    public record StepResult(
            String stepId,
            Artifact.ActionKind action,
            String status,                 // OK | RECOVERED | BUSINESS_OUTCOME | FAILED | SKIPPED
            String locatorStrategyUsed,    // which strategy in the chain resolved (null if n/a)
            boolean usedFallback,
            List<String> recoveriesApplied,
            long elapsedMs,
            String detail
    ) {}

    public record DriftSignal(String stepId, String expectedStrategy, String actualStrategy, String note) {}

    public boolean ok() { return status == Status.SUCCESS; }
}
