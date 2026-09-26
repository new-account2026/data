package com.nju.cssci.论文;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Post-run analysis only. Gold labels enter here after both variants have finished. */
public final class PaperDecisionFormalAnalysis {
    public static final String PROPOSED = "PROPOSED_PLANNER";
    public static final String DIRECT = "DIRECT_LLM_DECISION";

    public JSONObject confusionMatrices(JSONObject decisionMetrics) {
        JSONObject output = new JSONObject(true);
        output.put("marker", "FORMAL PAPER EXPERIMENT");
        output.put("actionLabelOrder", PaperExperimentMetrics.ACTION_LABELS);
        output.put("routeLabelOrder", PaperExperimentMetrics.ROUTE_LABELS);
        JSONObject variants = new JSONObject(true);
        JSONObject source = decisionMetrics.getJSONObject("variants");
        if (source != null) {
            for (String name : source.keySet()) {
                JSONObject metrics = source.getJSONObject(name);
                JSONObject value = new JSONObject(true);
                value.put("action", metrics.getJSONObject("action").getJSONObject("confusionMatrix"));
                value.put("initialRoute", metrics.getJSONObject("initialRoute").getJSONObject("confusionMatrix"));
                variants.put(name, value);
            }
        }
        output.put("variants", variants);
        return output;
    }

    public JSONObject pairwise(List<PaperExperimentResult> results) {
        Map<String, Pair> pairs = pairs(results);
        Counts action = new Counts();
        Counts route = new Counts();
        for (Pair pair : pairs.values()) {
            if (pair.proposed == null || pair.direct == null) continue;
            action.add(correctAction(pair.proposed), correctAction(pair.direct));
            if (routeEligible(pair.proposed)) {
                route.add(correctRoute(pair.proposed), correctRoute(pair.direct));
            }
        }
        JSONObject output = new JSONObject(true);
        output.put("marker", "FORMAL PAPER EXPERIMENT");
        output.put("proposedVariant", PROPOSED);
        output.put("directVariant", DIRECT);
        output.put("action", action.json());
        output.put("initialRoute", route.json());
        output.put("pairedQueryIds", pairs.size());
        return output;
    }

