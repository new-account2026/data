package com.nju.cssci.论文;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/** Deterministic, post-execution retrieval/answer analysis. It has no provider or Variant dependency. */
public final class PaperRetrievalFormalAnalysis {
    private static final List<String> ROUTES = List.of("DB", "WEB", "DB+WEB");
    private static final List<String> ANSWER_VARIANTS = List.of("PROPOSED_FULL", "DIRECT_LLM_ANSWER",
            "SINGLE_STEP_RAG", "ABLATION_NO_RETRIEVAL_FEEDBACK", "ABLATION_NO_FRESHNESS_DYNAMIC",
            "ABLATION_NO_EVIDENCE_AUDIT");

    public JSONObject retrievalMetrics(List<PaperExperimentResult> all) {
        JSONObject root = root("retrieval-formal-metrics-v1");
        JSONObject variants = new JSONObject(true);
        for (Map.Entry<String, List<PaperExperimentResult>> entry : byVariant(all).entrySet()) {
            String name = entry.getKey();
            List<PaperExperimentResult> rows = entry.getValue();
            JSONObject value = new JSONObject(true);
            value.put("queries", rows.size());
            value.put("executionSuccessRate", rate(rows, r -> r.success));
            boolean routeApplicable = !"DIRECT_LLM_ANSWER".equals(name);
            value.put("initialRoute", routeApplicable ? classification(rows, r -> r.predictedInitialRoute) : null);
            value.put("finalRoute", routeApplicable ? classification(rows, r -> r.finalRoute) : null);
            value.put("initialToFinalRouteChangeRate", routeApplicable ? rate(rows, r -> r.routeChanged) : null);
            value.put("routeAdaptationRate", routeApplicable ? rate(rows, r -> r.routeChanged) : null);
            value.put("correctAdaptationRate", routeApplicable ? rate(rows,
                    r -> r.routeChanged && eq(r.goldRoute, r.finalRoute) && !eq(r.goldRoute, r.predictedInitialRoute)) : null);
            value.put("incorrectAdaptationRate", routeApplicable ? rate(rows,
                    r -> r.routeChanged && !eq(r.goldRoute, r.finalRoute)) : null);
            value.put("dbToolSuccessRate", conditionalRate(rows, r -> r.dbAttempted,
                    r -> Boolean.TRUE.equals(r.dbSucceeded)));
            value.put("dbZeroHitRate", conditionalRate(rows, r -> r.dbAttempted,
                    r -> r.dbHitCount != null && r.dbHitCount == 0));
            value.put("dbLowCoverageRate", conditionalRate(rows, r -> r.dbAttempted,
                    r -> r.coverageScore != null && threshold(r) != null && r.coverageScore < threshold(r)));
            value.put("webInvocationRate", rate(rows, r -> r.webWouldBeCalled));
            value.put("webSupplementTriggerRate", rate(rows, r -> r.webSupplementTriggered));
            value.put("webSuccessRate", conditionalRate(rows, r -> r.webWouldBeCalled,
                    r -> Boolean.TRUE.equals(r.webSucceeded)));
            value.put("tavilyCacheHitRate", conditionalRate(rows, r -> r.webWouldBeCalled,
                    r -> Boolean.TRUE.equals(r.webCacheHit)));
            value.put("logicalWebCalls", rows.stream().mapToLong(PaperRetrievalFormalAnalysis::logicalWebCalls).sum());
            value.put("realTavilyPhysicalCalls", rows.stream().mapToLong(r -> r.tavilyPhysicalAttempts).sum());
            value.put("tavilyPhysicalCallsPerQuery", mean(rows, r -> r.tavilyPhysicalAttempts));
            value.put("dbCallsPerQuery", mean(rows, PaperRetrievalFormalAnalysis::dbToolCalls));
            value.put("logicalWebCallsPerQuery", mean(rows, PaperRetrievalFormalAnalysis::logicalWebCalls));
            value.put("dbOnlyRetainedRate", conditionalRate(rows, r -> "DB".equals(r.predictedInitialRoute),
                    r -> "DB".equals(r.finalRoute)));
            value.put("dbToDbWebAdaptationRate", conditionalRate(rows,
                    r -> "DB".equals(r.predictedInitialRoute), r -> "DB+WEB".equals(r.finalRoute)));
            value.put("noResultRecoveryRate", conditionalRate(rows,
                    r -> r.dbAttempted && r.dbHitCount != null && r.dbHitCount == 0,
                    r -> Boolean.TRUE.equals(r.webSucceeded) && nonBlank(r.finalAnswer) && r.success));
            value.put("routeTransitions", counts(rows, r -> r.routeTransition));
            value.put("webTriggerReasons", counts(rows, r -> r.webTriggerReason));
            value.put("coverageTerminology", "retrieval availability proxy");
            value.put("evidenceRelevance", "NOT_EVALUATED");
            value.put("evidenceSufficiency", "NOT_EVALUATED");
            variants.put(name, value);
        }
        root.put("variants", variants);
        return root;
    }

