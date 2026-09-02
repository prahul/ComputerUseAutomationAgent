package com.example.cua.server;

import com.example.cua.artifact.Artifact;
import com.example.cua.core.Json;
import com.example.cua.escalation.Escalation;
import com.example.cua.surface.Surface;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A deliberately minimal (but real) operator surface for human-in-the-loop handoff. It does not try
 * to be a co-browsing console. It shows the open intervention requests, a live screenshot of the
 * <em>same</em> session the automation was driving, lets the operator issue clicks/keystrokes into
 * that session, records every manual action as evidence, and signals resume - which hands control
 * back to the blocked automation thread exactly where it stopped.
 *
 * <p>Because the browser runs headed during an escalation, the operator can equally well act in the
 * real browser window; this console is the transport-agnostic version of the same control transfer.
 */
public final class OperatorConsole {

    private final AtomicReference<LiveSession> session;

    public OperatorConsole(AtomicReference<LiveSession> session) {
        this.session = session;
    }

    public void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        try {
            switch (path) {
                case "/operator", "/operator/" -> html(ex, INDEX_HTML);
                case "/operator/api/interventions" -> json(ex, interventions());
                case "/operator/api/screenshot" -> screenshot(ex);
                case "/operator/api/act" -> act(ex);
                case "/operator/api/resume" -> resume(ex);
                default -> { ex.sendResponseHeaders(404, -1); ex.close(); }
            }
        } catch (RuntimeException e) {
            json(ex, Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    private Object interventions() {
        LiveSession s = session.get();
        if (s == null) return Map.of("active", false, "requests", java.util.List.of());
        return Map.of(
                "active", true,
                "runId", s.runId(),
                "controlOwner", s.controller().owner().toString(),
                "requests", s.broker().all());
    }

    private void screenshot(HttpExchange ex) throws IOException {
        LiveSession s = session.get();
        byte[] png = s == null ? new byte[0] : s.surface().screenshot();
        ex.getResponseHeaders().add("Content-Type", "image/png");
        ex.getResponseHeaders().add("Cache-Control", "no-store");
        ex.sendResponseHeaders(200, png.length == 0 ? -1 : png.length);
        if (png.length > 0) try (OutputStream os = ex.getResponseBody()) { os.write(png); }
        else ex.close();
    }

    private void act(HttpExchange ex) throws IOException {
        LiveSession s = session.get();
        if (s == null) { json(ex, Map.of("error", "no active session")); return; }
        if (s.controller().owner() != Escalation.ControlOwner.OPERATOR) {
            json(ex, Map.of("error", "control is not currently held by the operator")); return;
        }
        Map<String, Object> req = readJson(ex);
        String kind = String.valueOf(req.getOrDefault("kind", ""));
        Surface.Action action;
        String detail;
        switch (kind) {
            case "click" -> {
                String sel = String.valueOf(req.get("selector"));
                action = new Surface.Action(Surface.ActionType.CLICK, null,
                        css(sel), null, null, null, null);
                detail = "click " + sel;
            }
            case "type" -> {
                String sel = String.valueOf(req.get("selector"));
                String text = String.valueOf(req.get("text"));
                action = new Surface.Action(Surface.ActionType.TYPE, null, css(sel), text, null, null, null);
                detail = "type into " + sel;
            }
            case "press" -> {
                action = Surface.Action.pressKey(String.valueOf(req.get("key")));
                detail = "press " + req.get("key");
            }
            case "navigate" -> {
                action = Surface.Action.navigate(String.valueOf(req.get("url")));
                detail = "navigate " + req.get("url");
            }
            default -> { json(ex, Map.of("error", "unknown kind " + kind)); return; }
        }
        Surface.ActionResult r = s.surface().act(action);
        s.controller().recordHumanAction(kind, detail + (r.ok() ? " [ok]" : " [failed: " + r.detail() + "]"));
        s.evidence().event("operator.action", Map.of("kind", kind, "detail", detail, "ok", r.ok()));
        s.evidence().screenshot("operator-" + kind, s.surface().screenshot());
        json(ex, Map.of("ok", r.ok(), "detail", r.detail()));
    }

    private void resume(HttpExchange ex) throws IOException {
        LiveSession s = session.get();
        if (s == null) { json(ex, Map.of("error", "no active session")); return; }
        Map<String, Object> req = readJson(ex);
        Escalation.Resolution res;
        try {
            res = Escalation.Resolution.valueOf(String.valueOf(req.getOrDefault("resolution", "RESUME")));
        } catch (RuntimeException e) {
            res = Escalation.Resolution.RESUME;
        }
        String note = String.valueOf(req.getOrDefault("note", ""));
        String operator = String.valueOf(req.getOrDefault("operator", "operator"));
        boolean ok = s.controller().resume(operator, res, note);
        json(ex, Map.of("ok", ok, "resolution", res.toString()));
    }

    private Artifact.LocatorSpec css(String selector) {
        return new Artifact.LocatorSpec(Artifact.LocatorStrategy.css(selector), java.util.List.of(),
                "operator-supplied CSS selector");
    }

    // --- http glue -----------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(HttpExchange ex) throws IOException {
        byte[] b = ex.getRequestBody().readAllBytes();
        if (b.length == 0) return Map.of();
        return Json.MAPPER.readValue(b, Map.class);
    }

    private void json(HttpExchange ex, Object body) throws IOException {
        byte[] b = Json.write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    private void html(HttpExchange ex, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    private static final String INDEX_HTML = """
            <!doctype html><html><head><meta charset="utf-8"><title>Operator Console</title>
            <style>
              body{font-family:system-ui,Arial,sans-serif;margin:0;display:grid;grid-template-columns:380px 1fr;height:100vh}
              #side{padding:16px;border-right:1px solid #ddd;overflow:auto}
              #main{padding:16px;overflow:auto;background:#fafafa}
              img{max-width:100%;border:1px solid #ccc}
              .req{border:1px solid #e0b400;background:#fff8e1;padding:10px;border-radius:6px;margin:8px 0;font-size:13px}
              .closed{border-color:#bbb;background:#f3f3f3}
              input,button,select{font-size:13px;padding:5px;margin:2px 0}
              label{display:block;margin-top:8px;font-weight:600;font-size:12px}
              code{background:#eee;padding:1px 4px}
            </style></head><body>
            <div id="side">
              <h2>Operator Console</h2>
              <div id="status">loading…</div>
              <h3>Interventions</h3>
              <div id="reqs"></div>
              <h3>Drive the live session</h3>
              <label>CSS selector</label><input id="sel" placeholder="#mid or button">
              <label>Text (for type)</label><input id="txt">
              <div>
                <button onclick="act('click')">Click</button>
                <button onclick="act('type')">Type</button>
                <button onclick="press('Enter')">Press Enter</button>
              </div>
              <label>Resolve</label>
              <select id="res">
                <option>RESUME</option><option>RESUME_SKIP_STEP</option><option>ABORT</option>
              </select>
              <input id="note" placeholder="note for the record">
              <button onclick="resume()">Hand control back</button>
            </div>
            <div id="main"><img id="shot" src="/operator/api/screenshot"><p id="log"></p></div>
            <script>
              async function refresh(){
                const r = await (await fetch('/operator/api/interventions')).json();
                document.getElementById('status').innerHTML =
                  r.active ? ('run <code>'+r.runId+'</code><br>control owner: <b>'+r.controlOwner+'</b>') : 'no active run';
                document.getElementById('reqs').innerHTML = (r.requests||[]).map(q =>
                  '<div class="req '+(q.closedAt?'closed':'')+'"><b>'+q.trigger+'</b> — '+q.reason+
                  (q.question?('<br><i>Q: '+q.question+'</i>'):'')+
                  '<br>step: '+(q.currentStepId||'-')+' @ '+q.location+
                  (q.closedAt?('<br>resolved: '+q.resolution+' by '+q.resolvedBy):'')+'</div>').join('');
                document.getElementById('shot').src = '/operator/api/screenshot?t='+Date.now();
              }
              async function post(path, body){
                const r = await fetch(path,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});
                document.getElementById('log').textContent = JSON.stringify(await r.json());
                refresh();
              }
              function act(kind){ post('/operator/api/act', {kind, selector:document.getElementById('sel').value, text:document.getElementById('txt').value}); }
              function press(key){ post('/operator/api/act', {kind:'press', key}); }
              function resume(){ post('/operator/api/resume', {resolution:document.getElementById('res').value, note:document.getElementById('note').value, operator:'console-operator'}); }
              setInterval(refresh, 1500); refresh();
            </script></body></html>
            """;
}
