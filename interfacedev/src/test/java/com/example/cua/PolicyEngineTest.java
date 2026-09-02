package com.example.cua;

import com.example.cua.artifact.Artifact;
import com.example.cua.policy.PolicyEngine;
import com.example.cua.surface.Surface;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PolicyEngineTest {

    private PolicyEngine engine(String writePolicy) {
        return new PolicyEngine(new PolicyEngine.Allowlist("test",
                List.of("http://localhost:8080"),
                List.of("^http://localhost:8080(/.*)?$"),
                List.of("NAVIGATE", "CLICK", "TYPE", "EXTRACT"),
                writePolicy, 25, 300_000, false));
    }

    @Test
    void blocksNavigationOutsideTheAllowlist() {
        var d = engine("REQUIRE_APPROVAL").check(
                Surface.Action.navigate("https://evil.example.com/steal"), Artifact.RiskClass.SAFE, null, false);
        assertEquals(PolicyEngine.Effect.BLOCK, d.effect());
    }

    @Test
    void allowsNavigationInsideTheAllowlist() {
        var d = engine("REQUIRE_APPROVAL").check(
                Surface.Action.navigate("http://localhost:8080/dashboard"), Artifact.RiskClass.SAFE, null, false);
        assertTrue(d.proceed());
    }

    @Test
    void blocksActionTypesNotOnTheList() {
        var d = engine("REQUIRE_APPROVAL").check(
                Surface.Action.pressKey("Enter"), Artifact.RiskClass.SAFE, null, false);
        assertEquals(PolicyEngine.Effect.BLOCK, d.effect());
    }

    @Test
    void irreversibleActionsFollowTheWritePolicy() {
        Surface.Action click = new Surface.Action(Surface.ActionType.CLICK, null, null, null, null, null, null);
        assertEquals(PolicyEngine.Effect.REQUIRE_APPROVAL,
                engine("REQUIRE_APPROVAL").check(click, Artifact.RiskClass.IRREVERSIBLE, null, false).effect());
        assertEquals(PolicyEngine.Effect.BLOCK,
                engine("BLOCK").check(click, Artifact.RiskClass.IRREVERSIBLE, null, false).effect());
        assertTrue(engine("ALLOW").check(click, Artifact.RiskClass.IRREVERSIBLE, null, false).proceed());
    }

    @Test
    void preApprovalBypassesTheGate() {
        Surface.Action click = new Surface.Action(Surface.ActionType.CLICK, null, null, null, null, null, null);
        assertTrue(engine("REQUIRE_APPROVAL").check(click, Artifact.RiskClass.IRREVERSIBLE, null, true).proceed());
    }

    @Test
    void stepPolicyBlockAlwaysWins() {
        Surface.Action click = new Surface.Action(Surface.ActionType.CLICK, null, null, null, null, null, null);
        assertEquals(PolicyEngine.Effect.BLOCK,
                engine("ALLOW").check(click, Artifact.RiskClass.SAFE, Artifact.StepPolicy.BLOCK, false).effect());
    }
}
