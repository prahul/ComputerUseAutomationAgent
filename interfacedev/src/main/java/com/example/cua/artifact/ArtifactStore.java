package com.example.cua.artifact;

import com.example.cua.core.CuaException;
import com.example.cua.core.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * A filesystem-backed capability catalog. One JSON file per (name, version):
 * {@code <root>/<name>/<version>.json}. Simple on purpose - the interesting design is the
 * artifact shape, not the storage. A real deployment would swap this for an object store +
 * metadata DB behind the same interface.
 */
public final class ArtifactStore {
    private final Path root;

    public ArtifactStore(Path root) {
        this.root = root;
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Path save(Artifact artifact) {
        Path file = root.resolve(artifact.meta().name()).resolve(artifact.meta().version() + ".json");
        Json.writeFile(file, artifact);
        return file;
    }

    public Artifact load(Path file) {
        return Json.readFile(file, Artifact.class);
    }

    public Optional<Artifact> latest(String name) {
        Path dir = root.resolve(name);
        if (!Files.isDirectory(dir)) return Optional.empty();
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.toString().endsWith(".json"))
                    .max(Comparator.comparing(ArtifactStore::versionKey))
                    .map(this::load);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Artifact require(String name) {
        return latest(name).orElseThrow(() -> new CuaException("no capability named '" + name + "' in " + root));
    }

    public List<Artifact> list() {
        try (Stream<Path> dirs = Files.list(root)) {
            return dirs.filter(Files::isDirectory)
                    .flatMap(d -> {
                        try {
                            return Files.list(d).filter(p -> p.toString().endsWith(".json"));
                        } catch (IOException e) {
                            return Stream.empty();
                        }
                    })
                    .sorted()
                    .map(this::load)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String versionKey(Path p) {
        String v = p.getFileName().toString().replace(".json", "");
        String[] parts = v.split("\\.");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) sb.append(String.format("%08d", safeInt(part))).append('.');
        return sb.toString();
    }

    private static int safeInt(String s) {
        try { return Integer.parseInt(s.replaceAll("\\D", "")); } catch (NumberFormatException e) { return 0; }
    }
}
