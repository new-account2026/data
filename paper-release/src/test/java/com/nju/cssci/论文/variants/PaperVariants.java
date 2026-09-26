package com.nju.cssci.论文.variants;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.nju.cssci.entity.vo.ChatToolCallVO;
import com.nju.cssci.论文.PaperExperimentCase;
import com.nju.cssci.论文.PaperExperimentContext;
import com.nju.cssci.论文.PaperExperimentResult;
import com.nju.cssci.论文.PaperExperimentVariant;
import com.nju.cssci.论文.PaperExperimentVariant.Task;
import com.nju.cssci.论文.support.ExperimentDeepSeekClient;
import com.nju.cssci.论文.support.FrozenExperimentHarness;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Factory and implementations for experiment-layer methods. */
public final class PaperVariants {
    private PaperVariants() { }

    public static List<PaperExperimentVariant> all(int maxReactSteps) {
        return List.of(
                new ProposedPlanner(),
                new DirectDecision(),
                new ProposedFull(),
                new DirectAnswer(),
                new Pipeline("SINGLE_STEP_RAG", "baseline-rag-v1", "C. End-to-End System Experiment",
                        "Frozen initial planner -> execute initial source(s) once -> grounded answer; no feedback or audit",
                        FrozenExperimentHarness.RetrievalPolicy.INITIAL_ONLY, true, false,
                        Set.of(Task.RETRIEVAL_DYNAMIC_ROUTING, Task.END_TO_END)),
                new Pipeline("ONE_SHOT_ROUTER", "one-shot-router-v1", "B. Retrieval / Dynamic Routing Experiment",
                        "Shared Frozen initial planner decision -> execute initial route; no result-based route adaptation",
                        FrozenExperimentHarness.RetrievalPolicy.INITIAL_ONLY, false, false,
                        Set.of(Task.RETRIEVAL_DYNAMIC_ROUTING)),
                new ReactStyle(maxReactSteps),
                new Pipeline("ABLATION_NO_RETRIEVAL_FEEDBACK", "no-feedback-v1", "D. Ablation Experiment",
                        "Full upstream planner/parser and downstream generation/audit; disable result-driven source adaptation only",
                        FrozenExperimentHarness.RetrievalPolicy.NO_FEEDBACK, true, true,
                        Set.of(Task.RETRIEVAL_DYNAMIC_ROUTING, Task.END_TO_END, Task.ABLATION)),
                new Pipeline("ABLATION_NO_FRESHNESS_DYNAMIC", "no-freshness-dynamic-v1", "D. Ablation Experiment",
                        "Keep initial planner and low-coverage feedback; disable only freshness/dynamic supplementation",
                        FrozenExperimentHarness.RetrievalPolicy.NO_FRESHNESS_DYNAMIC, true, true,
                        Set.of(Task.RETRIEVAL_DYNAMIC_ROUTING, Task.END_TO_END, Task.ABLATION)),
                new Pipeline("ABLATION_NO_EVIDENCE_AUDIT", "no-evidence-audit-v1", "D. Ablation Experiment",
                        "Full adaptive retrieval and grounded generation; pre-audit answer is final; no audit/correction",
                        FrozenExperimentHarness.RetrievalPolicy.NO_AUDIT, true, false,
                        Set.of(Task.END_TO_END, Task.ABLATION)),
                new NoStatefulClarification()
        );
    }

    private abstract static class Base implements PaperExperimentVariant {
        private final String name;
        private final String version;
        private final String category;
        private final String definition;
        private final Readiness readiness;
        private final Set<Task> tasks;

        Base(String name, String version, String category, String definition,
             Readiness readiness, Set<Task> tasks) {
            this.name = name; this.version = version; this.category = category;
            this.definition = definition; this.readiness = readiness; this.tasks = Set.copyOf(tasks);
        }
        @Override public String name() { return name; }
        @Override public String version() { return version; }
        @Override public String category() { return category; }
        @Override public String formalDefinition() { return definition; }
        @Override public Readiness readiness() { return readiness; }
        @Override public Set<Task> supportedTasks() { return tasks; }
        PaperExperimentResult base(PaperExperimentCase item, PaperExperimentContext context) {
            return PaperExperimentResult.base(item, this, context.marker());
        }
        PaperExperimentResult done(PaperExperimentResult value, long start) {
            value.success = true;
            if (value.status == null || "STARTED".equals(value.status)) value.status = "COMPLETED";
            value.latencyMs = (System.nanoTime() - start) / 1_000_000L;
            return value;
        }
    }

