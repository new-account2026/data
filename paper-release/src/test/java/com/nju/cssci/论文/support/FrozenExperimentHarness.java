package com.nju.cssci.论文.support;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.nju.cssci.common.Result;
import com.nju.cssci.entity.param.AiFieldAuthorSearchConfirmParam;
import com.nju.cssci.entity.param.AiFieldAuthorSearchParseParam;
import com.nju.cssci.entity.param.AiSearchConfirmParam;
import com.nju.cssci.entity.param.AiSearchParseParam;
import com.nju.cssci.entity.vo.ChatMessageVO;
import com.nju.cssci.entity.vo.ChatToolCallVO;
import com.nju.cssci.experiment.AuditTrace;
import com.nju.cssci.experiment.ExperimentContext;
import com.nju.cssci.experiment.ExperimentContextHolder;
import com.nju.cssci.experiment.ExperimentProperties;
import com.nju.cssci.experiment.ExperimentRunMetadata;
import com.nju.cssci.experiment.ExperimentTrace;
import com.nju.cssci.experiment.LlmCallEvent;
import com.nju.cssci.experiment.LlmStage;
import com.nju.cssci.experiment.ParserTrace;
import com.nju.cssci.experiment.PromptTemplateHashRegistry;
import com.nju.cssci.service.AiFieldAuthorSearchService;
import com.nju.cssci.service.AiSearchService;
import com.nju.cssci.service.SearchService;
import com.nju.cssci.service.Impl.AiFieldAuthorSearchServiceImpl;
import com.nju.cssci.service.Impl.AiSearchServiceImpl;
import com.nju.cssci.service.Impl.ChatServiceImpl;
import com.nju.cssci.service.Impl.DeepseekServiceImpl;
import com.nju.cssci.service.Impl.FieldAuthorDeepseekServiceImpl;
import com.nju.cssci.论文.PaperExperimentCase;
import com.nju.cssci.论文.PaperExperimentContext;
import com.nju.cssci.论文.PaperExperimentResult;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Test-layer adapter around the exact frozen private planner/parser/coverage/generation/audit methods.
 * It contains no replacement routing formula and never reaches a retrieval provider directly.
 */
public final class FrozenExperimentHarness {
    public enum RetrievalPolicy { FULL_ADAPTIVE, INITIAL_ONLY, NO_FEEDBACK, NO_FRESHNESS_DYNAMIC, NO_AUDIT }

    private final ChatServiceImpl chat;
    private final String model;
    private final double coverageThreshold;
    private final int webMaxResults;
    private final boolean validationOnly;

    private FrozenExperimentHarness(ChatServiceImpl chat, String model,
                                    double coverageThreshold, int webMaxResults, boolean validationOnly) {
        this.chat = chat;
        this.model = model;
        this.coverageThreshold = coverageThreshold;
        this.webMaxResults = webMaxResults;
        this.validationOnly = validationOnly;
    }

