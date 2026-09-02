package com.example.cua.discovery;

import com.example.cua.artifact.Artifact.LocatorSpec;
import com.example.cua.artifact.Artifact.LocatorStrategy;
import com.example.cua.surface.Surface;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a perceived element into a durable {@link LocatorSpec}: an ordered chain of targeting
 * strategies, most-robust first, with a human-readable rationale for the ordering.
 *
 * <p>Ordering policy (why replay stays stable on a slow-changing enterprise UI):
 * <ol>
 *   <li><b>role + accessible name</b> - survives markup/styling churn; a "Search" button stays a
 *       button named "Search".</li>
 *   <li><b>label / placeholder</b> - form fields are identified the way an operator identifies them.</li>
 *   <li><b>exact visible text</b> - good for links and static controls.</li>
 *   <li><b>test id / id / name attribute</b> - precise when present, but legacy apps rarely have
 *       stable ones, so it is not the primary.</li>
 *   <li><b>css path</b> - structural, brittle to redesigns; a fallback only.</li>
 *   <li><b>normalized bounding-box click</b> - last resort, works even with no usable DOM at all
 *       (the case a screenshot-only surface would always be in).</li>
 * </ol>
 */
public final class LocatorBuilder {

    private LocatorBuilder() {}

    public static LocatorSpec forElement(Surface.UiElement el) {
        List<LocatorStrategy> ordered = new ArrayList<>(el.locatorCandidates());
        ordered.sort((a, b) -> Integer.compare(rank(a.kind()), rank(b.kind())));
        if (ordered.isEmpty()) {
            ordered.add(LocatorStrategy.bbox(0.5, 0.5));
        }
        LocatorStrategy primary = ordered.get(0);
        List<LocatorStrategy> fallbacks = ordered.size() > 1 ? ordered.subList(1, ordered.size()) : List.of();
        return new LocatorSpec(primary, new ArrayList<>(fallbacks), rationale(el, primary, fallbacks));
    }

    private static int rank(LocatorStrategy.Kind kind) {
        return switch (kind) {
            case ROLE_NAME -> 0;
            case LABEL -> 1;
            case PLACEHOLDER -> 2;
            case TEXT -> 3;
            case TEST_ID -> 4;
            case ANCHORED -> 5;
            case CSS -> 6;
            case XPATH -> 7;
            case BBOX -> 9;
        };
    }

    private static String rationale(Surface.UiElement el, LocatorStrategy primary, List<LocatorStrategy> fallbacks) {
        String label = !el.name().isBlank() ? el.name() : el.text();
        StringBuilder sb = new StringBuilder();
        sb.append("Primary: ").append(describe(primary)).append(". ");
        switch (primary.kind()) {
            case ROLE_NAME -> sb.append("The control's role and accessible name (\"").append(trim(label))
                    .append("\") are functional and unlikely to change in a slow-moving back-office UI. ");
            case LABEL, PLACEHOLDER -> sb.append("Form fields are keyed by their operator-visible label, which is stable. ");
            case TEXT -> sb.append("Exact visible text is stable for this static control. ");
            case TEST_ID -> sb.append("A stable id/name attribute is present on this element. ");
            case CSS -> sb.append("No role/name/label was available; falling back to a structural path. ");
            default -> sb.append("Weak primary; relying on fallbacks. ");
        }
        if (!fallbacks.isEmpty()) {
            sb.append("Fallbacks in order: ");
            for (int i = 0; i < fallbacks.size(); i++) {
                sb.append(describe(fallbacks.get(i)));
                if (i < fallbacks.size() - 1) sb.append(" -> ");
            }
            sb.append(". The bounding-box fallback keeps replay working even with no usable DOM.");
        }
        return sb.toString();
    }

    private static String describe(LocatorStrategy s) {
        return switch (s.kind()) {
            case ROLE_NAME -> "role=" + s.role() + "+name";
            case LABEL -> "label";
            case PLACEHOLDER -> "placeholder";
            case TEXT -> "text";
            case TEST_ID -> "id/name attr";
            case ANCHORED -> "anchored-to-neighbour";
            case CSS -> "css path";
            case XPATH -> "xpath";
            case BBOX -> "normalized bbox click";
        };
    }

    private static String trim(String s) {
        s = s == null ? "" : s.replaceAll("\\s+", " ").trim();
        return s.length() <= 40 ? s : s.substring(0, 40);
    }
}