    public JSONObject answerMetrics(List<PaperExperimentResult> all) {
        JSONObject root = root("answer-execution-metrics-v1");
        JSONObject variants = new JSONObject(true);
        for (Map.Entry<String, List<PaperExperimentResult>> entry : byVariant(all).entrySet()) {
            if (!ANSWER_VARIANTS.contains(entry.getKey())) continue;
            List<PaperExperimentResult> rows = entry.getValue();
            JSONObject value = new JSONObject(true);
            value.put("queries", rows.size());
            value.put("answerProducedRate", rate(rows, r -> nonBlank(r.finalAnswer)));
            value.put("emptyAnswerRate", rate(rows, r -> !nonBlank(r.finalAnswer)));
            value.put("meanAnswerLength", mean(rows, r -> r.finalAnswer == null ? 0 : r.finalAnswer.length()));
            value.put("meanSourceCount", mean(rows, r -> r.evidenceSources.size()));
            value.put("meanDbSourceCount", mean(rows, r -> countSources(r, "DB")));
            value.put("meanWebSourceCount", mean(rows, r -> countSources(r, "WEB")));
            value.put("meanEvidenceCount", mean(rows, r -> r.dbEvidence.size() + r.webEvidence.size()));
            value.put("auditExecutedRate", rate(rows, r -> r.auditExecuted));
            value.put("auditIssueCount", rows.stream().mapToLong(r -> r.auditIssues.size()).sum());
            value.put("auditCorrectionRate", conditionalRate(rows, r -> r.auditExecuted,
                    r -> Boolean.TRUE.equals(r.auditCorrected)));
            value.put("auditIssueTypes", auditIssueCounts(rows));
            value.put("groundedCompletionRate", "DIRECT_LLM_ANSWER".equals(entry.getKey()) ? null
                    : rate(rows, r -> requiredRetrievalCompleted(r) && nonBlank(r.finalAnswer) && r.success));
            value.put("groundedCompletionInterpretation", "automatic execution/completion proxy; not human task success");
            value.put("humanFactualCorrectness", "NOT_EVALUATED");
            variants.put(entry.getKey(), value);
        }
        root.put("variants", variants);
        return root;
    }

    public JSONObject ablationMetrics(List<PaperExperimentResult> all) {
        JSONObject root = root("ablation-metrics-v1");
        JSONObject comparisons = new JSONObject(true);
        comparisons.put("retrievalFeedback", pairSummary(all, "PROPOSED_FULL",
                "ABLATION_NO_RETRIEVAL_FEEDBACK", false));
        comparisons.put("freshnessDynamicAll", pairSummary(all, "PROPOSED_FULL",
                "ABLATION_NO_FRESHNESS_DYNAMIC", false));
        comparisons.put("freshnessDynamicFrozenFlaggedSubset", pairSummary(all, "PROPOSED_FULL",
                "ABLATION_NO_FRESHNESS_DYNAMIC", true));
        comparisons.put("evidenceAudit", auditComparison(all));
        root.put("comparisons", comparisons);
        return root;
    }

    public JSONObject pairedAnalysis(List<PaperExperimentResult> all) {
        JSONObject root = root("retrieval-paired-analysis-v1");
        JSONObject comparisons = new JSONObject(true);
        comparisons.put("PROPOSED_FULL_vs_SINGLE_STEP_RAG",
                paired(all, "PROPOSED_FULL", "SINGLE_STEP_RAG"));
        comparisons.put("PROPOSED_FULL_vs_ABLATION_NO_RETRIEVAL_FEEDBACK",
                paired(all, "PROPOSED_FULL", "ABLATION_NO_RETRIEVAL_FEEDBACK"));
        root.put("comparisons", comparisons);
        return root;
    }

