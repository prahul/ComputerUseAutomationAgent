package com.example.cua;

import com.example.cua.artifact.Artifact;
import com.example.cua.artifact.Artifact.*;
import com.example.cua.core.Json;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ArtifactSchemaTest {

    @Test
    void roundTripsThroughJsonWithUnionsAndNestedConditions() {
        LocatorSpec loc = new LocatorSpec(
                LocatorStrategy.roleName("textbox", "Member ID"),
                List.of(LocatorStrategy.label("Member ID"), LocatorStrategy.bbox(0.3, 0.2)),
                "role+name is stable");

        Artifact original = new Artifact(
                Artifact.SCHEMA_VERSION,
                new Meta("id-1", "lookup_member", "1.2.0", "Lookup Member", "desc",
                        "run-1", Instant.parse("2026-01-01T00:00:00Z"), "discovery-agent",
                        ApprovalState.DRAFT, 0.9),
                new SurfaceSpec("web", "playwright-chromium", 1280, 900),
                new TargetSpec("app", "acme-servicing", "${tenant.baseUrl}/", "default"),
                List.of(new ParamSpec("memberId", ValueType.STRING, true, false, "the id", "10001", "^[0-9]{5}$", null)),
                List.of(new OutputSpec("savingsBalance", ValueType.MONEY, "balance",
                        new OutputSource("s5", "money", "([0-9.]+)"))),
                List.of(
                        new Step("s0", ActionKind.NAVIGATE, "open", null, null, "${tenant.baseUrl}/", null, null,
                                new Checkpoint(Condition.urlMatches("/login"), 10000, "on login"),
                                RiskClass.SAFE, StepPolicy.ALLOW),
                        new Step("s1", ActionKind.TYPE, "enter id", loc, ValueSpec.param("memberId"), null, null, null,
                                new Checkpoint(Condition.elementVisible(loc), 5000, "field present"),
                                RiskClass.SAFE, StepPolicy.ALLOW),
                        new Step("s2", ActionKind.CLICK, "submit", loc, null, null, null, null,
                                new Checkpoint(Condition.allOf(List.of(
                                        Condition.textPresent("Savings"),
                                        Condition.textAbsent("error"))), 10000, "results"),
                                RiskClass.IRREVERSIBLE, StepPolicy.REQUIRE_APPROVAL)),
                Condition.textPresent("Savings balance"),
                List.of(new KnownOutcome("MEMBER_NOT_FOUND", OutcomeClass.BUSINESS,
                        Condition.textPresent("No member found"), true, "no such member")),
                List.of(new Recovery("TRANSIENT", Condition.textPresent("Loading"),
                        Recovery.Strategy.WAIT_RETRY, 3, 1000, null)),
                Redaction.defaults());

        String json = Json.write(original);
        Artifact back = Json.read(json, Artifact.class);

        assertEquals(original, back, "artifact should survive a JSON round-trip unchanged");
        assertEquals("memberId", back.step("s1").value().paramRef());
        assertEquals(Condition.Kind.ALL_OF, back.step("s2").checkpoint().condition().kind());
        assertEquals(StepPolicy.REQUIRE_APPROVAL, back.step("s2").policy());
        assertEquals(ValueType.MONEY, back.outputs().get(0).type());
    }

    @Test
    void valueSpecIsATaggedUnion() {
        assertEquals("x", ValueSpec.literal("x").literal());
        assertNull(ValueSpec.literal("x").paramRef());
        assertEquals("credentials.password", ValueSpec.secret("credentials.password").secretRef());
    }
}
