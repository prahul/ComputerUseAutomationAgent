package com.example.cua.server;

import com.example.cua.escalation.Escalation;
import com.example.cua.evidence.EvidenceRecorder;
import com.example.cua.surface.Surface;

/** The in-process handle the operator console uses to drive the same live session as the automation. */
public record LiveSession(
        String runId,
        Surface surface,
        EvidenceRecorder evidence,
        Escalation.EscalationBroker broker
) {
    public Escalation.SessionController controller() { return broker.controller(); }
}
