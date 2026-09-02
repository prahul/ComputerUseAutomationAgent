package com.example.cua.server;

import com.example.cua.artifact.ArtifactStore;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The one embedded HTTP server the whole exercise runs against. It mounts:
 * <ul>
 *   <li>the target app under {@code /} (tenant "base") and {@code /altcu} (tenant "altcu") - two
 *       brandings of the same vendor product, for the cross-tenant reuse demo;</li>
 *   <li>the operator console under {@code /operator};</li>
 *   <li>the capability catalog API under {@code /capabilities};</li>
 *   <li>a small control endpoint {@code /__control/inject} to arm runtime-fault injection.</li>
 * </ul>
 */
public final class AppServer implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(AppServer.class);

    private final HttpServer http;
    private final int port;
    private final TargetApp base;
    private final TargetApp alt;
    private final AtomicReference<LiveSession> liveSession = new AtomicReference<>();

    public AppServer(int port, ArtifactStore store, CapabilityApi.Invoker invoker) throws IOException {
        this.port = port;
        this.base = new TargetApp(TargetApp.Branding.base(""));
        this.alt = new TargetApp(TargetApp.Branding.altcu("/altcu"));
        this.http = HttpServer.create(new InetSocketAddress(port), 0);
        this.http.setExecutor(Executors.newFixedThreadPool(8));

        OperatorConsole operator = new OperatorConsole(liveSession);
        CapabilityApi capabilities = invoker == null ? null : new CapabilityApi(store, invoker);

        http.createContext("/altcu", alt::handle);
        http.createContext("/operator", operator::handle);
        http.createContext("/__control/inject", this::inject);
        if (capabilities != null) http.createContext("/capabilities", capabilities::handle);
        http.createContext("/", this::routeRoot);
    }

    private void routeRoot(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.startsWith("/operator") || path.startsWith("/capabilities")
                || path.startsWith("/altcu") || path.startsWith("/__control")) {
            ex.sendResponseHeaders(404, -1);
            ex.close();
            return;
        }
        base.handle(ex);
    }

    private void inject(HttpExchange ex) throws IOException {
        String raw = ex.getRequestURI().getRawQuery();
        String mode = TargetApp.query(raw).getOrDefault("mode", "none");
        List<String> scope = List.of(TargetApp.query(raw).getOrDefault("tenant", "base"));
        if (scope.contains("altcu")) alt.setInject(mode); else base.setInject(mode);
        byte[] b = ("{\"inject\":\"" + mode + "\"}").getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    public void setInject(String tenantId, String mode) {
        if ("altcu".equals(tenantId)) alt.setInject(mode); else base.setInject(mode);
    }

    public void bindLiveSession(LiveSession s) { liveSession.set(s); }
    public void clearLiveSession() { liveSession.set(null); }

    public String baseUrl() { return "http://localhost:" + port; }
    public String tenantBaseUrl(String tenantId) {
        return "altcu".equals(tenantId) ? baseUrl() + "/altcu" : baseUrl();
    }

    public void start() {
        http.start();
        log.info("app server on {}  (operator console: {}/operator)", baseUrl(), baseUrl());
    }

    @Override
    public void close() {
        http.stop(0);
    }
}
