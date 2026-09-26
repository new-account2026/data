package com.nju.cssci.论文;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.Collections;

/** Immutable experiment-layer specification for the frozen 300-query retrieval run. */
public final class PaperRetrievalFormalSpec {
    public static final Path PROJECT_ROOT = Path.of("D:\\Java Projects\\cssci").toAbsolutePath().normalize();
    public static final Path DATASET = PROJECT_ROOT.resolve(
            "src/test/java/com/nju/cssci/论文/scholar_dataset_10000_v3.xlsx");
    public static final Path SUBSET_MANIFEST = PROJECT_ROOT.resolve(
            "src/test/java/com/nju/cssci/论文/retrieval_formal_subset_manifest.json");

    public static final String DATASET_SHA256 =
            "73ba909ae031be1be7d3ac9554960438c4f8087728e126a5b03596f4b2b4ec78";
    public static final String SUBSET_MANIFEST_SHA256 =
            "dbe4d651570dfd46b4ef18331c702b4bc0e08b22a455750c04630ee846ef338d";
    public static final int DATASET_VALID_ROWS = 10_000;
    public static final int SUBSET_SIZE = 300;
    public static final long RANDOM_SEED = 20260904L;
    public static final Map<String, Integer> ROUTE_DISTRIBUTION = routeDistribution();
    public static final String SAMPLING_STRATEGY =
            "Stratified random sample from Gold Action=RETRIEVE; DB=105, WEB=84, DB+WEB=111";
    public static final String RETRIEVAL_SNAPSHOT_ID = "retrieval_formal_300_20260904_v1";
    public static final int TAVILY_PHYSICAL_CALL_BUDGET = 700;

    public static final List<String> PRIMARY_VARIANTS_IN_ORDER = List.of(
            "PROPOSED_FULL",
            "DIRECT_LLM_ANSWER",
            "SINGLE_STEP_RAG",
            "ONE_SHOT_ROUTER",
            "ABLATION_NO_RETRIEVAL_FEEDBACK",
            "ABLATION_NO_FRESHNESS_DYNAMIC",
            "ABLATION_NO_EVIDENCE_AUDIT");
    public static final Set<String> PRIMARY_VARIANTS = Set.copyOf(PRIMARY_VARIANTS_IN_ORDER);

    private PaperRetrievalFormalSpec() { }

    private static Map<String, Integer> routeDistribution() {
        LinkedHashMap<String, Integer> values = new LinkedHashMap<>();
        values.put("DB", 105); values.put("WEB", 84); values.put("DB+WEB", 111);
        return Collections.unmodifiableMap(values);
    }
}