    private static final class ProposedPlanner extends Base {
        ProposedPlanner() {
            super("PROPOSED_PLANNER", "frozen-planner-observation-v1", "A. Decision Experiment",
                    "Frozen decideRouteByAi followed by the frozen clarification/query/action normalization chain",
                    Readiness.FORMAL_READY, Set.of(Task.DECISION));
        }
        @Override public PaperExperimentResult execute(PaperExperimentCase item, PaperExperimentContext context) throws Exception {
            long start = System.nanoTime(); PaperExperimentResult result = base(item, context);
            FrozenExperimentHarness.PlannerObservation plan = context.sharedPlanner(item);
            context.frozen().applyPlanner(result, plan, false);
            result.dbWouldBeCalled = "RETRIEVE".equals(plan.action()) && needsDb(plan.initialRoute());
            result.webWouldBeCalled = "RETRIEVE".equals(plan.action()) && needsWeb(plan.initialRoute());
            result.finalRoute = null; result.finalRouteProvenance = "NOT_EXECUTED_DECISION_EXPERIMENT";
            return done(result, start);
        }
    }

    private static final class DirectDecision extends Base {
        DirectDecision() {
            super("DIRECT_LLM_DECISION", ExperimentDeepSeekClient.DIRECT_DECISION_PROMPT_VERSION,
                    "A. Decision Experiment",
                    "One same-model zero-shot structured Action/Route classification; no tools, Gold, Router prompt, feedback, or correction",
                    Readiness.FORMAL_READY, Set.of(Task.DECISION));
        }
        @Override public PaperExperimentResult execute(PaperExperimentCase item, PaperExperimentContext context) throws Exception {
            long start = System.nanoTime(); PaperExperimentResult result = base(item, context);
            ExperimentDeepSeekClient.CallResult call = context.baselineLlm().directDecision(item.query());
            FrozenExperimentHarness.addLogicalCalls(result, call.attempts(), false);
            JSONObject decision = parseObject(call.content());
            String action = upper(decision.getString("action"));
            String route = upper(decision.getString("route"));
            if (!Set.of("ASK", "RETRIEVE", "ANSWER", "REFUSE").contains(action))
                throw new IllegalStateException("Direct decision returned invalid action: " + action);
            if (!"RETRIEVE".equals(action)) route = "NONE";
            if (!Set.of("DB", "WEB", "DB+WEB", "NONE").contains(route))
                throw new IllegalStateException("Direct decision returned invalid route: " + route);
            result.predictedAction = action; result.predictedInitialRoute = route;
            result.predictedTool = null; result.finalRoute = null;
            result.finalRouteProvenance = "NO_TOOL_EXECUTION";
            result.diagnostics.put("promptVersion", call.promptVersion());
            result.diagnostics.put("promptTemplateHash", call.promptHash());
            return done(result, start);
        }
    }

    private static final class DirectAnswer extends Base {
        DirectAnswer() {
            super("DIRECT_LLM_ANSWER", ExperimentDeepSeekClient.DIRECT_ANSWER_PROMPT_VERSION,
                    "C. End-to-End System Experiment", "Query -> same DeepSeek model -> answer; no Router, retrieval, RAG, or audit",
                    Readiness.FORMAL_READY, Set.of(Task.END_TO_END, Task.HUMAN_EVAL_EXPORT));
        }
        @Override public PaperExperimentResult execute(PaperExperimentCase item, PaperExperimentContext context) throws Exception {
            long start = System.nanoTime(); PaperExperimentResult result = base(item, context);
            ExperimentDeepSeekClient.CallResult call = context.baselineLlm().directAnswer(item.query());
            FrozenExperimentHarness.addLogicalCalls(result, call.attempts(), false);
            result.predictedAction = null; result.predictedInitialRoute = null; result.predictedTool = null;
            result.finalAnswer = call.content(); result.finalRoute = null; result.finalRouteProvenance = "NOT_APPLICABLE";
            result.auditExecuted = false;
            result.diagnostics.put("decisionFields", "N/A");
            result.diagnostics.put("promptVersion", call.promptVersion());
            result.diagnostics.put("promptTemplateHash", call.promptHash());
            return done(result, start);
        }
    }

