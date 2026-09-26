package com.nju.cssci.论文.support;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.nju.cssci.entity.vo.WebSearchItemVO;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/** Experiment-only cache for exactly equivalent Tavily retrieval requests. */
public final class ExperimentTavilyCache {
    private final Path root;
    private final String snapshotId;
    private final ConcurrentHashMap<String, Object> requestLocks = new ConcurrentHashMap<>();

    public ExperimentTavilyCache(Path root) {
        this(root, "default");
    }

    public ExperimentTavilyCache(Path root, String snapshotId) {
        if (snapshotId == null || snapshotId.isBlank()) {
            throw new IllegalArgumentException("Tavily cache snapshotId is required");
        }
        this.root = root.toAbsolutePath().normalize();
        this.snapshotId = snapshotId;
    }

    public String key(String provider, String query, int maxResults) {
        String canonical = snapshotId + "\n"
                + safe(provider).toLowerCase(Locale.ROOT) + "\n"
                + normalizeQuery(query) + "\n" + maxResults;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("Unable to create Tavily cache key", error);
        }
    }

    public Object lockFor(String provider, String query, int maxResults) {
        return requestLocks.computeIfAbsent(key(provider, query, maxResults), ignored -> new Object());
    }

    public CacheHit load(String provider, String query, int maxResults) throws Exception {
        String key = key(provider, query, maxResults);
        Path path = root.resolve(key + ".json").normalize();
        requireInsideRoot(path);
        if (!Files.isRegularFile(path)) return null;
        JSONObject stored = JSON.parseObject(Files.readString(path, StandardCharsets.UTF_8));
        if (stored == null || !key.equals(stored.getString("cacheKey"))
                || !snapshotId.equals(stored.getString("retrievalSnapshotId"))
                || !normalizeQuery(query).equals(stored.getString("normalizedWebQuery"))
                || maxResults != stored.getIntValue("maxResults")) {
            throw new IllegalStateException("Tavily cache metadata mismatch/corruption: " + path);
        }
        JSONArray rows = stored.getJSONArray("normalizedResults");
        List<WebSearchItemVO> results = rows == null ? List.of() : rows.toJavaList(WebSearchItemVO.class);
        String expectedResponseHash = stored.getString("normalizedResponseSha256");
        String actualResponseHash = responseHash(results);
        if (expectedResponseHash == null || !expectedResponseHash.equals(actualResponseHash)) {
            throw new IllegalStateException("Tavily cache response hash mismatch/corruption: " + path);
        }
        return new CacheHit(key, path, results, stored);
    }

    public Path save(String provider, String query, int maxResults, List<WebSearchItemVO> results) throws Exception {
        Files.createDirectories(root);
        String key = key(provider, query, maxResults);
        Path target = root.resolve(key + ".json").normalize();
        requireInsideRoot(target);
        JSONObject value = new JSONObject(true);
        value.put("cacheVersion", "tavily-retrieval-cache-v1");
        value.put("retrievalSnapshotId", snapshotId);
        value.put("cacheKey", key);
        value.put("provider", provider);
        value.put("normalizedWebQuery", normalizeQuery(query));
        value.put("originalWebQuery", query);
        value.put("maxResults", maxResults);
        value.put("timestamp", Instant.now().toString());
        value.put("rawResponse", results);
        value.put("rawProviderHttpBodyAvailable", false);
        value.put("rawResponseScope", "SearchService boundary response; provider HTTP body is not exposed by frozen production API");
        value.put("normalizedResults", results);
        value.put("normalizedResponseSha256", responseHash(results));
        Path temporary = root.resolve(key + ".tmp").normalize();
        requireInsideRoot(temporary);
        Files.writeString(temporary, JSON.toJSONString(value, true), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    private void requireInsideRoot(Path path) {
        if (!path.startsWith(root)) throw new IllegalArgumentException("Cache path escapes cache root");
    }

    public static String normalizeQuery(String query) {
        return safe(query).trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String safe(String value) { return value == null ? "" : value; }

    public String snapshotId() { return snapshotId; }
    public Path root() { return root; }

    public JSONObject manifest() {
        JSONObject value = new JSONObject(true);
        value.put("cacheVersion", "tavily-retrieval-cache-v1");
        value.put("snapshotId", snapshotId);
        value.put("cacheRoot", root.toString());
        JSONArray files = new JSONArray();
        try {
            if (Files.isDirectory(root)) {
                try (java.util.stream.Stream<Path> stream = Files.list(root)) {
                    for (Path path : stream.filter(Files::isRegularFile)
                            .filter(v -> v.getFileName().toString().endsWith(".json")).sorted().toList()) {
                        JSONObject row = new JSONObject(true);
                        row.put("file", path.getFileName().toString());
                        row.put("size", Files.size(path));
                        row.put("sha256", fileHash(path));
                        row.put("lastModified", Files.getLastModifiedTime(path).toInstant().toString());
                        files.add(row);
                    }
                }
            }
            value.put("scanError", null);
        } catch (Exception error) {
            value.put("scanError", error.getClass().getName() + ": " + error.getMessage());
        }
        value.put("entryCount", files.size());
        value.put("entries", files);
        return value;
    }

    public static String responseHash(List<WebSearchItemVO> results) {
        try {
            String json = JSON.toJSONString(results == null ? List.of() : results);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("Unable to hash Tavily response", error);
        }
    }

    private static String fileHash(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (java.io.InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public record CacheHit(String key, Path path, List<WebSearchItemVO> results, JSONObject metadata) { }
}