    public static FrozenExperimentHarness create(Properties config, String algorithmCommit,
                                                 String infrastructureCommit,
                                                 boolean validationOnly) throws Exception {
        ExperimentProperties experiment = new ExperimentProperties();
        experiment.setEnabled(false);
        experiment.setAlgorithmBaselineCommit(algorithmCommit);
        experiment.setInfrastructureVersion(infrastructureCommit);
        experiment.setApplicationEnvironment("paper-formal-experiment-test-layer");
        PromptTemplateHashRegistry registry = new PromptTemplateHashRegistry();
        String apiUrl = required(config, "deepseek.api.url");
        String apiKey = required(config, "deepseek.api.key");
        String model = required(config, "deepseek.model");
        int connect = integer(config, "deepseek.timeout.connect-ms", 5000);
        int read = integer(config, "deepseek.timeout.read-ms", 15000);

        DeepseekServiceImpl articleLlm = new DeepseekServiceImpl(experiment, registry);
        set(articleLlm, "apiUrl", apiUrl); set(articleLlm, "apiKey", apiKey); set(articleLlm, "model", model);
        set(articleLlm, "connectTimeoutMs", connect); set(articleLlm, "readTimeoutMs", read);
        AiSearchServiceImpl articleParser = new AiSearchServiceImpl();
        set(articleParser, "deepseekService", articleLlm);

        FieldAuthorDeepseekServiceImpl scholarLlm = new FieldAuthorDeepseekServiceImpl(experiment, registry);
        set(scholarLlm, "apiUrl", apiUrl); set(scholarLlm, "apiKey", apiKey); set(scholarLlm, "model", model);
        set(scholarLlm, "connectTimeoutMs", connect); set(scholarLlm, "readTimeoutMs", read);
        AiFieldAuthorSearchServiceImpl scholarParser = new AiFieldAuthorSearchServiceImpl();
        set(scholarParser, "fieldAuthorDeepseekService", scholarLlm);

        AiSearchService articleBoundary = new AiSearchService() {
            @Override public Result parseOriginRequest(AiSearchParseParam param) {
                return articleParser.parseOriginRequest(param);
            }
            @Override public Result confirmSearch(AiSearchConfirmParam param) {
                throw new AssertionError("Frozen harness cannot directly execute DB retrieval");
            }
        };
        AiFieldAuthorSearchService scholarBoundary = new AiFieldAuthorSearchService() {
            @Override public Result parseOriginRequest(AiFieldAuthorSearchParseParam param) {
                return scholarParser.parseOriginRequest(param);
            }
            @Override public Result confirmSearch(AiFieldAuthorSearchConfirmParam param) {
                throw new AssertionError("Frozen harness cannot directly execute DB retrieval");
            }
        };
        SearchService webBoundary = (query, maxResults) -> {
            throw new AssertionError("Frozen harness cannot directly execute Tavily retrieval");
        };

        ChatServiceImpl chat = new ChatServiceImpl();
        set(chat, "aiSearchService", articleBoundary);
        set(chat, "aiFieldAuthorSearchService", scholarBoundary);
        set(chat, "searchService", webBoundary);
        set(chat, "experimentProperties", experiment);
        set(chat, "promptTemplateHashRegistry", registry);
        set(chat, "apiUrl", apiUrl); set(chat, "apiKey", apiKey); set(chat, "model", model);
        set(chat, "connectTimeoutMs", connect); set(chat, "readTimeoutMs", read);
        set(chat, "maxHistoryMessages", integer(config, "deepseek.chat.max-history-messages", 20));
        set(chat, "maxSessionCount", integer(config, "deepseek.chat.max-session-count", 1000));
        set(chat, "maxInputChars", integer(config, "deepseek.chat.max-input-chars", 4000));
        set(chat, "temperature", decimal(config, "deepseek.chat.temperature", 0.7D));
        String configuredSystemPrompt = config.getProperty("deepseek.chat.system-prompt");
        if (configuredSystemPrompt != null && !configuredSystemPrompt.isBlank()) {
            set(chat, "defaultSystemPrompt", configuredSystemPrompt);
        }
        set(chat, "useSearchDefault", bool(config, "deepseek.chat.use-search-default", true));
        set(chat, "decisionEnabled", bool(config, "deepseek.chat.decision-enabled", true));
        set(chat, "askThreshold", decimal(config, "deepseek.chat.threshold.ask", 0.55D));
        double coverage = decimal(config, "deepseek.chat.threshold.coverage-db", 0.60D);
        int maxWeb = integer(config, "deepseek.chat.web-max-results", 5);
        set(chat, "coverageDbThreshold", coverage); set(chat, "webMaxResults", maxWeb);
        set(chat, "tooManyHitsThreshold", integer(config, "deepseek.chat.too-many-hits-threshold", 100));
        set(chat, "retryTimes", integer(config, "deepseek.chat.retry-times", 2));
        set(chat, "retryBackoffMs", integer(config, "deepseek.chat.retry-backoff-ms", 300));
        set(chat, "routeDecisionRetryTimes", integer(config, "deepseek.chat.route-decision.retry-times", 2));
        set(chat, "routeDecisionRetryBackoffMs", integer(config,
                "deepseek.chat.route-decision.retry-backoff-ms", 300));
        set(chat, "routeDecisionMaxTokens", integer(config, "deepseek.chat.route-decision.max-tokens", 1200));
        set(chat, "auditDecisionMaxTokens", integer(config, "deepseek.chat.audit-decision.max-tokens", 300));
        set(chat, "auditAdaptiveMaxTokensEnabled", bool(config,
                "deepseek.chat.audit-decision.adaptive-max-tokens.enabled", true));
        set(chat, "auditMaxTokenIncrement", integer(config,
                "deepseek.chat.audit-decision.adaptive-max-tokens.increment", 3000));
        set(chat, "auditMaxTokenCeiling", integer(config,
                "deepseek.chat.audit-decision.adaptive-max-tokens.ceiling", 15000));
        set(chat, "auditMaxTokenSaturationRatio", decimal(config,
                "deepseek.chat.audit-decision.adaptive-max-tokens.saturation-ratio", 0.99D));
        set(chat, "sessionPersistenceEnabled", false);
        invoke(chat, "registerStablePromptTemplates");
        return new FrozenExperimentHarness(chat, model, coverage, maxWeb, validationOnly);
    }

