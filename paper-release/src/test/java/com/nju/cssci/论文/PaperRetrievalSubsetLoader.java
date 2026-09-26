package com.nju.cssci.论文;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Loads a pre-frozen retrieval subset; it never samples or rewrites the source dataset. */
public final class PaperRetrievalSubsetLoader {
    public FrozenSubset load(PaperExperimentDatasetReader.Dataset dataset, Path manifestPath,
                             String expectedManifestSha256, Path allowedProjectRoot) throws Exception {
        Path projectRoot = allowedProjectRoot.toAbsolutePath().normalize();
        Path source = manifestPath.toAbsolutePath().normalize();
        require(source.startsWith(projectRoot), "Frozen subset manifest must be inside the project workspace");
        require(Files.isRegularFile(source), "Frozen subset manifest does not exist: " + source);
        String actualManifestHash = sha256(source);
        require(actualManifestHash.equalsIgnoreCase(expectedManifestSha256),
                "Frozen subset SHA-256 mismatch");

        JSONObject manifest = JSON.parseObject(Files.readString(source));
        require(manifest != null, "Frozen subset manifest is not a JSON object");
        require(PaperRetrievalFormalSpec.DATASET_SHA256.equalsIgnoreCase(dataset.sha256()),
                "Configured dataset SHA-256 does not match the frozen retrieval experiment specification");
        require(dataset.validCases().size() == PaperRetrievalFormalSpec.DATASET_VALID_ROWS,
                "Configured dataset must contain exactly 10,000 valid rows");
        require(dataset.sha256().equalsIgnoreCase(manifest.getString("datasetSha256")),
                "Frozen subset was not created from the configured dataset SHA-256");
        require(notBlank(manifest.getString("samplingStrategy")), "samplingStrategy is required");
        require(notBlank(manifest.getString("createdAt")), "createdAt is required");
        require(manifest.containsKey("randomSeed"), "randomSeed is required");
        JSONArray ids = manifest.getJSONArray("queryIds");
        require(ids != null && ids.size() == PaperRetrievalFormalSpec.SUBSET_SIZE,
                "Frozen retrieval subset must contain exactly 300 queryIds");
        require(manifest.getIntValue("sampleSize") == PaperRetrievalFormalSpec.SUBSET_SIZE,
                "Frozen subset sampleSize must be 300");
        require(manifest.getLongValue("randomSeed") == PaperRetrievalFormalSpec.RANDOM_SEED,
                "Frozen subset randomSeed mismatch");
        require(PaperRetrievalFormalSpec.RETRIEVAL_SNAPSHOT_ID.equals(
                        manifest.getString("retrievalSnapshotId")),
                "Frozen subset retrievalSnapshotId mismatch");

        Map<String, PaperExperimentCase> byId = new LinkedHashMap<>();
        for (PaperExperimentCase item : dataset.validCases()) {
            require(byId.putIfAbsent(item.queryId(), item) == null,
                    "Dataset contains duplicate queryId: " + item.queryId());
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        List<PaperExperimentCase> selected = new ArrayList<>();
        for (Object raw : ids) {
            String id = raw == null ? null : String.valueOf(raw).trim();
            require(notBlank(id), "Frozen subset contains a blank queryId");
            require(unique.add(id), "Frozen subset contains duplicate queryId: " + id);
            PaperExperimentCase item = byId.get(id);
            require(item != null, "Frozen subset queryId is absent from dataset: " + id);
            selected.add(item);
        }
        require(selected.size() < dataset.validCases().size(),
                "RETRIEVAL_FORMAL rejects the full dataset; a strict frozen subset is required");
        require(selected.size() == PaperRetrievalFormalSpec.SUBSET_SIZE,
                "RETRIEVAL_FORMAL requires exactly 300 frozen cases");
        require(selected.stream().allMatch(item -> "RETRIEVE".equals(item.goldAction())),
                "Frozen subset contains a non-RETRIEVE Gold Action");
        Map<String, Long> actualDistribution = new LinkedHashMap<>();
        for (String route : List.of("DB", "WEB", "DB+WEB")) {
            actualDistribution.put(route, selected.stream().filter(item -> route.equals(item.goldRoute())).count());
        }
        for (Map.Entry<String, Integer> expected : PaperRetrievalFormalSpec.ROUTE_DISTRIBUTION.entrySet()) {
            require(actualDistribution.getOrDefault(expected.getKey(), 0L) == expected.getValue().longValue(),
                    "Frozen subset Gold route distribution mismatch for " + expected.getKey());
        }
        JSONObject declaredDistribution = manifest.getJSONObject("routeDistribution");
        require(declaredDistribution != null, "routeDistribution is required");
        for (Map.Entry<String, Integer> expected : PaperRetrievalFormalSpec.ROUTE_DISTRIBUTION.entrySet()) {
            require(declaredDistribution.getIntValue(expected.getKey()) == expected.getValue(),
                    "Manifest routeDistribution mismatch for " + expected.getKey());
        }
        JSONArray selectedRows = manifest.getJSONArray("selectedRows");
        require(selectedRows != null && selectedRows.size() == selected.size(),
                "selectedRows Gold snapshot must contain exactly 300 rows");
        Map<String, PaperExperimentCase> selectedById = new LinkedHashMap<>();
        for (PaperExperimentCase item : selected) selectedById.put(item.queryId(), item);
        for (Object raw : selectedRows) {
            require(raw instanceof JSONObject, "selectedRows contains a non-object value");
            JSONObject row = (JSONObject) raw;
            PaperExperimentCase item = selectedById.get(row.getString("queryId"));
            require(item != null, "selectedRows contains an unknown queryId");
            require(item.goldAction().equals(row.getString("goldAction"))
                            && item.goldRoute().equals(row.getString("goldRoute")),
                    "Frozen subset Gold snapshot mismatch for " + item.queryId());
        }
        return new FrozenSubset(List.copyOf(selected), source, actualManifestHash,
                manifest.getString("samplingStrategy"), manifest.getLongValue("randomSeed"),
                manifest.getString("createdAt"), dataset.sha256(),
                manifest.getString("retrievalSnapshotId"), Map.copyOf(actualDistribution));
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static boolean notBlank(String value) { return value != null && !value.isBlank(); }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    public record FrozenSubset(List<PaperExperimentCase> cases, Path manifestPath,
                               String manifestSha256, String samplingStrategy,
                               long randomSeed, String createdAt, String datasetSha256,
                               String retrievalSnapshotId, Map<String, Long> routeDistribution) { }
}
