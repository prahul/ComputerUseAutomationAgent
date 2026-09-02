package com.example.cua.core;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

public final class Ids {
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss").withZone(ZoneOffset.UTC);

    private Ids() {}

    public static String runId(String kind) {
        return kind + "-" + STAMP.format(Instant.now()) + "-" + shortUuid();
    }

    public static String shortUuid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    public static String uuid() {
        return UUID.randomUUID().toString();
    }
}
