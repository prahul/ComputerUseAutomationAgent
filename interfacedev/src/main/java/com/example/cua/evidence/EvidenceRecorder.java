package com.example.cua.evidence;

import com.example.cua.core.Json;
import com.example.cua.policy.Redactor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Writes the run record for both discovery and replay into {@code evidence/<runId>/}:
 * <ul>
 *   <li>{@code run.jsonl} - one structured event per line (what the agent did and why);</li>
 *   <li>{@code steps/NN-*.png} - a screenshot per step (discovery) or on demand;</li>
 *   <li>{@code failure.png} + {@code failure-dom.html} - the richer signal on hard failure;</li>
 *   <li>{@code result.json} - the final structured result.</li>
 * </ul>
 * Every string written passes through the {@link Redactor} first.
 */
public final class EvidenceRecorder implements AutoCloseable {
    private final Path dir;
    private final String runId;
    private final Redactor redactor;
    private final Path jsonl;
    private final AtomicInteger seq = new AtomicInteger();

    public EvidenceRecorder(Path evidenceRoot, String runId, Redactor redactor) {
        this.dir = evidenceRoot.resolve(runId);
        this.runId = runId;
        this.redactor = redactor;
        try {
            Files.createDirectories(dir.resolve("steps"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        this.jsonl = dir.resolve("run.jsonl");
        event("run.start", Map.of("runId", runId));
    }

    public Path dir() { return dir; }

    public String runId() { return runId; }

    public synchronized void event(String type, Map<String, ?> fields) {
        var record = new java.util.LinkedHashMap<String, Object>();
        record.put("ts", Instant.now().toString());
        record.put("seq", seq.incrementAndGet());
        record.put("type", type);
        record.putAll(fields);
        String line = redactor.scrub(Json.writeCompact(record));
        try {
            Files.writeString(jsonl, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void screenshot(String label, byte[] png) {
        if (png == null) return;
        String safe = label.replaceAll("[^a-zA-Z0-9_\\-]", "_");
        Path p = dir.resolve("steps").resolve(String.format("%02d-%s.png", seq.get(), safe));
        write(p, png);
        event("screenshot", Map.of("file", dir.relativize(p).toString(), "label", label));
    }

    public void failure(String stepId, String expected, String observed, byte[] png, String domHtml) {
        if (png != null) write(dir.resolve("failure.png"), png);
        if (domHtml != null) writeText(dir.resolve("failure-dom.html"), domHtml);
        event("failure", Map.of(
                "step", stepId == null ? "" : stepId,
                "expected", expected == null ? "" : expected,
                "observed", observed == null ? "" : observed));
    }

    public void result(Object result) {
        writeText(dir.resolve("result.json"), Json.write(result));
        event("run.end", Map.of("resultFile", "result.json"));
    }

    public void writeText(Path p, String content) {
        try {
            Files.createDirectories(p.getParent());
            Files.writeString(p, redactor.scrub(content));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void write(Path p, byte[] bytes) {
        try {
            Files.createDirectories(p.getParent());
            Files.write(p, bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        // jsonl is flushed per write; nothing to do
    }
}