    public PlannerObservation plan(PaperExperimentCase item) throws Exception {
        ExperimentTrace trace = begin(item, "PROPOSED_PLANNER");
        try {
            Object state = nested("SessionState");
            Object decision = invoke(chat, "decideRouteByAi", item.query(), state);
            if (decision == null) throw new IllegalStateException("Frozen Planner returned no decision");
            invoke(chat, "normalizeClarificationDialogueMode", decision, state, item.query());
            String resolved = (String) invoke(chat, "resolveQueryForTurn", item.query(), state, decision);
            invoke(chat, "promoteClarificationToRetrieval", decision, state, resolved);
            invoke(chat, "normalizeNonBlockingAsk", decision, state, resolved);
            String action = string(decision, "action");
            String route = string(decision, "route");
            String tool = string(decision, "tool");
            Object task = null;
            Object toolPlan = null;
            if ("RETRIEVE".equals(action) && needsDb(route)) {
                task = invoke(chat, "chooseDbTask", decision, resolved, state);
                if (task != null) {
                    tool = string(task, "tool");
                    toolPlan = invoke(chat, "prepareTask", resolved, task);
                }
            }
            return new PlannerObservation(action, route, tool, string(decision, "dialogueMode"), resolved,
                    string(decision, "normalizedQuery"), list(field(decision, "requiredParams")),
                    list(field(decision, "clarificationQuestions")), list(field(decision, "reasonCodes")),
                    boolField(decision, "confidenceProvided") ? number(decision, "confidence") : null,
                    decision, state, task, toolPlan, parser(trace), llm(trace));
        } finally {
            ExperimentContextHolder.clear();
        }
    }

    public void applyPlanner(PaperExperimentResult result, PlannerObservation observation, boolean shared) {
        result.predictedAction = observation.action();
        result.predictedInitialRoute = observation.initialRoute();
        result.predictedTool = observation.tool();
        result.dialogueMode = observation.dialogueMode();
        result.resolvedQuery = observation.resolvedQuery();
        result.normalizedQuery = observation.normalizedQuery();
        result.missingParams = observation.missingParams();
        result.clarificationQuestions = observation.clarificationQuestions();
        result.reasonCodes = observation.reasonCodes();
        result.confidence = observation.confidence();
        result.sharedPlannerDecision = shared;
        result.plannerDecisionSourceQueryId = observation == null ? null : result.queryId;
        addLogicalCalls(result, observation.llmCalls(), shared);
        if (observation.parser() != null) {
            result.diagnostics.put("parser", observation.parser());
            result.parsedParameters = observation.parser().get("parsedParameters");
        }
    }

