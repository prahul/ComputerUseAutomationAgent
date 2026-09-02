package com.example.cua.escalation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

/**
 * Human-in-the-loop escalation and control transfer.
 *
 * <p>The model here is a single live session with one <b>control owner</b> at a time. When the
 * automation cannot safely proceed it files an {@link InterventionRequest} and blocks on the
 * {@link SessionController}. An operator (via the mock console) claims the request, drives the very
 * same browser session - either through the console's action relay or the headed browser window
 * directly - records what they did, and signals resume, which hands control back and unblocks the
 * automation exactly where it stopped.
 */
public final class Escalation {

    public enum ControlOwner { AUTOMATION, OPERATOR }

    public enum Trigger { AGENT_STUCK, MAX_STEPS, POLICY_APPROVAL, REPLAY_HARD_FAILURE, UNKNOWN_STATE }

    public enum Resolution { RESUME, RESUME_SKIP_STEP, ABORT }

    public record InterventionRequest(
            String id,
            String runId,
            String capabilityName,
            Trigger trigger,
            String currentStepId,
            String reason,
            String question,          // what the automation needs answered, if anything
            String location,
            String screenshotFile,    // path under the evidence dir
            Instant openedAt,
            Instant closedAt,
            String resolvedBy,
            Resolution resolution,
            List<HumanAction> humanActions
    ) {
        public boolean open() { return closedAt == null; }
    }

    public record HumanAction(Instant at, String kind, String detail) {}

    /** The outcome handed back to the blocked automation thread. */
    public record HandoffResult(Resolution resolution, String operator, List<HumanAction> actions, String note) {}

    /**
     * Owns control state for one session and the rendezvous between the blocked automation thread
     * and the operator console thread.
     */
    public static final class SessionController {
        private volatile ControlOwner owner = ControlOwner.AUTOMATION;
        private final SynchronousQueue<HandoffResult> handback = new SynchronousQueue<>();
        private final List<HumanAction> pendingActions = new CopyOnWriteArrayList<>();
        private volatile InterventionRequest active;

        public ControlOwner owner() { return owner; }
        public Optional<InterventionRequest> active() { return Optional.ofNullable(active); }

        /** Called by the automation thread. Blocks until an operator resumes or aborts. */
        public HandoffResult cedeControlAndWait(InterventionRequest request) {
            this.active = request;
            this.pendingActions.clear();
            this.owner = ControlOwner.OPERATOR;
            try {
                HandoffResult r = handback.take();
                this.owner = ControlOwner.AUTOMATION;
                this.active = null;
                return r;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                this.owner = ControlOwner.AUTOMATION;
                return new HandoffResult(Resolution.ABORT, "system", List.of(), "interrupted");
            }
        }

        /** Called by the operator console when the human performs a manual step. */
        public void recordHumanAction(String kind, String detail) {
            pendingActions.add(new HumanAction(Instant.now(), kind, detail));
        }

        public List<HumanAction> humanActions() { return new ArrayList<>(pendingActions); }

        /** Called by the operator console to hand control back. Unblocks the automation thread. */
        public boolean resume(String operator, Resolution resolution, String note) {
            if (owner != ControlOwner.OPERATOR) return false;
            HandoffResult r = new HandoffResult(resolution, operator, new ArrayList<>(pendingActions), note);
            try {
                return handback.offer(r, 5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /**
     * Routes intervention requests and keeps their history. In a real system this is a queue with
     * assignment, SLAs and notifications; here it is an in-memory list the mock console reads.
     */
    public static final class EscalationBroker {
        private final Map<String, InterventionRequest> byId = new ConcurrentHashMap<>();
        private final List<String> order = new CopyOnWriteArrayList<>();
        private final SessionController controller;
        private volatile java.util.function.Consumer<InterventionRequest> onOpen = r -> {};
        private volatile long autoMillis = 0;
        private volatile Resolution autoResolution = Resolution.RESUME;
        private volatile String autoNote = "auto-operator: no human claimed the intervention";

        public EscalationBroker(SessionController controller) {
            this.controller = controller;
        }

        /**
         * Configure an unattended fallback: if no human resumes within {@code millis}, the broker
         * itself hands control back with {@code resolution}. Set {@code millis <= 0} to require a
         * real operator (the default).
         */
        public void autoRespondAfter(long millis, Resolution resolution, String note) {
            this.autoMillis = millis;
            this.autoResolution = resolution;
            if (note != null) this.autoNote = note;
        }

        public SessionController controller() { return controller; }

        public void onOpen(java.util.function.Consumer<InterventionRequest> listener) {
            this.onOpen = listener;
        }

        /**
         * File a request and block the calling (automation) thread until an operator resumes.
         * Returns the resolution + the recorded human actions.
         */
        public HandoffResult raiseAndWait(InterventionRequest request) {
            byId.put(request.id(), request);
            order.add(request.id());
            onOpen.accept(request);
            if (autoMillis > 0) {
                Thread t = new Thread(() -> {
                    try {
                        Thread.sleep(autoMillis);
                        controller.resume("auto-operator", autoResolution, autoNote);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }, "auto-operator-" + request.id());
                t.setDaemon(true);
                t.start();
            }
            HandoffResult result = controller.cedeControlAndWait(request);
            InterventionRequest closed = new InterventionRequest(
                    request.id(), request.runId(), request.capabilityName(), request.trigger(),
                    request.currentStepId(), request.reason(), request.question(), request.location(),
                    request.screenshotFile(), request.openedAt(), Instant.now(), result.operator(),
                    result.resolution(), result.actions());
            byId.put(request.id(), closed);
            return result;
        }

        public List<InterventionRequest> all() {
            return order.stream().map(byId::get).toList();
        }

        public Optional<InterventionRequest> get(String id) {
            return Optional.ofNullable(byId.get(id));
        }

        public List<InterventionRequest> open() {
            return all().stream().filter(InterventionRequest::open).toList();
        }
    }

    public record RunContext(String runId, String capabilityName) {}
}
