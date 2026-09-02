package com.example.cua.server;

import com.example.cua.artifact.Artifact;
import com.example.cua.artifact.ArtifactStore;
import com.example.cua.core.Json;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Stretch goal: expose saved artifacts as a catalog of callable capabilities that an AI agent could
 * discover and invoke by name with typed args. Each capability advertises a JSON-Schema-shaped
 * contract (name, description, params, outputs) derived straight from the artifact - the same
 * contract a human reviewer reads.
 *
 * <ul>
 *   <li>{@code GET  /capabilities} - list capability tool specs</li>
 *   <li>{@code GET  /capabilities/{name}} - one capability's full contract</li>
 *   <li>{@code POST /capabilities/{name}/invoke} - run a deterministic replay with typed args</li>
 * </ul>
 */
public final class CapabilityApi {

    /** invoke(name, args) -> replay result object */
    public interface Invoker {
        Object invoke(String name, Map<String, String> args, String tenantId);
    }

    private final ArtifactStore store;
    private final Invoker invoker;

    public CapabilityApi(ArtifactStore store, Invoker invoker) {
        this.store = store;
        this.invoker = invoker;
    }

    public void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        try {
            String[] parts = path.replaceFirst("^/capabilities/?", "").split("/");
            if (path.equals("/capabilities") || path.equals("/capabilities/")) {
                json(ex, 200, Map.of("capabilities", store.list().stream().map(CapabilityApi::toolSpec).toList()));
            } else if (parts.length == 1 && !parts[0].isBlank() && "GET".equals(ex.getRequestMethod())) {
                json(ex, 200, toolSpec(store.require(parts[0])));
            } else if (parts.length == 2 && parts[1].equals("invoke") && "POST".equals(ex.getRequestMethod())) {
                Map<String, Object> req = readJson(ex);
                @SuppressWarnings("unchecked")
                Map<String, Object> rawArgs = (Map<String, Object>) req.getOrDefault("args", Map.of());
                Map<String, String> args = new LinkedHashMap<>();
                rawArgs.forEach((k, v) -> args.put(k, String.valueOf(v)));
                String tenant = (String) req.getOrDefault("tenant", "base");
                json(ex, 200, invoker.invoke(parts[0], args, tenant));
            } else {
                json(ex, 404, Map.of("error", "no such capability route"));
            }
        } catch (RuntimeException e) {
            json(ex, 400, Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    public static Map<String, Object> toolSpec(Artifact a) {
        Map<String, Object> props = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (Artifact.ParamSpec p : a.parameters()) {
            Map<String, Object> ps = new LinkedHashMap<>();
            ps.put("type", jsonType(p.type()));
            ps.put("description", p.description());
            if (p.pattern() != null) ps.put("pattern", p.pattern());
            if (p.sensitive()) ps.put("x-sensitive", true);
            props.put(p.name(), ps);
            if (p.required()) required.add(p.name());
        }
        Map<String, Object> outputs = new LinkedHashMap<>();
        for (Artifact.OutputSpec o : a.outputs()) {
            outputs.put(o.name(), Map.of("type", jsonType(o.type()), "description", o.description()));
        }
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("name", a.meta().name());
        spec.put("version", a.meta().version());
        spec.put("description", a.meta().description());
        spec.put("approval", a.meta().approval().toString());
        spec.put("input_schema", Map.of("type", "object", "properties", props, "required", required));
        spec.put("output_schema", Map.of("type", "object", "properties", outputs));
        spec.put("target", a.target());
        return spec;
    }

    private static String jsonType(Artifact.ValueType t) {
        return switch (t) {
            case INTEGER -> "integer";
            case NUMBER, MONEY -> "number";
            case BOOLEAN -> "boolean";
            default -> "string";
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(HttpExchange ex) throws IOException {
        byte[] b = ex.getRequestBody().readAllBytes();
        return b.length == 0 ? Map.of() : Json.MAPPER.readValue(b, Map.class);
    }

    private void json(HttpExchange ex, int status, Object body) throws IOException {
        byte[] b = Json.write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }
}