    public void executeFrozenPipeline(PaperExperimentCase item, PaperExperimentResult result,
                                      PlannerObservation plan, ExperimentToolBoundary boundary,
                                      RetrievalPolicy policy, boolean generateAnswer,
                                      boolean executeAudit) throws Exception {
        applyPlanner(result, plan, true);
        if (!"RETRIEVE".equals(plan.action())) {
            if (generateAnswer && "ANSWER".equals(plan.action())) directFrozenAnswer(item, result);
            else if ("ASK".equals(plan.action())) result.finalAnswer = String.join("\n", plan.clarificationQuestions());
            result.finalRoute = "NONE";
            result.finalRouteProvenance = "NO_RETRIEVAL_ACTION";
            return;
        }
        List<ChatToolCallVO> calls = new ArrayList<>();
        String initial = plan.initialRoute();
        String plannedWebQuery = webQuery(plan);
        result.webQuery = needsWeb(initial) ? plannedWebQuery : null;
        if (needsDb(initial)) calls.add(boundary.execute(normalizeTool(plan.tool()), plan.resolvedQuery(),
                new ExperimentToolBoundary.FrozenCallContext(plan.stateObject(), plan.toolPlan())));
        if (needsWeb(initial)) calls.add(boundary.execute("web_search", plannedWebQuery, null));
        result.dbWouldBeCalled = needsDb(initial);
        result.webWouldBeCalled = needsWeb(initial);
        double coverage = (double) invoke(chat, "evaluateCoverage", calls);
        String reason = "none";
        boolean canAdapt = "DB".equals(initial) && (policy == RetrievalPolicy.FULL_ADAPTIVE
                || policy == RetrievalPolicy.NO_FRESHNESS_DYNAMIC || policy == RetrievalPolicy.NO_AUDIT);
        if (canAdapt) {
            reason = (String) invoke(chat, "determineWebSupplementReason", item.query(), coverage);
            boolean addWeb = !"none".equals(reason);
            if (policy == RetrievalPolicy.NO_FRESHNESS_DYNAMIC) {
                addWeb = coverage < coverageThreshold;
                reason = addWeb ? "low_db_coverage" : "none";
            }
            if (addWeb) {
                result.webWouldBeCalled = true;
                result.webQuery = plannedWebQuery;
                calls.add(boundary.execute("web_search", plannedWebQuery, null));
            }
        }
        result.webSupplementTriggered = result.webWouldBeCalled && !needsWeb(initial);
        result.webTriggerReason = result.webWouldBeCalled && !needsWeb(initial)
                ? reason : (needsWeb(initial) ? "initial_route_web" : "none");
        result.dbActuallyCalled = boundary.realDbEnabled() && result.dbWouldBeCalled;
        result.webActuallyCalled = calls.stream().filter(v -> "web_search".equals(v.getToolName()))
                .map(ChatToolCallVO::getRequestPayload).filter(JSONObject.class::isInstance)
                .map(JSONObject.class::cast)
                .anyMatch(v -> Boolean.TRUE.equals(v.getBoolean("realTavilyActuallyCalled")));
        boolean tavilyBudgetExceeded = calls.stream().anyMatch(v ->
                "TAVILY_BUDGET_EXCEEDED".equals(v.getStatus()));
        for (ChatToolCallVO call : calls) addTool(result, call, boundary);
        if (tavilyBudgetExceeded) {
            result.status = "INCOMPLETE_TAVILY_BUDGET_EXCEEDED";
            result.failureStage = "WEB_RETRIEVAL_BUDGET";
            result.finalRoute = null;
            result.finalRouteProvenance = "INCOMPLETE_TAVILY_BUDGET_EXCEEDED";
            result.diagnostics.put("budgetExceeded", true);
            result.diagnostics.put("tavilyBudgetLimit", boundary.tavilyBudgetLimit());
            result.diagnostics.put("remainingAllowedTavilyCalls", boundary.tavilyBudgetRemaining());
            return;
        }
        result.finalRoute = (String) invoke(chat, "resolveRoute", calls, initial);
        result.finalRouteProvenance = boundary.realDbEnabled() || boundary.realWebEnabled()
                ? "ACTUAL_EXECUTION" : "MOCK_EXECUTION_DERIVED / NOT FORMAL EFFECT RESULT";
        result.routeChanged = !safe(initial).equals(safe(result.finalRoute));
        result.routeTransition = initial + " -> " + result.finalRoute;
        result.diagnostics.put("dbCoverage", coverage);
        result.coverageScore = coverage;
        result.diagnostics.put("coverageThreshold", coverageThreshold);
        result.diagnostics.put("retrievalFeedbackEnabled", policy == RetrievalPolicy.FULL_ADAPTIVE
                || policy == RetrievalPolicy.NO_FRESHNESS_DYNAMIC || policy == RetrievalPolicy.NO_AUDIT);
        result.diagnostics.put("freshnessDynamicEnabled", policy != RetrievalPolicy.NO_FRESHNESS_DYNAMIC);
        if (!generateAnswer) return;

        ExperimentTrace trace = begin(item, result.variant + "_GENERATION");
        try {
            String evidence = (String) invoke(chat, "buildEvidenceContext", calls, coverage);
            result.finalEvidence = evidence;
            String prompt = (String) invoke(chat, "buildRetrieveAnswerInstruction", result.finalRoute, calls, null);
            String raw = (String) invoke(chat, "safeCallDeepseek", Collections.<ChatMessageVO>emptyList(),
                    plan.resolvedQuery(), false, prompt, evidence, null, LlmStage.RETRIEVAL_ANSWER);
            result.preAuditAnswer = raw;
            result.finalAnswer = raw;
            if (executeAudit && policy != RetrievalPolicy.NO_AUDIT) {
                result.auditExecuted = true;
                result.finalAnswer = (String) invoke(chat, "auditAndCorrectEvidenceAnswer", plan.resolvedQuery(),
                        Collections.<ChatMessageVO>emptyList(), raw, calls, coverage);
                AuditTrace audit = trace.getAudit();
                result.auditPassed = audit.getAuditPassed();
                result.answerChanged = audit.getAnswerChanged();
                result.auditCorrected = audit.getAnswerChanged();
                if (audit.getIssues() != null) result.auditIssues.addAll(audit.getIssues());
                result.diagnostics.put("preAuditAnswer", audit.getPreAuditAnswer());
                result.diagnostics.put("auditIssues", audit.getIssues());
            } else {
                result.auditExecuted = false;
                result.auditPassed = null;
                result.answerChanged = false;
                result.auditCorrected = false;
            }
            addLogicalCalls(result, llm(trace), false);
        } finally {
            ExperimentContextHolder.clear();
        }
    }

