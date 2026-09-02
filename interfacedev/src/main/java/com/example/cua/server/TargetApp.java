package com.example.cua.server;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A stand-in for a legacy credit-union back-office servicing console. It is deliberately
 * "hostile-ish": table-based layout, server-rendered, an <iframe> for the member panel, no
 * test IDs. It still exposes reasonable accessible names (labels, button text) so the
 * role+name locator strategy has something durable to bind to - that is the point of the
 * locator-robustness story, not an accident.
 *
 * <p>Flow: login -> dashboard (member search) -> member detail (savings balance) ->
 * open sub-account (multi-field form) -> confirmation.
 *
 * <p>Runtime conditions it can produce (for replay error-handling demos):
 * <ul>
 *   <li>member not found (id 99999) - business outcome</li>
 *   <li>permission denied (id 10003, RESTRICTED) - business outcome</li>
 *   <li>validation error (negative / non-numeric deposit) - business outcome</li>
 *   <li>injected: slow load, unexpected interstitial, session timeout, 500 error</li>
 * </ul>
 */
public final class TargetApp {

    /** Per-tenant configuration of the same underlying vendor product. */
    public record Branding(String tenantId, String contextPath, String institution, String memberNoun, String openSubAccountLabel, String accent) {
        public static Branding base(String ctx) { return new Branding("base", ctx, "Riverbend Credit Union", "Member", "Open Sub-Account", "#1f6feb"); }
        public static Branding altcu(String ctx) { return new Branding("altcu", ctx, "Summit Financial CU", "Customer", "New Sub-Account", "#8a3ffc"); }
    }

    record Member(String id, String name, String status, double savings, double checking) {}

    private final Branding b;
    private final Map<String, Member> members = new LinkedHashMap<>();
    private final Map<String, String> sessions = new HashMap<>();
    private volatile String inject = "none";
    private volatile boolean interstitialPending = false;

    public TargetApp(Branding branding) {
        this.b = branding;
        members.put("10001", new Member("10001", "Ada Fletcher", "ACTIVE", 4210.55, 812.30));
        members.put("10002", new Member("10002", "Marcus Reyes", "ACTIVE", 15230.00, 2450.10));
        members.put("10003", new Member("10003", "Priya Anand", "RESTRICTED", 0, 0));
    }

    public String contextPath() { return b.contextPath(); }
    public String tenantId() { return b.tenantId(); }

    public void setInject(String mode) {
        this.inject = mode == null ? "none" : mode;
        this.interstitialPending = "interstitial".equals(this.inject);
    }

    public void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath().substring(b.contextPath().length());
        if (path.isEmpty()) path = "/";
        Map<String, String> q = query(ex.getRequestURI().getRawQuery());
        String cookie = header(ex, "Cookie");
        boolean authed = cookie != null && cookie.contains("sid=") && sessions.containsKey(sid(cookie));