    public void writeReport(Path path, JSONObject manifest, JSONObject retrieval,
                            JSONObject answers, JSONObject efficiency, JSONObject ablations,
                            JSONObject paired, JSONObject tavily, JSONObject integrity) throws Exception {
        StringBuilder out = new StringBuilder("# RETRIEVAL_FORMAL Report\n\n")
                .append("FORMAL PAPER EXPERIMENT\n\n")
                .append("## Run identity\n\n")
                .append("- Run ID: ").append(manifest.getString("runId")).append('\n')
                .append("- Dataset SHA-256: ").append(manifest.getString("datasetSha256")).append('\n')
                .append("- Frozen subset SHA-256: ").append(manifest.getString("subsetManifestSha256")).append('\n')
                .append("- Retrieval snapshot: ").append(manifest.getString("retrievalSnapshotId")).append('\n')
                .append("- Selected queries: ").append(manifest.getIntValue("selectedRows")).append("\n\n")
                .append("## Primary variants\n\n")
                .append(manifest.getJSONArray("variants")).append("\n\n")
                .append("ReAct and Human Evaluation were disabled for this run.\n\n")
                .append("## Retrieval and dynamic routing metrics\n\n```json\n")
                .append(JSON.toJSONString(retrieval, true)).append("\n```\n\n")
                .append("Coverage is reported only as a retrieval/evidence availability proxy.\n\n")
                .append("## Answer execution metrics\n\n```json\n")
                .append(JSON.toJSONString(answers, true)).append("\n```\n\n")
                .append("These are automatic execution/completion proxies, not human factual-correctness results.\n\n")
                .append("## Ablation metrics\n\n```json\n")
                .append(JSON.toJSONString(ablations, true)).append("\n```\n\n")
                .append("## Paired analysis\n\n```json\n")
                .append(JSON.toJSONString(paired, true)).append("\n```\n\n")
                .append("## Efficiency\n\n```json\n")
                .append(JSON.toJSONString(efficiency, true)).append("\n```\n\n")
                .append("## Tavily usage and safety\n\n```json\n")
                .append(JSON.toJSONString(tavily, true)).append("\n```\n\n")
                .append("## Postflight integrity\n\n```json\n")
                .append(JSON.toJSONString(integrity, true)).append("\n```\n");
        Files.writeString(path, out.toString(), StandardCharsets.UTF_8);
    }

    private JSONObject pairSummary(List<PaperExperimentResult> all, String first, String second,
                                   boolean frozenFlaggedOnly) {
        Map<String, PaperExperimentResult> left = index(all, first);
        Map<String, PaperExperimentResult> right = index(all, second);
        JSONObject value = new JSONObject(true);
        long pairs = 0, leftWeb = 0, rightWeb = 0, leftAnswers = 0, rightAnswers = 0;
        for (Map.Entry<String, PaperExperimentResult> entry : left.entrySet()) {
            PaperExperimentResult a = entry.getValue();
            PaperExperimentResult b = right.get(entry.getKey());
            if (b == null || (frozenFlaggedOnly && !freshnessFlagged(a))) continue;
            pairs++;
            if (a.webWouldBeCalled) leftWeb++;
            if (b.webWouldBeCalled) rightWeb++;
            if (nonBlank(a.finalAnswer)) leftAnswers++;
            if (nonBlank(b.finalAnswer)) rightAnswers++;
        }
        value.put("pairs", pairs);
        value.put("proposedWebSupplementRate", pairs == 0 ? null : (double) leftWeb / pairs);
        value.put("comparisonWebSupplementRate", pairs == 0 ? null : (double) rightWeb / pairs);
        value.put("proposedAnswerProducedRate", pairs == 0 ? null : (double) leftAnswers / pairs);
        value.put("comparisonAnswerProducedRate", pairs == 0 ? null : (double) rightAnswers / pairs);
        return value;
    }