    public void directFrozenAnswer(PaperExperimentCase item, PaperExperimentResult result) throws Exception {
        ExperimentTrace trace = begin(item, result.variant + "_DIRECT_ANSWER");
        try {
            String prompt = (String) invoke(chat, "buildDirectAnswerPrompt");
            result.finalAnswer = (String) invoke(chat, "safeCallDeepseek", Collections.<ChatMessageVO>emptyList(),
                    item.query(), false, prompt, null, null, LlmStage.DIRECT_ANSWER);
            addLogicalCalls(result, llm(trace), false);
        } finally {
            ExperimentContextHolder.clear();
        }
    }

    private ExperimentTrace begin(PaperExperimentCase item, String stage) {
        ExperimentTrace trace = new ExperimentTrace();
        trace.setMarker(validationOnly ? PaperExperimentContext.VALIDATION_MARKER
                : PaperExperimentContext.FORMAL_MARKER);
        ExperimentRunMetadata metadata = new ExperimentRunMetadata();
        metadata.setExperimentId(validationOnly ? "paper_formal_smoke" : "paper_formal");
        metadata.setRunId("in_memory");
        metadata.setQueryId(item.queryId());
        metadata.setTurnId(stage);
        metadata.setTimestamp(Instant.now().toString());
        metadata.setExactModelName(model);
        metadata.setValidationOnly(validationOnly);
        trace.setExperiment(metadata);
        ExperimentContextHolder.set(new ExperimentContext(trace));
        return trace;
    }