        try {
            if ("error".equals(inject) && (path.startsWith("/member") || path.startsWith("/sub-account"))) {
                send(ex, 500, page("Application Error", "<p class='error'>HTTP 500 — the application encountered an unexpected error. Reference " + UUID.randomUUID() + "</p>"));
                return;
            }
            if ("slow".equals(inject) && path.startsWith("/member")) {
                sleep(3200);
            }

            switch (method(ex) + " " + route(path)) {
                case "GET /" -> redirect(ex, b.contextPath() + "/login");
                case "GET /login" -> send(ex, 200, loginPage(null));
                case "POST /login" -> doLogin(ex);
                case "GET /interstitial" -> send(ex, 200, interstitialPage());
                case "POST /interstitial" -> { interstitialPending = false; redirect(ex, b.contextPath() + "/dashboard"); }
                case "GET /dashboard" -> {
                    if (!authed) { redirect(ex, b.contextPath() + "/login"); break; }
                    if (interstitialPending) { redirect(ex, b.contextPath() + "/interstitial"); break; }
                    send(ex, 200, dashboardPage(q.get("id")));
                }
                case "GET /member-frame" -> {
                    if (!authed || "session_timeout".equals(inject)) {
                        send(ex, 200, page("Session", "<p class='error'>Your session has expired. Please sign in again.</p>"));
                        break;
                    }
                    send(ex, 200, memberFramePage(q.get("id")));
                }
                case "GET /sub-account" -> {
                    if (!authed) { redirect(ex, b.contextPath() + "/login"); break; }
                    send(ex, 200, subAccountFormPage(q.get("member"), null));
                }
                case "POST /sub-account" -> doSubAccountReview(ex);
                default -> send(ex, 404, page("Not Found", "<p>No such page.</p>"));
            }
        } catch (RuntimeException e) {
            send(ex, 500, page("Application Error", "<p class='error'>Unexpected error: " + esc(e.getMessage()) + "</p>"));
        }
    }

    private String route(String path) {
        if (path.startsWith("/sub-account")) return "/sub-account";
        return path;
    }

    // --- handlers ---------------------------------------------------------------------------

    private void doLogin(HttpExchange ex) throws IOException {
        Map<String, String> form = query(body(ex));
        String user = form.getOrDefault("username", "");
        String pass = form.getOrDefault("password", "");
        if (user.isBlank() || pass.isBlank()) {
            send(ex, 200, loginPage("Enter both a username and a password."));
            return;
        }
        String sid = UUID.randomUUID().toString();
        sessions.put(sid, user);
        ex.getResponseHeaders().add("Set-Cookie", "sid=" + sid + "; Path=" + b.contextPath() + "; HttpOnly");
        redirect(ex, b.contextPath() + (interstitialPending ? "/interstitial" : "/dashboard"));
    }

    private void doSubAccountReview(HttpExchange ex) throws IOException {
        Map<String, String> form = query(body(ex));
        String memberId = form.getOrDefault("member", "");
        String type = form.getOrDefault("accountType", "");
        String deposit = form.getOrDefault("initialDeposit", "").trim();
        double amount;
        try {
            amount = Double.parseDouble(deposit);
        } catch (NumberFormatException e) {
            send(ex, 200, subAccountFormPage(memberId, "Initial deposit must be a number."));
            return;
        }
        if (amount < 0) {
            send(ex, 200, subAccountFormPage(memberId, "Initial deposit cannot be negative."));
            return;
        }
        if (type.isBlank()) {
            send(ex, 200, subAccountFormPage(memberId, "Select an account type."));
            return;
        }
        String sa = "SA-" + (1000 + new java.util.Random().nextInt(9000));
        String cnf = "CNF-" + (100000 + new java.util.Random().nextInt(900000));
        send(ex, 200, page("Sub-Account Confirmation", """
                <h1>Sub-Account Confirmation</h1>
                <table border="1" cellpadding="6">
                  <tr><th align="left">%s</th><td>%s</td></tr>
                  <tr><th align="left">New sub-account</th><td>%s</td></tr>
                  <tr><th align="left">Account type</th><td>%s</td></tr>
                  <tr><th align="left">Opening deposit</th><td>$%.2f</td></tr>
                  <tr><th align="left">Confirmation number</th><td>%s</td></tr>
                </table>
                <p class="ok">The sub-account was created successfully.</p>
                """.formatted(b.memberNoun() + " ID", esc(memberId), sa, esc(type), amount, cnf)));
    }

    // --- pages ------------------------------------------------------------------------------

    private String loginPage(String error) {
        return page("Sign In", """
                <h1>%s — Servicing Console</h1>
                %s
                <form method="POST" action="%s/login">
                  <table>
                    <tr><td><label for="u">Username</label></td><td><input id="u" name="username" type="text"></td></tr>
                    <tr><td><label for="p">Password</label></td><td><input id="p" name="password" type="password"></td></tr>
                    <tr><td colspan="2"><button type="submit">Sign In</button></td></tr>
                  </table>
                </form>
                <p style="color:#888">Demo credentials: any non-empty username / password.</p>
                """.formatted(esc(b.institution()), error == null ? "" : "<p class='error'>" + esc(error) + "</p>", b.contextPath()));
    }

    private String interstitialPage() {
        return page("Notice", """
                <h1>System Notice</h1>
                <p>Scheduled maintenance is planned for this weekend. No action is required.</p>
                <form method="POST" action="%s/interstitial"><button type="submit">Continue</button></form>
                """.formatted(b.contextPath()));
    }

    private String dashboardPage(String searchedId) {
        String frame = "";
        if (searchedId != null && !searchedId.isBlank()) {
            frame = "<iframe title=\"" + esc(b.memberNoun()) + " detail\" src=\"" + b.contextPath()
                    + "/member-frame?id=" + esc(searchedId) + "\" width=\"680\" height=\"320\" style=\"border:1px solid #ccc\"></iframe>";
        }
        return page("Dashboard", """
                <h1>%s Servicing</h1>
                <table><tr>
                  <td>
                    <form method="GET" action="%s/dashboard">
                      <label for="mid">%s ID</label>
                      <input id="mid" name="id" type="text" value="%s">
                      <button type="submit">Search</button>
                    </form>
                  </td>
                </tr></table>
                <div id="results">%s</div>
                """.formatted(esc(b.memberNoun()), b.contextPath(), esc(b.memberNoun()),
                searchedId == null ? "" : esc(searchedId), frame));
    }

    private String memberFramePage(String id) {
        if (id == null || id.isBlank()) {
            return frame("<p>Enter a " + esc(b.memberNoun().toLowerCase()) + " ID and press Search.</p>");
        }
        Member m = members.get(id.trim());
        if (m == null) {
            return frame("<p class=\"error\">No member found matching that ID.</p>");
        }
        if ("RESTRICTED".equals(m.status())) {
            return frame("<p class=\"error\">You do not have permission to view this member.</p>");
        }
        return frame("""
                <table border="1" cellpadding="6">
                  <tr><th align="left">%s name</th><td>%s</td></tr>
                  <tr><th align="left">%s ID</th><td>%s</td></tr>
                  <tr><th align="left">Status</th><td>%s</td></tr>
                  <tr><th align="left">Savings balance</th><td id="savings">$%.2f</td></tr>
                  <tr><th align="left">Checking balance</th><td id="checking">$%.2f</td></tr>
                </table>
                <p><a href="%s/sub-account?member=%s" target="_top">%s</a></p>
                """.formatted(esc(b.memberNoun()), esc(m.name()), esc(b.memberNoun()), esc(m.id()),
                esc(m.status()), m.savings(), m.checking(), b.contextPath(), esc(m.id()), esc(b.openSubAccountLabel())));
    }

    private String subAccountFormPage(String memberId, String error) {
        return page("Open Sub-Account", """
                <h1>%s</h1>
                <p>%s ID: %s</p>
                %s
                <form method="POST" action="%s/sub-account">
                  <input type="hidden" name="member" value="%s">
                  <table>
                    <tr><td><label for="at">Account type</label></td>
                        <td><select id="at" name="accountType">
                          <option value="">— select —</option>
                          <option value="Regular Savings">Regular Savings</option>
                          <option value="Holiday Club">Holiday Club</option>
                          <option value="Money Market">Money Market</option>
                        </select></td></tr>
                    <tr><td><label for="dep">Initial deposit</label></td>
                        <td><input id="dep" name="initialDeposit" type="text" placeholder="0.00"></td></tr>
                    <tr><td colspan="2"><button type="submit">Review and Create</button></td></tr>
                  </table>
                </form>
                """.formatted(esc(b.openSubAccountLabel()), esc(b.memberNoun()), esc(memberId),
                error == null ? "" : "<p class='error'>" + esc(error) + "</p>", b.contextPath(), esc(memberId)));
    }

    private String frame(String inner) {
        return "<!doctype html><html><head><meta charset='utf-8'><title>Member Detail</title>"
                + styleTag() + "</head><body>" + inner + "</body></html>";
    }

    private String page(String title, String body) {
        return "<!doctype html><html><head><meta charset='utf-8'><title>" + esc(title) + " — " + esc(b.institution())
                + "</title>" + styleTag() + "</head><body><div style='max-width:820px;margin:20px auto;font-family:Verdana,Arial,sans-serif'>"
                + "<div style='border-bottom:3px solid " + b.accent() + ";padding-bottom:6px;margin-bottom:14px'>"
                + "<strong style='color:" + b.accent() + "'>" + esc(b.institution()) + "</strong></div>"
                + body + "</div></body></html>";
    }

    private String styleTag() {
        return "<style>body{font-family:Verdana,Arial,sans-serif;color:#222}"
                + ".error{color:#b00020;font-weight:bold}.ok{color:#0a7d28;font-weight:bold}"
                + "table{border-collapse:collapse}button{padding:6px 14px;cursor:pointer}"
                + "input,select{padding:4px;margin:3px}label{display:inline-block;min-width:120px}</style>";
    }

    // --- http helpers ---------------------------------------------------------------------

    private static String method(HttpExchange ex) { return ex.getRequestMethod(); }
    private static String header(HttpExchange ex, String n) { return ex.getRequestHeaders().getFirst(n); }

    private static String sid(String cookie) {
        for (String part : cookie.split(";")) {
            String p = part.trim();
            if (p.startsWith("sid=")) return p.substring(4);
        }
        return "";
    }

    private static String body(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    static Map<String, String> query(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null || raw.isBlank()) return m;
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            if (i < 0) { m.put(dec(pair), ""); continue; }
            m.put(dec(pair.substring(0, i)), dec(pair.substring(i + 1)));
        }
        return m;
    }

    private static String dec(String s) { return URLDecoder.decode(s, StandardCharsets.UTF_8); }

    private void redirect(HttpExchange ex, String location) throws IOException {
        ex.getResponseHeaders().add("Location", location);
        ex.sendResponseHeaders(302, -1);
        ex.close();
    }

    private void send(HttpExchange ex, int status, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