    private static final class ProposedFull extends Pipeline {
        ProposedFull() {
            super("PROPOSED_FULL", "frozen-full-experiment-adapter-v1", "C. End-to-End System Experiment",
                    "Frozen planner/parser/feedback/generation/audit methods with retrieval through the common experiment boundary",
                    FrozenExperimentHarness.RetrievalPolicy.FULL_ADAPTIVE, true, true,
                    Set.of(Task.RETRIEVAL_DYNAMIC_ROUTING, Task.END_TO_END, Task.HUMAN_EVAL_EXPORT));
        }
    }

    private static class Pipeline extends Base {
        private final FrozenExperimentHarness.RetrievalPolicy policy;
        private final boolean generate;
        private final boolean audit;
        Pipeline(String name, String version, String category, String definition,
                 FrozenExperimentHarness.RetrievalPolicy policy, boolean generate, boolean audit, Set<Task> tasks) {
            this(name, version, category, definition, policy, generate, audit, Readiness.FORMAL_READY, tasks);
        }
        Pipeline(String name, String version, String category, String definition,
                 FrozenExperimentHarness.RetrievalPolicy policy, boolean generate, boolean audit,
                 Readiness readiness, Set<Task> tasks) {
            super(name, version, category, definition, readiness, tasks);
            this.policy = policy; this.generate = generate; this.audit = audit;
        }
        @Override public PaperExperimentResult execute(PaperExperimentCase item, PaperExperimentContext context) throws Exception {
            long start = System.nanoTime(); PaperExperimentResult result = base(item, context);
            FrozenExperimentHarness.PlannerObservation plan = context.sharedPlanner(item);
            context.frozen().executeFrozenPipeline(item, result, plan, context.tools(), policy, generate, audit);
            result.diagnostics.put("retrievalPolicy", policy.name());
            return done(result, start);
        }
    }

    private static final class NoStatefulClarification extends Pipeline {
        NoStatefulClarification() {
            super("ABLATION_NO_STATEFUL_CLARIFICATION", "stateless-clarification-v1", "D. Ablation Experiment",
                    "Each turn gets isolated state; same Frozen planner/model/retrieval; no cross-turn pending/resolved/paging state",
                    FrozenExperimentHarness.RetrievalPolicy.FULL_ADAPTIVE, true, true,
                    Readiness.FORMAL_READY_AWAITING_DATASET,
                    Set.of(Task.END_TO_END, Task.ABLATION));
        }
    }