    private static void addTool(PaperExperimentResult result, ChatToolCallVO call,
                                ExperimentToolBoundary boundary) {
        JSONObject value = new JSONObject(true);
        value.put("toolName", call.getToolName()); value.put("status", call.getStatus());
        value.put("hitCount", call.getHitCount()); value.put("latencyMs", call.getLatencyMs());
        value.put("requestPayload", call.getRequestPayload()); value.put("responseData", call.getResponseData());
        boolean real;
        if ("web_search".equals(call.getToolName()) && call.getRequestPayload() instanceof JSONObject request) {
            real = Boolean.TRUE.equals(request.getBoolean("realTavilyActuallyCalled"));
            value.put("webCacheHit", request.getBoolean("webCacheHit"));
            value.put("realTavilyActuallyCalled", real);
            value.put("tavilyPhysicalAttempts", request.getInteger("tavilyPhysicalAttempts"));
            value.put("tavilyRequestHash", request.getString("tavilyRequestHash"));
            value.put("tavilyResponseHash", request.getString("tavilyResponseHash"));
            value.put("providerStatus", request.getString("providerStatus"));
            value.put("providerErrorType", request.getString("providerErrorType"));
            value.put("providerErrorMessage", request.getString("providerErrorMessage"));
            result.webCacheHit = Boolean.TRUE.equals(request.getBoolean("webCacheHit"));
            result.realTavilyActuallyCalled |= real;
            result.tavilyPhysicalAttempts += request.getIntValue("tavilyPhysicalAttempts");
            result.tavilyRequestHash = request.getString("tavilyRequestHash");
            result.tavilyResponseHash = request.getString("tavilyResponseHash");
            String providerStatus = request.getString("providerStatus");
            result.webSucceeded = "success".equalsIgnoreCase(call.getStatus())
                    && !"PROVIDER_ERROR".equals(providerStatus)
                    && !"BUDGET_EXCEEDED".equals(providerStatus);
            result.webEvidence.add(call.getResponseData());
        } else {
            real = boundary.realDbEnabled();
            result.dbAttempted = true;
            result.dbSucceeded = "success".equalsIgnoreCase(call.getStatus());
            result.dbHitCount = (result.dbHitCount == null ? 0 : result.dbHitCount)
                    + (call.getHitCount() == null ? 0 : call.getHitCount());
            result.dbLatencyMs = (result.dbLatencyMs == null ? 0L : result.dbLatencyMs)
                    + (call.getLatencyMs() == null ? 0L : call.getLatencyMs());
            Long total = findTotal(call.getResponseData());
            if (total != null) result.dbTotal = (result.dbTotal == null ? 0L : result.dbTotal) + total;
            result.dbEvidence.add(call.getResponseData());
        }
        value.put("realProviderCalled", real);
        result.toolCalls.add(value);
        JSONObject evidence = new JSONObject(true);
        evidence.put("sourceType", "web_search".equals(call.getToolName()) ? "WEB" : "DB");
        evidence.put("toolName", call.getToolName()); evidence.put("hitCount", call.getHitCount());
        evidence.put("validationStub", !real);
        evidence.put("responseHash", "web_search".equals(call.getToolName())
                && call.getRequestPayload() instanceof JSONObject request
                ? request.getString("tavilyResponseHash") : null);
        result.evidenceSources.add(evidence);
    }

    private static Long findTotal(Object value) {
        if (value instanceof JSONObject object) {
            Long direct = object.getLong("total");
            if (direct != null) return direct;
            for (Object nested : object.values()) {
                Long found = findTotal(nested);
                if (found != null) return found;
            }
        }
        if (value instanceof Map<?, ?> map) {
            Object direct = map.get("total");
            if (direct instanceof Number number) return number.longValue();
            for (Object nested : map.values()) {
                Long found = findTotal(nested);
                if (found != null) return found;
            }
        }
        return null;
    }