    public void writeErrorCases(Path path, List<PaperExperimentResult> results) throws Exception {
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
             CSVPrinter csv = new CSVPrinter(writer, CSVFormat.DEFAULT.withHeader(
                     "queryId", "query", "goldAction", "proposedAction", "directLlmAction",
                     "goldRoute", "proposedInitialRoute", "directLlmInitialRoute",
                     "proposedActionCorrect", "directActionCorrect", "proposedRouteCorrect",
                     "directRouteCorrect", "proposedReasonCodes", "proposedConfidence",
                     "proposedStatus", "directStatus", "proposedFailureStage", "directFailureStage",
                     "failureStatus"))) {
            for (Pair pair : pairs(results).values()) {
                PaperExperimentResult basis = pair.proposed != null ? pair.proposed : pair.direct;
                Boolean proposedAction = pair.proposed == null ? null : correctAction(pair.proposed);
                Boolean directAction = pair.direct == null ? null : correctAction(pair.direct);
                Boolean proposedRoute = pair.proposed == null || !routeEligible(pair.proposed)
                        ? null : correctRoute(pair.proposed);
                Boolean directRoute = pair.direct == null || !routeEligible(pair.direct)
                        ? null : correctRoute(pair.direct);
                boolean failed = pair.proposed == null || pair.direct == null
                        || !pair.proposed.success || !pair.direct.success;
                boolean error = !Boolean.TRUE.equals(proposedAction) || !Boolean.TRUE.equals(directAction)
                        || Boolean.FALSE.equals(proposedRoute) || Boolean.FALSE.equals(directRoute) || failed;
                if (!error) continue;
                csv.printRecord(basis.queryId, basis.query, basis.goldAction,
                        pair.proposed == null ? null : pair.proposed.predictedAction,
                        pair.direct == null ? null : pair.direct.predictedAction,
                        basis.goldRoute,
                        pair.proposed == null ? null : pair.proposed.predictedInitialRoute,
                        pair.direct == null ? null : pair.direct.predictedInitialRoute,
                        proposedAction, directAction, proposedRoute, directRoute,
                        pair.proposed == null ? null : JSON.toJSONString(pair.proposed.reasonCodes),
                        pair.proposed == null ? null : pair.proposed.confidence,
                        pair.proposed == null ? null : pair.proposed.status,
                        pair.direct == null ? null : pair.direct.status,
                        pair.proposed == null ? null : pair.proposed.failureStage,
                        pair.direct == null ? null : pair.direct.failureStage,
                        failureStatus(pair));
            }
        }
    }

    public void writeReport(Path path, JSONObject manifest, JSONObject decisionMetrics,
                            JSONObject confusion, JSONObject pairwise, JSONObject efficiency,
                            List<PaperExperimentCase> selected, List<PaperExperimentVariant> variants,
                            List<PaperExperimentResult> results) throws Exception {
        StringBuilder text = new StringBuilder("# DECISION_FORMAL Report\n\n")
                .append("FORMAL PAPER EXPERIMENT\n\n")
                .append("## 1. Run Identity\n\n")
                .append("- Experiment mode: DECISION_FORMAL\n")
                .append("- Dataset SHA-256: ").append(manifest.getString("datasetSha256")).append('\n')
                .append("- Git commit: ").append(manifest.getString("currentGitCommit")).append('\n')
                .append("- Branch: ").append(manifest.getString("branch")).append('\n')
                .append("- Dirty working tree: ").append(manifest.getBoolean("dirtyWorkingTree")).append('\n')
                .append("- Model: ").append(manifest.getString("model")).append('\n')
                .append("- Started: ").append(manifest.getString("startedAt")).append('\n')
                .append("- Finished: ").append(manifest.getString("finishedAt")).append('\n')
                .append("- Duration ms: ").append(manifest.getLong("durationMs")).append("\n\n")
                .append("## 2. Dataset\n\n")
                .append("- Valid queries: ").append(selected.size()).append('\n')
                .append("- Action distribution: ").append(distribution(selected, true)).append('\n')
                .append("- Retrieve route distribution: ").append(distribution(selected, false)).append("\n\n")
                .append("## 3. Variants\n\n");
        for (PaperExperimentVariant variant : variants) {
            text.append("- ").append(variant.name()).append(" — version `")
                    .append(variant.version()).append("`; readiness ").append(variant.readiness()).append('\n');
        }
        text.append("\n## 4. Action Results\n\n```json\n")
                .append(JSON.toJSONString(actionOnly(decisionMetrics), true)).append("\n```\n")
                .append("\n## 5. Initial Route Results\n\n```json\n")
                .append(JSON.toJSONString(routeOnly(decisionMetrics), true)).append("\n```\n")
                .append("\n## 6. Confusion Matrices\n\n```json\n")
                .append(JSON.toJSONString(confusion, true)).append("\n```\n")
                .append("\n## 7. Paired Comparison\n\n```json\n")
                .append(JSON.toJSONString(pairwise, true)).append("\n```\n")
                .append("\n## 8. Efficiency\n\n```json\n")
                .append(JSON.toJSONString(efficiency, true)).append("\n```\n")
                .append("\n## 9. Failures\n\n- Failed logical executions: ")
                .append(results.stream().filter(r -> !r.success).count()).append('\n')
                .append("\n## 10. Safety\n\n")
                .append("- Real DB retrieval calls = ").append(manifest.getIntValue("actualRealDbCalls")).append('\n')
                .append("- Real Tavily API calls = ").append(manifest.getIntValue("actualRealTavilyCalls")).append('\n')
                .append("- Tavily quota consumed by DECISION_FORMAL = 0\n")
                .append("- Formal Retrieval experiment executed = NO\n")
                .append("- Human Evaluation executed = NO\n");
        Files.writeString(path, text.toString(), StandardCharsets.UTF_8);
    }

    private Map<String, Pair> pairs(List<PaperExperimentResult> results) {
        Map<String, Pair> values = new LinkedHashMap<>();
        for (PaperExperimentResult result : results) {
            Pair pair = values.computeIfAbsent(result.queryId, ignored -> new Pair());
            if (PROPOSED.equals(result.variant)) pair.proposed = result;
            if (DIRECT.equals(result.variant)) pair.direct = result;
        }
        return values;
    }

    private JSONObject actionOnly(JSONObject metrics) { return metricOnly(metrics, "action"); }
    private JSONObject routeOnly(JSONObject metrics) { return metricOnly(metrics, "initialRoute"); }

    private JSONObject metricOnly(JSONObject metrics, String key) {
        JSONObject output = new JSONObject(true);
        JSONObject source = metrics.getJSONObject("variants");
        if (source != null) for (String variant : source.keySet()) {
            output.put(variant, source.getJSONObject(variant).getJSONObject(key));
        }
        return output;
    }

    private Map<String, Long> distribution(List<PaperExperimentCase> selected, boolean action) {
        Map<String, Long> values = new LinkedHashMap<>();
        List<String> labels = action ? PaperExperimentMetrics.ACTION_LABELS : PaperExperimentMetrics.ROUTE_LABELS;
        for (String label : labels) values.put(label, 0L);
        for (PaperExperimentCase item : selected) {
            if (!action && !"RETRIEVE".equals(item.goldAction())) continue;
            String label = action ? item.goldAction() : item.goldRoute();
            if (values.containsKey(label)) values.put(label, values.get(label) + 1);
        }
        return values;
    }

    private static boolean correctAction(PaperExperimentResult row) {
        return row.success && row.goldAction != null && row.goldAction.equals(row.predictedAction);
    }

    private static boolean routeEligible(PaperExperimentResult row) {
        return "RETRIEVE".equals(row.goldAction) && PaperExperimentMetrics.ROUTE_LABELS.contains(row.goldRoute);
    }

    private static boolean correctRoute(PaperExperimentResult row) {
        return row.success && routeEligible(row) && row.goldRoute.equals(row.predictedInitialRoute);
    }

    private static String failureStatus(Pair pair) {
        if (pair.proposed == null || pair.direct == null) return "MISSING_VARIANT_RESULT";
        if (!pair.proposed.success) return PROPOSED + ":" + pair.proposed.status;
        if (!pair.direct.success) return DIRECT + ":" + pair.direct.status;
        return null;
    }

    private static final class Pair {
        private PaperExperimentResult proposed;
        private PaperExperimentResult direct;
    }

    private static final class Counts {
        private long eligible;
        private long bothCorrect;
        private long proposedOnly;
        private long directOnly;
        private long bothWrong;

        void add(boolean proposed, boolean direct) {
            eligible++;
            if (proposed && direct) bothCorrect++;
            else if (proposed) proposedOnly++;
            else if (direct) directOnly++;
            else bothWrong++;
        }

        JSONObject json() {
            JSONObject value = new JSONObject(true);
            value.put("eligible", eligible);
            value.put("bothCorrect", bothCorrect);
            value.put("proposedOnlyCorrect", proposedOnly);
            value.put("directOnlyCorrect", directOnly);
            value.put("bothWrong", bothWrong);
            return value;
        }
    }
}