    private JSONObject auditComparison(List<PaperExperimentResult> all) {
        Map<String, PaperExperimentResult> full = index(all, "PROPOSED_FULL");
        Map<String, PaperExperimentResult> noAudit = index(all, "ABLATION_NO_EVIDENCE_AUDIT");
        long pairs = full.keySet().stream().filter(noAudit::containsKey).count();
        JSONObject value = new JSONObject(true);
        value.put("pairs", pairs);
        value.put("fullAuditExecuted", full.values().stream().filter(r -> r.auditExecuted).count());
        value.put("fullAnswersChanged", full.values().stream().filter(r -> Boolean.TRUE.equals(r.answerChanged)).count());
        value.put("noAuditExecuted", noAudit.values().stream().filter(r -> r.auditExecuted).count());
        value.put("noAuditPreAuditEqualsFinal", noAudit.values().stream()
                .filter(r -> eq(r.preAuditAnswer, r.finalAnswer)).count());
        return value;
    }

    private JSONObject paired(List<PaperExperimentResult> all, String first, String second) {
        Map<String, PaperExperimentResult> left = index(all, first);
        Map<String, PaperExperimentResult> right = index(all, second);
        long bothCorrect = 0, leftOnly = 0, rightOnly = 0, bothWrong = 0;
        long bothSuccess = 0, leftSuccess = 0, rightSuccess = 0, bothFail = 0, pairs = 0;
        for (Map.Entry<String, PaperExperimentResult> entry : left.entrySet()) {
            PaperExperimentResult a = entry.getValue();
            PaperExperimentResult b = right.get(entry.getKey());
            if (b == null) continue;
            pairs++;
            boolean ac = eq(a.goldRoute, a.finalRoute), bc = eq(b.goldRoute, b.finalRoute);
            if (ac && bc) bothCorrect++; else if (ac) leftOnly++; else if (bc) rightOnly++; else bothWrong++;
            if (a.success && b.success) bothSuccess++;
            else if (a.success) leftSuccess++;
            else if (b.success) rightSuccess++;
            else bothFail++;
        }
        JSONObject value = new JSONObject(true);
        value.put("pairs", pairs);
        value.put("finalRoute", Map.of("bothCorrect", bothCorrect, "proposedOnlyCorrect", leftOnly,
                "baselineOnlyCorrect", rightOnly, "bothWrong", bothWrong));
        value.put("execution", Map.of("bothSuccess", bothSuccess, "proposedOnlySuccess", leftSuccess,
                "baselineOnlySuccess", rightSuccess, "bothFail", bothFail));
        return value;
    }

    private JSONObject classification(List<PaperExperimentResult> rows,
                                      Function<PaperExperimentResult, String> predicted) {
        JSONObject matrix = new JSONObject(true);
        JSONObject perClass = new JSONObject(true);
        for (String gold : ROUTES) {
            JSONObject line = new JSONObject(true);
            for (String label : ROUTES) line.put(label, 0);
            line.put("MISSING_OR_INVALID", 0);
            matrix.put(gold, line);
        }
        long correct = 0;
        double f1Sum = 0;
        for (PaperExperimentResult row : rows) {
            String guess = predicted.apply(row);
            if (eq(row.goldRoute, guess)) correct++;
            JSONObject line = matrix.getJSONObject(row.goldRoute);
            if (line != null) {
                String column = guess != null && ROUTES.contains(guess) ? guess : "MISSING_OR_INVALID";
                line.put(column, line.getIntValue(column) + 1);
            }
        }
        for (String label : ROUTES) {
            long tp = rows.stream().filter(r -> label.equals(r.goldRoute) && label.equals(predicted.apply(r))).count();
            long fp = rows.stream().filter(r -> !label.equals(r.goldRoute) && label.equals(predicted.apply(r))).count();
            long fn = rows.stream().filter(r -> label.equals(r.goldRoute) && !label.equals(predicted.apply(r))).count();
            double p = tp + fp == 0 ? 0 : (double) tp / (tp + fp);
            double recall = tp + fn == 0 ? 0 : (double) tp / (tp + fn);
            double f1 = p + recall == 0 ? 0 : 2 * p * recall / (p + recall);
            JSONObject score = new JSONObject(true);
            score.put("precision", p); score.put("recall", recall); score.put("f1", f1); score.put("support", tp + fn);
            perClass.put(label, score); f1Sum += f1;
        }
        JSONObject value = new JSONObject(true);
        value.put("evaluated", rows.size());
        value.put("accuracy", rows.isEmpty() ? null : (double) correct / rows.size());
        value.put("macroF1", f1Sum / ROUTES.size());
        value.put("fixedLabels", ROUTES);
        value.put("perClass", perClass);
        value.put("confusionMatrix", matrix);
        return value;
    }