    public static void addLogicalCalls(PaperExperimentResult result, List<JSONObject> calls, boolean reused) {
        if (calls == null) return;
        for (JSONObject original : calls) {
            JSONObject call = JSON.parseObject(original.toJSONString());
            if (reused) call.put("sharedPhysicalInvocation", true);
            result.llmCalls.add(call);
            result.llmCallCount++;
            Integer p = call.getInteger("promptTokens"); Integer c = call.getInteger("completionTokens");
            Integer t = call.getInteger("totalTokens");
            if (p != null) result.promptTokens += p;
            if (c != null) result.completionTokens += c;
            if (t != null) result.totalTokens += t;
        }
        result.tokenUsageComplete = !result.llmCalls.isEmpty()
                && result.llmCalls.stream().allMatch(v -> Boolean.TRUE.equals(v.getBoolean("tokenUsageComplete")));
    }

    private static List<JSONObject> llm(ExperimentTrace trace) {
        List<JSONObject> values = new ArrayList<>();
        for (LlmCallEvent event : trace.getLlmCalls()) {
            JSONObject value = new JSONObject(true);
            value.put("eventId", event.getEventId());
            value.put("stage", event.getStage() == null ? null : event.getStage().name());
            value.put("model", event.getModel()); value.put("attempt", event.getAttempt());
            value.put("maxAttempts", event.getMaxAttempts());
            value.put("latencyMs", event.getLatencyMs()); value.put("promptTokens", event.getPromptTokens());
            value.put("completionTokens", event.getCompletionTokens()); value.put("totalTokens", event.getTotalTokens());
            value.put("reasoningTokens", event.getReasoningTokens());
            value.put("maxTokens", event.getMaxTokens());
            value.put("finishReason", event.getFinishReason());
            value.put("responseContentLength", event.getResponseContentLength());
            value.put("responseContentSha256", event.getResponseContentSha256());
            value.put("reasoningContentPresent", event.getReasoningContentPresent());
            value.put("outputLimitReached", event.getOutputLimitReached());
            value.put("tokenUsageComplete", event.getPromptTokens() != null && event.getCompletionTokens() != null
                    && event.getTotalTokens() != null);
            value.put("success", event.getSuccess()); value.put("exceptionType", event.getExceptionType());
            value.put("promptVersion", event.getPromptVersion());
            value.put("promptTemplateHash", event.getPromptTemplateHash());
            values.add(value);
        }
        return values;
    }

    private static JSONObject parser(ExperimentTrace trace) {
        if (trace.getParser() == null || trace.getParser().isEmpty()) return null;
        ParserTrace parser = trace.getParser().get(trace.getParser().size() - 1);
        JSONObject value = new JSONObject(true);
        value.put("parserType", parser.getParserType()); value.put("success", parser.getSuccess());
        value.put("needClarification", parser.getNeedClarification());
        value.put("clarificationQuestions", parser.getClarificationQuestions());
        value.put("parsedParameters", parser.getParsedParameters()); value.put("failureReason", parser.getFailureReason());
        value.put("latencyMs", parser.getLatencyMs());
        return value;
    }

    private static String webQuery(PlannerObservation plan) {
        String value = string(plan.decisionObject(), "webQuery");
        return value == null || value.isBlank() ? plan.resolvedQuery() : value;
    }
    private static String normalizeTool(String tool) {
        return tool == null || tool.isBlank() || "none".equals(tool) ? "article_search" : tool;
    }
    private static boolean needsDb(String route) { return "DB".equals(route) || "DB+WEB".equals(route); }
    private static boolean needsWeb(String route) { return "WEB".equals(route) || "DB+WEB".equals(route); }
    private static String safe(String value) { return value == null ? "" : value; }

