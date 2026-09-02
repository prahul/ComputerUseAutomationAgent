package com.example.cua.surface.web;

import com.example.cua.artifact.Artifact.LocatorSpec;
import com.example.cua.artifact.Artifact.LocatorStrategy;
import com.example.cua.core.CuaException;
import com.example.cua.surface.Surface;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.SelectOption;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Web implementation of {@link Surface}, backed by Playwright + Chromium.
 *
 * <p>Perception is deliberately not "read the clean DOM". We build a normalized element list from an
 * accessibility-flavoured DOM walk (role + accessible name + geometry), across every frame, and hand
 * the agent that list plus a screenshot. Targeting at replay time resolves an ordered strategy chain
 * (role+name, visible text, label, anchored, css, and finally a normalized bounding-box click) - the
 * same approach would degrade gracefully on a frameset with no test IDs.
 */
public final class WebSurface implements Surface {

    public record Config(boolean headed, int slowMoMs, int viewportWidth, int viewportHeight, int defaultTimeoutMs) {
        public static Config defaults() { return new Config(false, 0, 1280, 900, 8000); }
        public Config headed(boolean h) { return new Config(h, h ? 150 : slowMoMs, viewportWidth, viewportHeight, defaultTimeoutMs); }
    }

    /**
     * Playwright's Java objects are thread-confined. All Playwright access is marshalled onto this
     * single thread so the automation loop AND the operator console (an HTTP-executor thread) can
     * both drive the one live session - which is what "hand a human the same session" requires.
     */
    private final ExecutorService pw = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "playwright");
        t.setDaemon(true);
        return t;
    });

    private Playwright playwright;
    private Browser browser;
    private BrowserContext context;
    private Page page;
    private final Config config;
    private volatile String lastNativeDialogMessage;

    public WebSurface(Config config) {
        this.config = config;
        run(() -> {
            this.playwright = Playwright.create();
            this.browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(!config.headed())
                    .setSlowMo(config.slowMoMs()));
            this.context = browser.newContext(new Browser.NewContextOptions()
                    .setViewportSize(config.viewportWidth(), config.viewportHeight()));
            this.context.setDefaultTimeout(config.defaultTimeoutMs());
            this.page = context.newPage();
            this.page.onDialog(d -> {
                lastNativeDialogMessage = d.type() + ": " + d.message();
                d.dismiss();
            });
        });
    }

    private <T> T call(Supplier<T> op) {
        try {
            return pw.submit(op::get).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CuaException("interrupted during surface op");
        } catch (ExecutionException e) {
            Throwable c = e.getCause() == null ? e : e.getCause();
            if (c instanceof RuntimeException re) throw re;
            throw new CuaException("surface op failed: " + c.getMessage(), c);
        }
    }

    private void run(Runnable op) {
        call(() -> { op.run(); return null; });
    }

    public Optional<String> consumeNativeDialog() {
        String m = lastNativeDialogMessage;
        lastNativeDialogMessage = null;
        return Optional.ofNullable(m);
    }

    // --- perception -------------------------------------------------------------------------------

    @Override
    public Observation observe() {
        return call(this::observeInternal);
    }

    private Observation observeInternal() {
        settle();
        List<UiElement> elements = new ArrayList<>();
        int counter = 0;
        for (Frame frame : page.frames()) {
            String frameSel = frameSelector(frame);
            Object raw;
            try {
                raw = frame.evaluate(SNAPSHOT_JS);
            } catch (RuntimeException e) {
                continue; // cross-origin or detached frame - skip
            }
            @SuppressWarnings("unchecked")
            List<java.util.Map<String, Object>> nodes = (List<java.util.Map<String, Object>>) raw;
            for (var n : nodes) {
                String ref = "e" + (++counter);
                try {
                    frame.evaluate("([sel, r]) => { const el = document.querySelector(sel); if (el) el.setAttribute('data-cua-ref', r); }",
                            List.of(n.get("uid"), ref));
                } catch (RuntimeException ignored) {}
                elements.add(toElement(ref, frameSel, n));
            }
        }
        String digest = elements.stream()
                .map(UiElement::text).filter(t -> t != null && !t.isBlank())
                .distinct().reduce("", (a, b) -> a.isEmpty() ? b : a + " | " + b);
        String bodyText = "";
        try { bodyText = (String) page.evaluate("() => document.body ? document.body.innerText : ''"); } catch (RuntimeException ignored) {}
        return new Observation(page.url(), page.title(), elements,
                (bodyText + " " + digest).replaceAll("\\s+", " ").trim(),
                config.viewportWidth(), config.viewportHeight());
    }

    @SuppressWarnings("unchecked")
    private UiElement toElement(String ref, String frameSel, java.util.Map<String, Object> n) {
        String role = str(n.get("role"));
        String name = str(n.get("name"));
        String value = str(n.get("value"));
        String text = str(n.get("text"));
        boolean enabled = !Boolean.TRUE.equals(n.get("disabled"));
        boolean editable = Boolean.TRUE.equals(n.get("editable"));
        var b = (java.util.Map<String, Object>) n.get("rect");
        Rect rect = new Rect(num(b.get("x")), num(b.get("y")), num(b.get("w")), num(b.get("h")));

        List<LocatorStrategy> candidates = new ArrayList<>();
        if (!role.isBlank() && !name.isBlank()) candidates.add(LocatorStrategy.roleName(role, name));
        String label = str(n.get("label"));
        if (!label.isBlank()) candidates.add(LocatorStrategy.label(label));
        String placeholder = str(n.get("placeholder"));
        if (!placeholder.isBlank()) candidates.add(LocatorStrategy.placeholder(placeholder));
        String testId = str(n.get("testId"));
        boolean interactive = "button".equals(role) || "link".equals(role) || "tab".equals(role) || "heading".equals(role);
        if (!testId.isBlank()) candidates.add(LocatorStrategy.testId(testId));
        if (!name.isBlank() && ("button".equals(role) || "link".equals(role))) {
            candidates.add(LocatorStrategy.text(name));
        } else if (!text.isBlank() && text.length() < 40 && interactive && !looksLikeValue(text)) {
            candidates.add(LocatorStrategy.text(text));
        }
        String css = str(n.get("css"));
        if (!css.isBlank()) {
            String prefixed = frameSel == null ? css : css; // css is frame-local; resolver re-scopes per frame
            candidates.add(new LocatorStrategy(LocatorStrategy.Kind.CSS, null, null, null, null, null, null, prefixed, null, null, null, null, null));
        }
        if (rect.width() > 0 && rect.height() > 0) {
            candidates.add(LocatorStrategy.bbox(
                    round(rect.centerX() / config.viewportWidth()),
                    round(rect.centerY() / config.viewportHeight())));
        }
        return new UiElement(ref, role, name, value, text, enabled, editable, rect, candidates);
    }

    // --- action ----------------------------------------------------------------------------------

    @Override
    public ActionResult act(Action action) {
        return call(() -> actInternal(action));
    }

    private ActionResult actInternal(Action action) {
        try {
            return switch (action.type()) {
                case NAVIGATE -> {
                    page.navigate(action.url());
                    settle();
                    yield ActionResult.ok("navigated to " + page.url());
                }
                case WAIT -> {
                    page.waitForTimeout(action.waitMs() == null ? 500 : action.waitMs());
                    yield ActionResult.ok("waited");
                }
                case PRESS_KEY -> {
                    page.keyboard().press(action.key());
                    settle();
                    yield ActionResult.ok("pressed " + action.key());
                }
                case CLICK -> {
                    Resolved r = target(action);
                    r.click();
                    settle();
                    yield ActionResult.ok("clicked " + r.describe(), r.element());
                }
                case TYPE -> {
                    Resolved r = target(action);
                    r.fill(action.value() == null ? "" : action.value());
                    yield ActionResult.ok("typed into " + r.describe(), r.element());
                }
                case SELECT_OPTION -> {
                    Resolved r = target(action);
                    r.selectOption(action.value());
                    settle();
                    yield ActionResult.ok("selected " + action.value(), r.element());
                }
                case SCROLL_TO -> {
                    Resolved r = target(action);
                    r.scrollIntoView();
                    yield ActionResult.ok("scrolled to " + r.describe(), r.element());
                }
                case EXTRACT -> {
                    Resolved r = target(action);
                    String txt = r.textContent();
                    yield ActionResult.extracted(txt, r.element());
                }
                case ACCEPT_DIALOG, DISMISS_DIALOG -> {
                    yield ActionResult.ok("dialog handled (" + consumeNativeDialog().orElse("none") + ")");
                }
            };
        } catch (RuntimeException e) {
            return ActionResult.fail(e.getClass().getSimpleName() + ": " + firstLine(e.getMessage()));
        }
    }

    /** Resolve either by discovery-time ref (data-cua-ref) or by a durable locator spec. */
    private Resolved target(Action action) {
        if (action.locator() != null) {
            Resolved r = locate(action.locator());
            if (r == null) throw new CuaException("no element matched locator " + describe(action.locator()));
            return r;
        }
        if (action.targetRef() != null) {
            for (Frame f : page.frames()) {
                Locator l = f.locator("[data-cua-ref='" + action.targetRef() + "']");
                if (l.count() > 0) return new Resolved(l.first(), null, "ref " + action.targetRef());
            }
        }
        throw new CuaException("action has neither targetRef nor locator: " + action.type());
    }

    // --- durable locator resolution -------------------------------------------------------------

    @Override
    public Optional<UiElement> resolve(LocatorSpec spec) {
        return call(() -> resolveInternal(spec));
    }

    private Optional<UiElement> resolveInternal(LocatorSpec spec) {
        Resolved r = locate(spec);
        if (r == null || r.locator == null) return Optional.empty(); // bbox cannot be verified as an element
        try {
            if (r.locator.count() == 0) return Optional.empty();
        } catch (RuntimeException ignored) {}
        UiElement el = r.element();
        return el == null ? Optional.empty() : Optional.of(el);
    }

    @Override
    public ProbeResult probe(LocatorSpec spec) {
        return call(() -> {
            settle();
            Resolved r = locate(spec);
            if (r == null) return ProbeResult.miss();
            return new ProbeResult(true, r.strategyKind());
        });
    }

    /** Returns the first strategy in the chain that resolves to a visible element, else null. */
    Resolved locate(LocatorSpec spec) {
        List<LocatorStrategy> chain = new ArrayList<>();
        if (spec.primary() != null) chain.add(spec.primary());
        if (spec.fallbacks() != null) chain.addAll(spec.fallbacks());
        for (LocatorStrategy s : chain) {
            Resolved r = tryStrategy(s);
            if (r != null) return r;
        }
        return null;
    }

    private Resolved tryStrategy(LocatorStrategy s) {
        if (s.kind() == LocatorStrategy.Kind.BBOX) {
            return new Resolved(null, s, "bbox(" + s.normX() + "," + s.normY() + ")");
        }
        for (Frame f : page.frames()) {
            Locator l;
            try {
                l = strategyLocator(f, s);
            } catch (RuntimeException e) {
                continue;
            }
            if (l == null) continue;
            try {
                if (l.count() >= 1) {
                    Locator first = l.first();
                    if (first.isVisible()) {
                        return new Resolved(first, s, describe(s));
                    }
                }
            } catch (RuntimeException ignored) {}
        }
        return null;
    }

    private Locator strategyLocator(Frame f, LocatorStrategy s) {
        return switch (s.kind()) {
            case ROLE_NAME -> f.getByRole(ariaRole(s.role()), new Frame.GetByRoleOptions().setName(s.name()).setExact(false));
            case LABEL -> f.getByLabel(s.labelText(), new Frame.GetByLabelOptions().setExact(false));
            case PLACEHOLDER -> f.getByPlaceholder(s.placeholder());
            case TEXT -> {
                Locator base = f.getByText(s.text(), new Frame.GetByTextOptions().setExact(true));
                yield s.nth() != null ? base.nth(s.nth()) : base;
            }
            case TEST_ID -> f.locator("#" + cssEscape(s.testId()) + ", [data-testid='" + s.testId() + "'], [data-test='" + s.testId() + "'], [name='" + s.testId() + "']");
            case ANCHORED -> {
                Locator anchor = f.getByText(s.near(), new Frame.GetByTextOptions().setExact(false));
                yield s.nth() != null ? anchor.nth(s.nth()) : anchor;
            }
            case CSS -> f.locator(s.css());
            case XPATH -> f.locator("xpath=" + s.xpath());
            case BBOX -> null;
        };
    }

    private AriaRole ariaRole(String role) {
        try {
            return AriaRole.valueOf(role.toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            return AriaRole.GENERIC;
        }
    }

    /** Wraps a resolved Playwright locator (or a bbox strategy) with the ops replay/act need. */
    final class Resolved {
        private final Locator locator;
        private final LocatorStrategy strategy;
        private final String description;

        Resolved(Locator locator, LocatorStrategy strategy, String description) {
            this.locator = locator;
            this.strategy = strategy;
            this.description = description;
        }

        LocatorStrategy.Kind strategyKind() { return strategy == null ? null : strategy.kind(); }
        String describe() { return description; }

        void click() {
            if (locator != null) locator.click(new Locator.ClickOptions().setTimeout(config.defaultTimeoutMs()));
            else page.mouse().click(strategy.normX() * config.viewportWidth(), strategy.normY() * config.viewportHeight());
        }

        void fill(String v) {
            if (locator != null) { locator.fill(v); return; }
            page.mouse().click(strategy.normX() * config.viewportWidth(), strategy.normY() * config.viewportHeight());
            page.keyboard().type(v);
        }

        void selectOption(String v) {
            if (locator != null) locator.selectOption(new SelectOption().setLabel(v));
        }

        void scrollIntoView() { if (locator != null) locator.scrollIntoViewIfNeeded(); }

        String textContent() {
            if (locator == null) return "";
            String t = locator.first().textContent();
            return t == null ? "" : t.trim();
        }

        boolean isVisible() {
            try { return locator == null || locator.first().isVisible(); } catch (RuntimeException e) { return false; }
        }

        UiElement element() {
            try {
                if (locator == null) return null;
                Locator l = locator.first();
                var box = l.boundingBox();
                Rect rect = box == null ? new Rect(0, 0, 0, 0) : new Rect(box.x, box.y, box.width, box.height);
                String txt = l.textContent();
                return new UiElement("resolved", "", "", "", txt == null ? "" : txt.trim(),
                        true, false, rect, List.of());
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    // --- evidence + lifecycle ------------------------------------------------------------------

    @Override
    public byte[] screenshot() {
        return call(() -> page.screenshot(new Page.ScreenshotOptions().setFullPage(false)));
    }

    @Override
    public String rawSnapshot() {
        return call(() -> page.content());
    }

    @Override
    public String location() {
        return call(() -> page.url());
    }

    @Override
    public void close() {
        try {
            run(() -> {
                try { context.close(); } catch (RuntimeException ignored) {}
                try { browser.close(); } catch (RuntimeException ignored) {}
                try { playwright.close(); } catch (RuntimeException ignored) {}
            });
        } catch (RuntimeException ignored) {}
        pw.shutdownNow();
    }

    private void settle() {
        try { page.waitForLoadState(LoadState.DOMCONTENTLOADED); } catch (RuntimeException ignored) {}
        try { page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(3000)); } catch (RuntimeException ignored) {}
    }

    private static String frameSelector(Frame f) {
        return f.parentFrame() == null ? null : "iframe";
    }

    static String describe(LocatorStrategy s) {
        return switch (s.kind()) {
            case ROLE_NAME -> "role=" + s.role() + " name~='" + s.name() + "'";
            case LABEL -> "label~='" + s.labelText() + "'";
            case PLACEHOLDER -> "placeholder='" + s.placeholder() + "'";
            case TEXT -> "text='" + s.text() + "'";
            case TEST_ID -> "testId='" + s.testId() + "'";
            case ANCHORED -> "near='" + s.near() + "'";
            case CSS -> "css='" + s.css() + "'";
            case XPATH -> "xpath='" + s.xpath() + "'";
            case BBOX -> "bbox(" + s.normX() + "," + s.normY() + ")";
        };
    }

    static String describe(LocatorSpec spec) {
        return spec.primary() == null ? "<none>" : describe(spec.primary());
    }

    private static String str(Object o) { return o == null ? "" : o.toString().trim(); }
    private static double num(Object o) { return o instanceof Number n ? n.doubleValue() : 0; }
    private static double round(double d) { return Math.round(d * 1000.0) / 1000.0; }
    private static String cssEscape(String s) { return s.replaceAll("[^a-zA-Z0-9_\\-]", "_"); }
    private static boolean looksLikeValue(String s) { return s.matches("[$£€]?\\s*[0-9][0-9,]*(?:\\.[0-9]+)?%?"); }
    private static String firstLine(String s) { return s == null ? "" : s.split("\n", 2)[0]; }

    /**
     * DOM walk executed in every frame. Produces one entry per interactive/labelled node with a
     * best-effort ARIA role, accessible name, geometry, and a unique CSS path we stamp for ref-based
     * resolution. Kept intentionally conservative: enterprise legacy pages have thousands of table
     * cells, so we only surface things an operator would actually act on or read as a status.
     */
    private static final String SNAPSHOT_JS = """
        () => {
          const out = [];
          const seen = new Set();
          const interactiveSel = "a[href], button, input:not([type=hidden]), select, textarea, [role=button], [role=link], [role=tab], [onclick]";
          const statusSel = "h1,h2,h3,legend,label,.status,.error,.message,.alert,[role=alert],[role=status],td,th,dt,dd,p,span";
          function cssPath(el) {
            if (el.id) return '#' + CSS.escape(el.id);
            const parts = [];
            let node = el;
            while (node && node.nodeType === 1 && parts.length < 6) {
              let sel = node.nodeName.toLowerCase();
              if (node.className && typeof node.className === 'string') {
                const c = node.className.trim().split(/\\s+/).filter(Boolean).slice(0,2).map(x=>'.'+CSS.escape(x)).join('');
                sel += c;
              }
              const parent = node.parentNode;
              if (parent) {
                const sibs = Array.from(parent.children).filter(n => n.nodeName === node.nodeName);
                if (sibs.length > 1) sel += ':nth-of-type(' + (sibs.indexOf(node)+1) + ')';
              }
              parts.unshift(sel);
              node = node.parentElement;
            }
            return parts.join(' > ');
          }
          function accName(el) {
            const aria = el.getAttribute('aria-label');
            if (aria) return aria.trim();
            const labelledby = el.getAttribute('aria-labelledby');
            if (labelledby) {
              const l = document.getElementById(labelledby);
              if (l) return l.innerText.trim();
            }
            if (el.id) {
              const lab = document.querySelector("label[for='" + CSS.escape(el.id) + "']");
              if (lab) return lab.innerText.trim();
            }
            const wrapLabel = el.closest('label');
            if (wrapLabel) return wrapLabel.innerText.trim();
            if (el.tagName === 'INPUT' && el.value && (el.type === 'submit' || el.type === 'button')) return el.value.trim();
            const t = (el.innerText || el.textContent || '').trim();
            if (t) return t.replace(/\\s+/g,' ').slice(0, 80);
            return (el.getAttribute('placeholder') || el.getAttribute('title') || el.getAttribute('name') || '').trim();
          }
          function roleOf(el) {
            const explicit = el.getAttribute('role');
            if (explicit) return explicit;
            const tag = el.tagName;
            if (tag === 'A' && el.hasAttribute('href')) return 'link';
            if (tag === 'BUTTON') return 'button';
            if (tag === 'SELECT') return 'combobox';
            if (tag === 'TEXTAREA') return 'textbox';
            if (tag === 'INPUT') {
              const ty = (el.getAttribute('type') || 'text').toLowerCase();
              if (ty === 'submit' || ty === 'button' || ty === 'reset') return 'button';
              if (ty === 'checkbox') return 'checkbox';
              if (ty === 'radio') return 'radio';
              return 'textbox';
            }
            if (/^H[1-6]$/.test(tag)) return 'heading';
            if (tag === 'LABEL') return 'label';
            return '';
          }
          function visible(el) {
            const r = el.getBoundingClientRect();
            if (r.width === 0 && r.height === 0) return false;
            const st = getComputedStyle(el);
            return st.visibility !== 'hidden' && st.display !== 'none' && st.opacity !== '0';
          }
          function push(el, kind) {
            if (seen.has(el) || !visible(el)) return;
            const name = accName(el);
            const role = roleOf(el);
            if (kind === 'status' && (!name || name.length > 120)) return;
            if (kind === 'status' && !role && !/error|status|alert|message/i.test(el.className)) {
              // keep only short standalone status text nodes
              if (el.children.length > 0) return;
            }
            seen.add(el);
            const r = el.getBoundingClientRect();
            let css;
            try { css = cssPath(el); } catch (e) { css = el.tagName.toLowerCase(); }
            const forLabel = el.tagName === 'INPUT' && el.id
              ? (document.querySelector("label[for='" + CSS.escape(el.id) + "']") || {}).innerText : '';
            out.push({
              uid: css,
              css: css,
              role: role,
              name: name,
              text: (el.innerText || el.textContent || '').trim().replace(/\\s+/g,' ').slice(0,120),
              value: ('value' in el ? (el.value || '') : ''),
              label: (forLabel || '').trim(),
              placeholder: el.getAttribute('placeholder') || '',
              testId: el.getAttribute('data-testid') || el.getAttribute('data-test') || el.id || el.getAttribute('name') || '',
              disabled: !!el.disabled || el.getAttribute('aria-disabled') === 'true',
              editable: (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA') && !el.disabled && !el.readOnly,
              rect: { x: r.x, y: r.y, w: r.width, h: r.height }
            });
          }
          document.querySelectorAll(interactiveSel).forEach(el => push(el, 'interactive'));
          document.querySelectorAll(statusSel).forEach(el => push(el, 'status'));
          return out.slice(0, 120);
        }
        """;
}
