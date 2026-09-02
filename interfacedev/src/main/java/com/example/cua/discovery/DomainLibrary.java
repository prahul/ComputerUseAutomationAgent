package com.example.cua.discovery;

import com.example.cua.artifact.Artifact.*;

import java.util.List;

/**
 * Known business outcomes and recovery rules for the {@code acme-servicing} vendor product.
 *
 * <p>These are intentionally curated per vendor product rather than inferred from a single happy-path
 * discovery run: the whole point of the assignment is that a replay must handle the exceptional
 * states that "legitimately occur at runtime" - and a happy-path run never sees them. In a real
 * system this library would be maintained alongside each vendor integration and versioned with it.
 */
public final class DomainLibrary {

    private DomainLibrary() {}

    public static List<KnownOutcome> knownOutcomes() {
        return List.of(
                new KnownOutcome("MEMBER_NOT_FOUND", OutcomeClass.BUSINESS,
                        anyText("No member found", "no such member", "No results"),
                        true, "No member matches the supplied ID."),
                new KnownOutcome("PERMISSION_DENIED", OutcomeClass.DENIED,
                        anyText("do not have permission", "not authorized", "access denied", "restricted"),
                        true, "The operator role cannot view this member."),
                new KnownOutcome("VALIDATION_REJECTED", OutcomeClass.BUSINESS,
                        anyText("must be", "is required", "invalid amount", "cannot be negative", "please correct"),
                        true, "The application rejected the submitted values."),
                new KnownOutcome("SESSION_EXPIRED", OutcomeClass.DENIED,
                        anyText("session has expired", "please sign in again", "session timeout"),
                        true, "The session expired mid-flow; a fresh login is required."));
    }

    public static List<Recovery> recoveries() {
        return List.of(
                new Recovery("TRANSIENT_LOAD",
                        anyText("Loading…", "Please wait", "One moment"),
                        Recovery.Strategy.WAIT_RETRY, 3, 1_000, null),
                new Recovery("KNOWN_INTERSTITIAL",
                        Condition.elementVisible(new LocatorSpec(
                                LocatorStrategy.roleName("button", "Continue"),
                                List.of(LocatorStrategy.roleName("button", "OK"),
                                        LocatorStrategy.text("Dismiss")),
                                "A known interstitial ('Continue'/'OK'/'Dismiss') that some tenants show after login.")),
                        Recovery.Strategy.DISMISS, 2, 500,
                        new Step("recovery-dismiss", ActionKind.CLICK, "dismiss known interstitial",
                                new LocatorSpec(LocatorStrategy.roleName("button", "Continue"),
                                        List.of(LocatorStrategy.roleName("button", "OK"), LocatorStrategy.text("Dismiss")),
                                        "same chain as the detector"),
                                null, null, null, null, null, RiskClass.SAFE, StepPolicy.ALLOW)));
    }

    private static Condition anyText(String... texts) {
        return Condition.anyOf(java.util.Arrays.stream(texts).map(Condition::textPresent).toList());
    }
}
