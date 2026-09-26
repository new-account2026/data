package com.nju.cssci.论文;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Fixed-label metrics. Failed attempts remain in denominators and are never silently deleted. */
public final class PaperExperimentMetrics {
    public static final List<String> ACTION_LABELS = List.of("ASK", "RETRIEVE", "ANSWER", "REFUSE");
    public static final List<String> ROUTE_LABELS = List.of("DB", "WEB", "DB+WEB");

    public JSONObject decisionMetrics(List<PaperExperimentResult> all) {
        JSONObject root = new JSONObject(true);
        root.put("marker", marker(all));
        JSONObject variants = new JSONObject(true);
        for (Map.Entry<String, List<PaperExperimentResult>> entry : byVariant(all).entrySet()) {
            if (!List.of("PROPOSED_PLANNER", "DIRECT_LLM_DECISION").contains(entry.getKey())) continue;
            JSONObject value = new JSONObject(true);
            value.put("action", classification(entry.getValue(), ACTION_LABELS,
                    r -> r.goldAction, r -> r.predictedAction, r -> true));
            value.put("initialRoute", classification(entry.getValue(), ROUTE_LABELS,
                    r -> r.goldRoute, r -> r.predictedInitialRoute,
                    r -> "RETRIEVE".equals(r.goldAction) && ROUTE_LABELS.contains(r.goldRoute)));
            variants.put(entry.getKey(), value);
        }
        root.put("variants", variants);
        return root;
    }

    public JSONObject retrievalMetrics(List<PaperExperimentResult> all) {
        JSONObject root = new JSONObject(true);
        root.put("marker", marker(all));
        JSONObject variants = new JSONObject(true);
        for (Map.Entry<String, List<PaperExperimentResult>> entry : byVariant(all).entrySet()) {
            JSONObject value = new JSONObject(true);
            List<PaperExperimentResult> rows = entry.getValue();
            value.put("attempted", rows.size());
            value.put("successful", rows.stream().filter(r -> r.success).count());
            value.put("dbWouldCall", rows.stream().filter(r -> r.dbWouldBeCalled).count());
            value.put("webWouldCall", rows.stream().filter(r -> r.webWouldBeCalled).count());
            value.put("realDbCalls", rows.stream().filter(r -> r.dbActuallyCalled).count());
            value.put("realWebCalls", rows.stream().filter(r -> r.webActuallyCalled).count());
            value.put("routeChanged", rows.stream().filter(r -> r.routeChanged).count());
            value.put("routeTransitions", counts(rows, r -> r.routeTransition));
            value.put("webTriggerReasons", counts(rows, r -> r.webTriggerReason));
            value.put("evidenceRelevance", "NOT_EVALUATED");
            value.put("evidenceSufficiency", "NOT_EVALUATED");
            variants.put(entry.getKey(), value);
        }
        root.put("variants", variants);
        return root;
    }

    public JSONObject efficiencyMetrics(List<PaperExperimentResult> all) {
        JSONObject root = new JSONObject(true);
        root.put("marker", marker(all));
        JSONObject variants = new JSONObject(true);
        for (Map.Entry<String, List<PaperExperimentResult>> entry : byVariant(all).entrySet()) {
            List<PaperExperimentResult> rows = entry.getValue();
            JSONObject value = new JSONObject(true);
            value.put("queries", rows.size());
            value.put("totalLogicalExecutions", rows.size());
            long physical = rows.stream().filter(r -> !Boolean.TRUE.equals(r.diagnostics.get("pairedArtifactReuse")))
                    .mapToLong(r -> r.llmCalls.size()).sum();
            long successfulPhysical = rows.stream()
                    .filter(r -> !Boolean.TRUE.equals(r.diagnostics.get("pairedArtifactReuse")))
                    .flatMap(r -> r.llmCalls.stream())
                    .filter(v -> Boolean.TRUE.equals(v.getBoolean("success"))).count();
            long failedPhysical = rows.stream()
                    .filter(r -> !Boolean.TRUE.equals(r.diagnostics.get("pairedArtifactReuse")))
                    .flatMap(r -> r.llmCalls.stream())
                    .filter(v -> Boolean.FALSE.equals(v.getBoolean("success"))).count();
            long firstAttemptFailures = rows.stream().flatMap(r -> r.llmCalls.stream())
                    .filter(v -> Integer.valueOf(1).equals(v.getInteger("attempt")))
                    .filter(v -> Boolean.FALSE.equals(v.getBoolean("success"))).count();
            long retryRecovered = rows.stream().filter(r -> r.success
                    && r.llmCalls.stream().anyMatch(v -> Boolean.FALSE.equals(v.getBoolean("success")))
                    && r.llmCalls.stream().anyMatch(v -> Boolean.TRUE.equals(v.getBoolean("success")))).count();
            value.put("physicalDeepSeekCalls", physical);
            value.put("successfulPhysicalCalls", successfulPhysical);
            value.put("failedPhysicalAttempts", failedPhysical);
            value.put("structuredOutputFirstAttemptFailureCount", firstAttemptFailures);
            value.put("retryRecoveredCount", retryRecovered);
            value.put("finalLogicalFailureCount", rows.stream().filter(r -> !r.success).count());
            value.put("llmCallsPerQuery", mean(rows, r -> r.llmCallCount));
            value.put("promptTokensPerQuery", mean(rows, r -> r.promptTokens));
            value.put("completionTokensPerQuery", mean(rows, r -> r.completionTokens));
            value.put("totalTokensPerQuery", mean(rows, r -> r.totalTokens));
            value.put("tokenUsageCompleteRate", rate(rows,
                    r -> Boolean.TRUE.equals(r.tokenUsageComplete)));
            value.put("dbCallsPerQuery", mean(rows, r -> r.dbActuallyCalled ? 1 : 0));
            value.put("webCallsPerQuery", mean(rows, r -> r.webActuallyCalled ? 1 : 0));
            List<Long> latencies = rows.stream().map(r -> r.latencyMs).sorted().collect(Collectors.toList());
            value.put("meanLatencyMs", latencies.stream().mapToLong(Long::longValue).average().orElse(0D));
            value.put("medianLatencyMs", percentile(latencies, 0.50));
            value.put("p95LatencyMs", percentile(latencies, 0.95));
            value.put("failureRate", rate(rows, r -> !r.success));
            value.put("retryRate", rate(rows, r -> r.llmCalls.stream()
                    .anyMatch(v -> Integer.valueOf(2).equals(v.getInteger("attempt"))
                            || Integer.valueOf(3).equals(v.getInteger("attempt")))));
            value.put("fallbackRate", rate(rows, r -> r.fallbackUsed));
            value.put("estimatedCost", null);
            variants.put(entry.getKey(), value);
        }
        root.put("variants", variants);
        return root;
    }

