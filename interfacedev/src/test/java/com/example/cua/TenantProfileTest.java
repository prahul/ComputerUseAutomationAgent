package com.example.cua;

import com.example.cua.artifact.Artifact;
import com.example.cua.artifact.Artifact.LocatorSpec;
import com.example.cua.artifact.Artifact.LocatorStrategy;
import com.example.cua.tenant.TenantProfile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TenantProfileTest {

    private Artifact.Step step(String id, LocatorSpec target) {
        return new Artifact.Step(id, Artifact.ActionKind.TYPE, "enter", target, null, null, null, null, null,
                Artifact.RiskClass.SAFE, Artifact.StepPolicy.ALLOW);
    }

    @Test
    void appliesPerStepLocatorOverridesElseFallsBackToTheArtifact() {
        LocatorSpec base = new LocatorSpec(LocatorStrategy.roleName("textbox", "Member ID"), List.of(), "base");
        LocatorSpec override = new LocatorSpec(LocatorStrategy.roleName("textbox", "Customer ID"), List.of(), "tenant");

        TenantProfile profile = new TenantProfile("altcu", "acme-servicing",
                "http://localhost:8080/altcu", Map.of(),
                Map.of("s4", override), Map.of(), List.of());

        assertEquals("Customer ID", profile.locatorFor(step("s4", base)).primary().name());
        assertEquals("Member ID", profile.locatorFor(step("s1", base)).primary().name());
    }

    @Test
    void substitutesTheTenantBaseUrl() {
        TenantProfile profile = new TenantProfile("altcu", "acme-servicing",
                "http://localhost:8080/altcu", Map.of(), Map.of(), Map.of(), List.of());
        assertEquals("http://localhost:8080/altcu/dashboard",
                profile.resolveUrl("${tenant.baseUrl}/dashboard"));
    }

    @Test
    void checkedInAltcuProfileParses() {
        java.nio.file.Path f = java.nio.file.Path.of("config/tenants/altcu.json");
        if (!java.nio.file.Files.exists(f)) return; // optional config
        TenantProfile p = TenantProfile.load(f);
        assertEquals("acme-servicing", p.vendorProduct());
        assertTrue(p.stepLocatorOverrides().containsKey("s4"));
    }
}