    private Object nested(String simpleName) throws Exception {
        for (Class<?> type : ChatServiceImpl.class.getDeclaredClasses()) {
            if (type.getSimpleName().equals(simpleName)) {
                Constructor<?> constructor = type.getDeclaredConstructor();
                constructor.setAccessible(true);
                return constructor.newInstance();
            }
        }
        throw new NoSuchMethodException(simpleName);
    }
    public static Object newSessionState() throws Exception {
        for (Class<?> type : ChatServiceImpl.class.getDeclaredClasses()) {
            if (type.getSimpleName().equals("SessionState")) {
                Constructor<?> constructor = type.getDeclaredConstructor();
                constructor.setAccessible(true);
                return constructor.newInstance();
            }
        }
        throw new NoSuchMethodException("SessionState");
    }
    public static Object invoke(Object target, String name, Object... args) throws Exception {
        Method selected = null;
        for (Method method : target.getClass().getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == args.length
                    && compatible(method.getParameterTypes(), args)) {
                selected = method; break;
            }
        }
        if (selected == null) throw new NoSuchMethodException(name);
        selected.setAccessible(true);
        try { return selected.invoke(target, args); }
        catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error fatal) throw fatal;
            throw error;
        }
    }
    private static boolean compatible(Class<?>[] types, Object[] args) {
        for (int i = 0; i < types.length; i++) {
            if (args[i] == null) {
                if (types[i].isPrimitive()) return false;
                continue;
            }
            Class<?> expected = box(types[i]);
            if (!expected.isAssignableFrom(args[i].getClass())) return false;
        }
        return true;
    }
    private static Class<?> box(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == boolean.class) return Boolean.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == double.class) return Double.class;
        if (type == float.class) return Float.class;
        if (type == short.class) return Short.class;
        if (type == byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        return type;
    }
    private static Object field(Object target, String name) {
        Class<?> type = target.getClass();
        while (type != null) {
            try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(target); }
            catch (NoSuchFieldException missing) { type = type.getSuperclass(); }
            catch (Exception error) { throw new IllegalStateException(error); }
        }
        return null;
    }
    public static Object readField(Object target, String name) { return target == null ? null : field(target, name); }
    private static String string(Object target, String name) { Object v = field(target, name); return v == null ? null : v.toString(); }
    private static boolean boolField(Object target, String name) { Object v = field(target, name); return v instanceof Boolean && (Boolean) v; }
    private static Double number(Object target, String name) { Object v = field(target, name); return v instanceof Number ? ((Number) v).doubleValue() : null; }
    @SuppressWarnings("unchecked") private static List<String> list(Object value) {
        if (!(value instanceof List<?> source)) return new ArrayList<>();
        List<String> result = new ArrayList<>(); for (Object item : source) if (item != null) result.add(item.toString()); return result;
    }
    private static void set(Object target, String name, Object value) { ReflectionTestUtils.setField(target, name, value); }
    private static String required(Properties p, String key) {
        String value = p.getProperty(key); if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing config: " + key); return value;
    }
    private static int integer(Properties p, String key, int fallback) { try { return Integer.parseInt(p.getProperty(key, String.valueOf(fallback))); } catch (Exception e) { return fallback; } }
    private static double decimal(Properties p, String key, double fallback) { try { return Double.parseDouble(p.getProperty(key, String.valueOf(fallback))); } catch (Exception e) { return fallback; } }
    private static boolean bool(Properties p, String key, boolean fallback) { String v=p.getProperty(key); return v == null ? fallback : Boolean.parseBoolean(v); }

    public record PlannerObservation(String action, String initialRoute, String tool, String dialogueMode,
                                     String resolvedQuery, String normalizedQuery, List<String> missingParams,
                                     List<String> clarificationQuestions, List<String> reasonCodes, Double confidence,
                                     Object decisionObject, Object stateObject, Object taskObject, Object toolPlan,
                                     JSONObject parser, List<JSONObject> llmCalls) { }
}