    private static final class ReactStyle extends Base {
        private final int maxSteps;
        ReactStyle(int maxSteps) {
            super("REACT_STYLE", ExperimentDeepSeekClient.REACT_PROMPT_VERSION,
                    "C. End-to-End System Experiment",
                    "Iterative same-model tool agent: decide tool -> common-tool observation -> decide next step, bounded by max steps",
                    Readiness.FORMAL_READY, Set.of(Task.END_TO_END, Task.HUMAN_EVAL_EXPORT));
            this.maxSteps = maxSteps;
        }
        @Override public PaperExperimentResult execute(PaperExperimentCase item, PaperExperimentContext context) throws Exception {
            long start = System.nanoTime(); PaperExperimentResult result = base(item, context);
            List<JSONObject> observations = new ArrayList<>();
            LinkedHashSet<String> routeSources = new LinkedHashSet<>();
            for (int step = 1; step <= maxSteps; step++) {
                ExperimentDeepSeekClient.CallResult call = context.baselineLlm().react(item.query(), observations);
                FrozenExperimentHarness.addLogicalCalls(result, call.attempts(), false);
                JSONObject decision;
                try {
                    decision = parseObject(call.content());
                } catch (RuntimeException unstructuredFinal) {
                    if (call.content() == null || call.content().isBlank()) throw unstructuredFinal;
                    result.finalAnswer = call.content();
                    result.diagnostics.put("unstructuredFinalFallback", true);
                    result.diagnostics.put("unstructuredFinalStep", step);
                    result.status = "COMPLETED_UNSTRUCTURED_FINAL";
                    break;
                }
                String action = upper(decision.getString("action"));
                if ("FINAL".equals(action)) {
                    result.finalAnswer = decision.getString("answer");
                    result.status = "COMPLETED";
                    break;
                }
                if (!"TOOL".equals(action)) throw new IllegalStateException("Invalid ReAct action: " + action);
                String tool = decision.getString("tool");
                if (!Set.of("article_search", "field_author_search", "score_top10", "web_search").contains(tool))
                    throw new IllegalStateException("Invalid ReAct tool: " + tool);
                String input = decision.getString("toolInput");
                ChatToolCallVO toolCall = context.tools().execute(tool,
                        input == null || input.isBlank() ? item.query() : input, null);
                JSONObject observation = new JSONObject(true);
                observation.put("step", step); observation.put("tool", tool);
                observation.put("status", toolCall.getStatus()); observation.put("hitCount", toolCall.getHitCount());
                observation.put("observation", toolCall.getResponseData()); observations.add(observation);
                JSONObject exported = JSON.parseObject(observation.toJSONString());
                exported.put("realProviderCalled", "web_search".equals(tool)
                        ? context.tools().realWebEnabled() : context.tools().realDbEnabled());
                result.toolCalls.add(exported);
                if ("web_search".equals(tool)) { result.webWouldBeCalled = true; routeSources.add("WEB"); }
                else { result.dbWouldBeCalled = true; routeSources.add("DB"); }
            }
            if (result.finalAnswer == null) {
                result.status = "MAX_TOOL_STEPS_REACHED";
                result.failureStage = "REACT_LOOP";
                result.fallbackUsed = true;
                result.finalAnswer = "MAX_TOOL_STEPS_REACHED";
            }
            result.predictedAction = null; result.predictedInitialRoute = null; result.predictedTool = null;
            result.finalRoute = routeSources.size() == 2 ? "DB+WEB"
                    : (routeSources.isEmpty() ? "NONE" : routeSources.iterator().next());
            result.finalRouteProvenance = context.validationOnly()
                    ? "MOCK_EXECUTION_DERIVED / NOT FORMAL EFFECT RESULT" : "ACTUAL_EXECUTION";
            result.dbActuallyCalled = context.tools().realDbEnabled() && result.dbWouldBeCalled;
            result.webActuallyCalled = context.tools().realWebEnabled() && result.webWouldBeCalled;
            result.auditExecuted = false;
            result.diagnostics.put("maxToolSteps", maxSteps);
            result.diagnostics.put("toolSteps", observations.size());
            result.diagnostics.put("promptVersion", ExperimentDeepSeekClient.REACT_PROMPT_VERSION);
            result.diagnostics.put("promptTemplateHash", ExperimentDeepSeekClient.reactPromptHash());
            return done(result, start);
        }
    }

    private static JSONObject parseObject(String text) {
        if (text == null) throw new IllegalArgumentException("Empty JSON response");
        String value = text.trim();
        if (value.startsWith("```")) {
            value = value.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        }
        int first = value.indexOf('{'); int last = value.lastIndexOf('}');
        if (first < 0 || last <= first) throw new IllegalArgumentException("No JSON object in response");
        return JSON.parseObject(value.substring(first, last + 1));
    }
    private static String upper(String value) { return value == null ? null : value.trim().toUpperCase(Locale.ROOT); }
    private static boolean needsDb(String route) { return "DB".equals(route) || "DB+WEB".equals(route); }
    private static boolean needsWeb(String route) { return "WEB".equals(route) || "DB+WEB".equals(route); }
}