    private JSONObject classification(List<PaperExperimentResult> rows, List<String> labels,
                                      Function<PaperExperimentResult, String> goldFn,
                                      Function<PaperExperimentResult, String> predictedFn,
                                      java.util.function.Predicate<PaperExperimentResult> include) {
        List<PaperExperimentResult> evaluated = rows.stream().filter(include).collect(Collectors.toList());
        int correct = 0;
        JSONObject perClass = new JSONObject(true);
        JSONObject matrix = new JSONObject(true);
        double f1Sum = 0D;
        for (String gold : labels) {
            JSONObject row = new JSONObject(true);
            for (String predicted : labels) row.put(predicted, 0);
            row.put("MISSING_OR_INVALID", 0);
            matrix.put(gold, row);
        }
        for (PaperExperimentResult item : evaluated) {
            String gold = goldFn.apply(item); String predicted = predictedFn.apply(item);
            if (gold != null && gold.equals(predicted)) correct++;
            if (gold != null && labels.contains(gold)) {
                JSONObject matrixRow = matrix.getJSONObject(gold);
                String column = predicted != null && labels.contains(predicted)
                        ? predicted : "MISSING_OR_INVALID";
                matrixRow.put(column, matrixRow.getIntValue(column) + 1);
            }
        }
        for (String label : labels) {
            long tp = evaluated.stream().filter(r -> label.equals(goldFn.apply(r)) && label.equals(predictedFn.apply(r))).count();
            long fp = evaluated.stream().filter(r -> !label.equals(goldFn.apply(r)) && label.equals(predictedFn.apply(r))).count();
            long fn = evaluated.stream().filter(r -> label.equals(goldFn.apply(r)) && !label.equals(predictedFn.apply(r))).count();
            double precision = tp + fp == 0 ? 0D : (double) tp / (tp + fp);
            double recall = tp + fn == 0 ? 0D : (double) tp / (tp + fn);
            double f1 = precision + recall == 0 ? 0D : 2D * precision * recall / (precision + recall);
            JSONObject score = new JSONObject(true);
            score.put("precision", precision); score.put("recall", recall); score.put("f1", f1);
            score.put("support", tp + fn); perClass.put(label, score); f1Sum += f1;
        }
        JSONObject result = new JSONObject(true);
        result.put("evaluated", evaluated.size());
        result.put("accuracy", evaluated.isEmpty() ? null : (double) correct / evaluated.size());
        result.put("macroF1", labels.isEmpty() ? null : f1Sum / labels.size());
        result.put("fixedLabels", labels); result.put("perClass", perClass); result.put("confusionMatrix", matrix);
        return result;
    }

    private static Map<String, List<PaperExperimentResult>> byVariant(List<PaperExperimentResult> all) {
        return all.stream().collect(Collectors.groupingBy(r -> r.variant, LinkedHashMap::new, Collectors.toList()));
    }
    private static String marker(List<PaperExperimentResult> rows) {
        return rows.isEmpty() || rows.get(0).marker == null ? PaperExperimentContext.FORMAL_MARKER : rows.get(0).marker;
    }
    private static JSONObject counts(List<PaperExperimentResult> rows, Function<PaperExperimentResult, String> fn) {
        JSONObject value = new JSONObject(true);
        for (PaperExperimentResult row : rows) {
            String key = fn.apply(row); if (key == null || key.isBlank()) continue;
            value.put(key, value.getIntValue(key) + 1);
        }
        return value;
    }
    private static double mean(List<PaperExperimentResult> rows, java.util.function.ToDoubleFunction<PaperExperimentResult> fn) {
        return rows.stream().mapToDouble(fn).average().orElse(0D);
    }
    private static double rate(List<PaperExperimentResult> rows, java.util.function.Predicate<PaperExperimentResult> predicate) {
        return rows.isEmpty() ? 0D : (double) rows.stream().filter(predicate).count() / rows.size();
    }
    private static Long percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) return null;
        int index = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }
}
