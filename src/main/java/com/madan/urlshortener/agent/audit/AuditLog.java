package com.madan.urlshortener.agent.audit;

import com.madan.urlshortener.agent.json.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Append-only, hash-chained audit log (one JSON object per line).
 *
 * Each record stores the SHA-256 of the previous record ("prev") and of itself ("hash"). Editing,
 * deleting or reordering any line breaks the chain, and {@link #verify(Path)} detects it.
 * Every tool call the agent attempts is recorded: arguments, guardrail decision, rule, outcome, latency.
 */
public class AuditLog {

    private static final String GENESIS = "0".repeat(64);

    private final Path file;
    private long seq;
    private String prevHash = GENESIS;

    public AuditLog(Path file) throws IOException {
        this.file = file;
        Files.deleteIfExists(file); // a fresh log per run keeps the demo readable
        Files.createFile(file);
    }

    public synchronized void record(String event, Map<String, Object> fields) {
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("seq", ++seq);
        rec.put("ts", Instant.now().toString());
        rec.put("event", event);
        rec.putAll(fields);
        rec.put("prev", prevHash);
        String hash = sha256(Json.write(rec));
        rec.put("hash", hash);
        try {
            Files.writeString(file, Json.write(rec) + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("audit log write failed - refusing to continue unaudited", e);
        }
        prevHash = hash;
    }

    public Path getFile() {
        return file;
    }

    /** Returns null if the chain is intact, otherwise a description of the first broken record. */
    public static String verify(Path file) throws IOException {
        String prev = GENESIS;
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            Map<String, Object> rec = Json.parseObject(lines.get(i));
            String claimed = (String) rec.remove("hash");
            if (!prev.equals(rec.get("prev"))) return "record " + (i + 1) + " does not link to the previous record";
            if (!sha256(Json.write(rec)).equals(claimed)) return "record " + (i + 1) + " was modified";
            prev = claimed;
        }
        return null;
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
