package com.example.cua;

import com.example.cua.artifact.Artifact.LocatorSpec;
import com.example.cua.artifact.Artifact.LocatorStrategy;
import com.example.cua.surface.Surface;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** In-memory {@link Surface} for tests: a fixed observation + a list of "present" locator strategies. */
public final class FakeSurface implements Surface {
    private Observation observation;
    private final List<LocatorStrategy.Kind> presentKinds = new ArrayList<>();
    public final List<Action> acted = new ArrayList<>();

    public FakeSurface(String url, String title, String textDigest, List<UiElement> elements) {
        this.observation = new Observation(url, title, elements, textDigest, 1280, 900);
    }

    public FakeSurface present(LocatorStrategy.Kind... kinds) {
        for (var k : kinds) presentKinds.add(k);
        return this;
    }

    public void setObservation(Observation o) { this.observation = o; }

    @Override public Observation observe() { return observation; }

    @Override public ActionResult act(Action action) {
        acted.add(action);
        return ActionResult.ok("noted");
    }

    @Override public byte[] screenshot() { return new byte[0]; }
    @Override public String rawSnapshot() { return "<html></html>"; }
    @Override public String location() { return observation.location(); }

    @Override public Optional<UiElement> resolve(LocatorSpec spec) {
        return probe(spec).found()
                ? Optional.of(new UiElement("fake", "", "", "", "", true, false, new Rect(0, 0, 1, 1), List.of()))
                : Optional.empty();
    }

    @Override public ProbeResult probe(LocatorSpec spec) {
        List<LocatorStrategy> chain = new ArrayList<>();
        if (spec.primary() != null) chain.add(spec.primary());
        if (spec.fallbacks() != null) chain.addAll(spec.fallbacks());
        for (LocatorStrategy s : chain) {
            if (presentKinds.contains(s.kind())) return new ProbeResult(true, s.kind());
        }
        return ProbeResult.miss();
    }

    @Override public void close() {}
}
