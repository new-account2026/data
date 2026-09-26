package com.nju.cssci.论文;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** One-time, offline, explicit-opt-in freezer. It never constructs a provider or executes a variant. */
public final class PaperRetrievalFormalSubsetFreezer {
    @Test
    void freezeConfiguredSubsetOnce() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("paper.retrieval.freezeSubset"),
                "Subset freezing requires -Dpaper.retrieval.freezeSubset=true");
        if (Files.exists(PaperRetrievalFormalSpec.SUBSET_MANIFEST)) {
            throw new IllegalStateException("Frozen subset manifest already exists and will not be overwritten: "
                    + PaperRetrievalFormalSpec.SUBSET_MANIFEST);
        }

        PaperExperimentDatasetReader.Dataset dataset = new PaperExperimentDatasetReader()
                .read(PaperRetrievalFormalSpec.DATASET);
        require(PaperRetrievalFormalSpec.DATASET_SHA256.equalsIgnoreCase(dataset.sha256()),
                "Dataset SHA-256 mismatch");
        require(dataset.validCases().size() == PaperRetrievalFormalSpec.DATASET_VALID_ROWS,
                "Dataset valid-row count mismatch");

        Map<String, List<PaperExperimentCase>> buckets = new LinkedHashMap<>();
        for (String route : List.of("DB", "WEB", "DB+WEB")) buckets.put(route, new ArrayList<>());
        for (PaperExperimentCase item : dataset.validCases()) {
            if (!"RETRIEVE".equals(item.goldAction())) continue;
            List<PaperExperimentCase> bucket = buckets.get(item.goldRoute());
            if (bucket != null) bucket.add(item);
        }

        List<PaperExperimentCase> selected = new ArrayList<>();
        Random random = new Random(PaperRetrievalFormalSpec.RANDOM_SEED);
        for (String route : List.of("DB", "WEB", "DB+WEB")) {
            List<PaperExperimentCase> bucket = buckets.get(route);
            Collections.shuffle(bucket, random);
            int required = PaperRetrievalFormalSpec.ROUTE_DISTRIBUTION.get(route);
            require(bucket.size() >= required, "Insufficient Gold route rows for " + route);
            selected.addAll(bucket.subList(0, required));
        }
        Collections.shuffle(selected, random);
        require(selected.size() == PaperRetrievalFormalSpec.SUBSET_SIZE, "Subset size mismatch");
        require(selected.stream().map(PaperExperimentCase::queryId).distinct().count() == selected.size(),
                "Duplicate query IDs selected");

        JSONObject manifest = new JSONObject(true);
        manifest.put("manifestVersion", "retrieval-formal-subset-v1");
        manifest.put("datasetPath", dataset.path().toString());
        manifest.put("datasetSha256", dataset.sha256());
        manifest.put("datasetValidRows", dataset.validCases().size());
        manifest.put("samplingStrategy", PaperRetrievalFormalSpec.SAMPLING_STRATEGY);
        manifest.put("randomSeed", PaperRetrievalFormalSpec.RANDOM_SEED);
        manifest.put("sampleSize", PaperRetrievalFormalSpec.SUBSET_SIZE);
        manifest.put("routeDistribution", PaperRetrievalFormalSpec.ROUTE_DISTRIBUTION);
        manifest.put("goldActionDistribution", Map.of("RETRIEVE", PaperRetrievalFormalSpec.SUBSET_SIZE));
        manifest.put("retrievalSnapshotId", PaperRetrievalFormalSpec.RETRIEVAL_SNAPSHOT_ID);
        manifest.put("createdAt", Instant.now().toString());
        manifest.put("queryIds", selected.stream().map(PaperExperimentCase::queryId).toList());
        manifest.put("selectedRows", selected.stream().map(item -> {
            JSONObject row = new JSONObject(true);
            row.put("queryId", item.queryId());
            row.put("goldAction", item.goldAction());
            row.put("goldRoute", item.goldRoute());
            return row;
        }).toList());

        Files.writeString(PaperRetrievalFormalSpec.SUBSET_MANIFEST,
                JSON.toJSONString(manifest, true) + System.lineSeparator(), StandardCharsets.UTF_8);
        System.out.println("Frozen retrieval subset written: " + PaperRetrievalFormalSpec.SUBSET_MANIFEST);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
