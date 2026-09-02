package com.example.cua;

import com.example.cua.artifact.Artifact;
import com.example.cua.artifact.Artifact.LocatorStrategy;
import com.example.cua.discovery.LocatorBuilder;
import com.example.cua.policy.Redactor;
import com.example.cua.surface.Surface;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RedactorAndLocatorTest {

    @Test
    void redactorMasksKnownValuesAndPatterns() {
        Redactor r = new Redactor(Artifact.Redaction.defaults().patterns())
                .withValue("credentials.password", "hunter2xyz");
        String scrubbed = r.scrub("logged in as user with password hunter2xyz; ssn 123-45-6789; key sk-ant-abc123DEF");
        assertFalse(scrubbed.contains("hunter2xyz"));
        assertFalse(scrubbed.contains("123-45-6789"));
        assertFalse(scrubbed.contains("sk-ant-abc123DEF"));
        assertTrue(scrubbed.contains("logged in as user"));
    }

    @Test
    void locatorBuilderOrdersMostDurableFirstAndAlwaysHasABboxTail() {
        Surface.UiElement el = new Surface.UiElement("e1", "button", "Search", "", "Search", true, false,
                new Surface.Rect(100, 50, 80, 30),
                List.of(
                        LocatorStrategy.css("form > button"),
                        LocatorStrategy.bbox(0.1, 0.05),
                        LocatorStrategy.roleName("button", "Search"),
                        LocatorStrategy.text("Search")));

        Artifact.LocatorSpec spec = LocatorBuilder.forElement(el);
        assertEquals(LocatorStrategy.Kind.ROLE_NAME, spec.primary().kind(), "role+name is the most durable");
        assertEquals(LocatorStrategy.Kind.BBOX,
                spec.fallbacks().get(spec.fallbacks().size() - 1).kind(), "bbox is always the last resort");
        assertFalse(spec.rationale().isBlank());
    }

    @Test
    void locatorBuilderFallsBackToBboxWhenNoCandidates() {
        Surface.UiElement el = new Surface.UiElement("e1", "", "", "", "", true, false,
                new Surface.Rect(0, 0, 0, 0), List.of());
        Artifact.LocatorSpec spec = LocatorBuilder.forElement(el);
        assertEquals(LocatorStrategy.Kind.BBOX, spec.primary().kind());
    }
}
