package com.example.cua;

import com.example.cua.artifact.Artifact.Condition;
import com.example.cua.artifact.Artifact.LocatorSpec;
import com.example.cua.artifact.Artifact.LocatorStrategy;
import com.example.cua.replay.DetectorEngine;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DetectorEngineTest {

    private final FakeSurface surface = new FakeSurface(
            "http://localhost:8080/dashboard?id=10001", "Dashboard",
            "Riverbend Credit Union Member Servicing Savings balance $4210.55 ACTIVE", List.of())
            .present(LocatorStrategy.Kind.ROLE_NAME);

    private final DetectorEngine detectors = new DetectorEngine(surface);

    @Test
    void textPresentAndAbsentAreCaseInsensitive() {
        assertTrue(detectors.matches(Condition.textPresent("savings BALANCE")));
        assertTrue(detectors.matches(Condition.textAbsent("no member found")));
        assertFalse(detectors.matches(Condition.textPresent("permission denied")));
    }

    @Test
    void urlMatchesIsSubstring() {
        assertTrue(detectors.matches(Condition.urlMatches("/dashboard")));
        assertFalse(detectors.matches(Condition.urlMatches("/sub-account")));
    }

    @Test
    void elementVisibleUsesTheLocatorChain() {
        LocatorSpec present = new LocatorSpec(LocatorStrategy.roleName("button", "Search"), List.of(), "");
        LocatorSpec absent = new LocatorSpec(LocatorStrategy.css("#missing"), List.of(), "");
        assertTrue(detectors.matches(Condition.elementVisible(present)));
        assertFalse(detectors.matches(Condition.elementVisible(absent)));
    }

    @Test
    void composesAllOfAndAnyOf() {
        Condition ok = Condition.allOf(List.of(
                Condition.textPresent("Savings"),
                Condition.textAbsent("error")));
        Condition notOk = Condition.allOf(List.of(
                Condition.textPresent("Savings"),
                Condition.textPresent("error")));
        assertTrue(detectors.matches(ok));
        assertFalse(detectors.matches(notOk));
        assertTrue(detectors.matches(Condition.anyOf(List.of(
                Condition.textPresent("nope"), Condition.textPresent("ACTIVE")))));
    }
}