    private static boolean requiredRetrievalCompleted(PaperExperimentResult row) {
        if (row.dbWouldBeCalled && !Boolean.TRUE.equals(row.dbSucceeded)) return false;
        return !row.webWouldBeCalled || Boolean.TRUE.equals(row.webSucceeded);
    }
    private static int countSources(PaperExperimentResult row, String source) {
        return (int) row.evidenceSources.stream().filter(v -> source.equals(v.getString("sourceType"))).count();
    }
    private static long logicalWebCalls(PaperExperimentResult row) {
        return row.toolCalls.stream().filter(v -> "web_search".equals(v.getString("toolName"))).count();
    }
    private static long dbToolCalls(PaperExperimentResult row) {
        return row.toolCalls.stream().filter(v -> !"web_search".equals(v.getString("toolName"))).count();
    }
    private static Double threshold(PaperExperimentResult row) {
        Object value = row.diagnostics.get("coverageThreshold");
        return value instanceof Number n ? n.doubleValue() : null;
    }
    private static boolean freshnessFlagged(PaperExperimentResult row) {
        if (row.reasonCodes != null && row.reasonCodes.stream().map(String::toLowerCase)
                .anyMatch(v -> v.contains("fresh") || v.contains("dynamic"))) return true;
        return row.webTriggerReason != null && (row.webTriggerReason.contains("fresh")
                || row.webTriggerReason.contains("dynamic"));
    }
    private static JSONObject auditIssueCounts(List<PaperExperimentResult> rows) {
        JSONObject value = new JSONObject(true);
        for (String type : List.of("UNSUPPORTED_FACT", "SOURCE_MISMATCH", "MISSING_UNCERTAINTY",
                "INVALID_CITATION", "OVERCLAIM", "CONTRADICTS_EVIDENCE")) value.put(type, 0);
        for (PaperExperimentResult row : rows) for (Object raw : row.auditIssues) {
            JSONObject issue = raw instanceof JSONObject ? (JSONObject) raw : (JSONObject) JSON.toJSON(raw);
            String type = issue == null ? null : issue.getString("type");
            if (type != null) value.put(type, value.getIntValue(type) + 1);
        }
        return value;
    }
    private static JSONObject counts(List<PaperExperimentResult> rows, Function<PaperExperimentResult, String> fn) {
        JSONObject value = new JSONObject(true);
        for (PaperExperimentResult row : rows) {
            String key = fn.apply(row);
            if (key != null && !key.isBlank()) value.put(key, value.getIntValue(key) + 1);
        }
        return value;
    }
    private static Map<String, List<PaperExperimentResult>> byVariant(List<PaperExperimentResult> all) {
        return all.stream().collect(Collectors.groupingBy(r -> r.variant, LinkedHashMap::new, Collectors.toList()));
    }
    private static Map<String, PaperExperimentResult> index(List<PaperExperimentResult> all, String variant) {
        return all.stream().filter(r -> variant.equals(r.variant)).collect(Collectors.toMap(r -> r.queryId,
                Function.identity(), (a, b) -> a, LinkedHashMap::new));
    }
    private static JSONObject root(String version) {
        JSONObject value = new JSONObject(true);
        value.put("marker", "FORMAL PAPER EXPERIMENT"); value.put("metricsVersion", version); return value;
    }
    private static double rate(List<PaperExperimentResult> rows, Predicate<PaperExperimentResult> test) {
        return rows.isEmpty() ? 0 : (double) rows.stream().filter(test).count() / rows.size();
    }
    private static Double conditionalRate(List<PaperExperimentResult> rows,
                                          Predicate<PaperExperimentResult> denominator,
                                          Predicate<PaperExperimentResult> numerator) {
        long base = rows.stream().filter(denominator).count();
        return base == 0 ? null : (double) rows.stream().filter(denominator).filter(numerator).count() / base;
    }
    private static double mean(List<PaperExperimentResult> rows,
                               java.util.function.ToDoubleFunction<PaperExperimentResult> fn) {
        return rows.stream().mapToDouble(fn).average().orElse(0);
    }
    private static boolean nonBlank(String value) { return value != null && !value.isBlank(); }
    private static boolean eq(Object a, Object b) { return a != null && a.equals(b); }
}
