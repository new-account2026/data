package com.nju.cssci.论文.support;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.nju.cssci.entity.vo.ChatToolCallVO;
import com.nju.cssci.entity.vo.WebSearchItemVO;
import com.nju.cssci.entity.vo.AiSearchPreviewVO;
import com.nju.cssci.entity.vo.AiFieldAuthorSearchPreviewVO;
import com.nju.cssci.service.SearchService;
import com.nju.cssci.service.Impl.ChatServiceImpl;
import com.nju.cssci.experiment.WebProviderStatus;
import com.nju.cssci.experiment.WebSearchDiagnostic;
import com.nju.cssci.experiment.WebSearchDiagnosticHolder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** The only retrieval boundary visible to experiment variants. */
public interface ExperimentToolBoundary {
    ChatToolCallVO execute(String toolName, String query, Object parsedParameters) throws Exception;
    int realDbCalls();
    int realWebCalls();
    boolean realDbEnabled();
    boolean realWebEnabled();
    default int tavilyBudgetLimit() { return 0; }
    default int tavilyBudgetRemaining() { return 0; }
    default int tavilyBudgetExceededCount() { return 0; }
    default int webCacheHits() { return 0; }
    default int logicalWebCalls() { return 0; }
    default int tavilyCacheMisses() { return 0; }
    default int failedTavilyPhysicalCalls() { return 0; }
    default int retryTavilyPhysicalCalls() { return 0; }
    default void beginVariant(String variant) { }
    default void endVariant() { }
    default JSONObject tavilyUsageSnapshot() { return new JSONObject(true); }
    default JSONObject cacheManifest() { return new JSONObject(true); }

    record FrozenCallContext(Object sessionState, Object toolPlan) { }

    /** Validation boundary. It never delegates to DB or Tavily. */
    final class ValidationStub implements ExperimentToolBoundary {
        private final AtomicInteger realDb = new AtomicInteger();
        private final AtomicInteger realWeb = new AtomicInteger();

        @Override
        public ChatToolCallVO execute(String toolName, String query, Object parsedParameters) {
            long start = System.nanoTime();
            boolean web = "web_search".equals(toolName);
            int bucket = stableBucket(query + "|" + toolName);
            int hits = bucket % 4 == 0 ? 0 : 2;
            long total = hits == 0 ? 0L : 12L + bucket;
            JSONObject record = new JSONObject(true);
            record.put("title", "SMOKE ONLY - fixed " + (web ? "web" : "database") + " evidence");
            record.put("content", "VALIDATION stub evidence for execution-path testing only.");
            record.put("url", web ? "https://example.invalid/smoke" : null);
            JSONArray records = new JSONArray();
            if (hits > 0) records.add(record);
            JSONObject page = new JSONObject(true);
            page.put("records", records);
            page.put("total", total);
            JSONObject response = new JSONObject(true);
            response.put("page", page);
            response.put("items", records);
            response.put("validationStub", true);

            ChatToolCallVO call = new ChatToolCallVO();
            call.setToolName(toolName);
            call.setStatus("success");
            call.setSummary("SMOKE ONLY fixed boundary; hits=" + hits);
            call.setHitCount(hits);
            JSONObject request = new JSONObject(true);
            request.put("query", query);
            request.put("parsedParameters", parsedParameters);
            call.setRequestPayload(request);
            call.setResponseData(response);
            call.setLatencyMs((System.nanoTime() - start) / 1_000_000L);
            return call;
        }

        @Override public int realDbCalls() { return realDb.get(); }
        @Override public int realWebCalls() { return realWeb.get(); }
        @Override public boolean realDbEnabled() { return false; }
        @Override public boolean realWebEnabled() { return false; }

        private static int stableBucket(String value) {
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256")
                        .digest(value.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
                return Integer.parseInt(HexFormat.of().formatHex(digest, 0, 2), 16);
            } catch (Exception error) {
                return Math.abs(value.hashCode() % 256);
            }
        }
    }

    /** Future formal-run boundary: delegates to the same production ChatService retrieval methods and provider. */
    final class Production implements ExperimentToolBoundary {
        private final ChatServiceImpl frozenChatService;
        private final SearchService searchService;
        private final boolean enableDb;
        private final boolean enableWeb;
        private final int webMaxResults;
        private final String webProvider;
        private final ExperimentTavilyBudget tavilyBudget;
        private final ExperimentTavilyCache tavilyCache;
        private final ValidationStub disabledBoundary = new ValidationStub();
        private final AtomicInteger realDb = new AtomicInteger();
        private final AtomicInteger cacheHits = new AtomicInteger();
        private final AtomicInteger cacheMisses = new AtomicInteger();
        private final AtomicInteger logicalWebCalls = new AtomicInteger();
        private final AtomicInteger failedPhysicalCalls = new AtomicInteger();
        private final ThreadLocal<String> currentVariant = new ThreadLocal<>();
        private final ConcurrentHashMap<String, VariantWebStats> perVariant = new ConcurrentHashMap<>();

        public Production(ChatServiceImpl frozenChatService, SearchService searchService,
                          boolean enableDb, boolean enableWeb, int webMaxResults,
                          String webProvider, ExperimentTavilyBudget tavilyBudget,
                          ExperimentTavilyCache tavilyCache) {
            this.frozenChatService = frozenChatService;
            this.searchService = searchService;
            this.enableDb = enableDb;
            this.enableWeb = enableWeb;
            this.webMaxResults = webMaxResults;
            this.webProvider = webProvider;
            this.tavilyBudget = tavilyBudget;
            this.tavilyCache = tavilyCache;
            if (enableWeb && (tavilyBudget == null || tavilyCache == null)) {
                throw new IllegalArgumentException("Real Tavily requires both a hard budget and retrieval cache");
            }
        }

        @Override
        public ChatToolCallVO execute(String toolName, String query, Object parsedParameters) throws Exception {
            if ("DIRECT_LLM_ANSWER".equals(currentVariant.get())) {
                throw new IllegalStateException("DIRECT_LLM_ANSWER is forbidden from entering DB/Web boundaries");
            }
            if ("web_search".equals(toolName)) {
                if (!enableWeb) return disabledBoundary.execute(toolName, query, parsedParameters);
                logicalWebCalls.incrementAndGet();
                VariantWebStats stats = stats();
                stats.logical.incrementAndGet();
                synchronized (tavilyCache.lockFor(webProvider, query, webMaxResults)) {
                    String requestHash = tavilyCache.key(webProvider, query, webMaxResults);
                    ExperimentTavilyCache.CacheHit cached = tavilyCache.load(webProvider, query, webMaxResults);
                    if (cached != null) {
                        cacheHits.incrementAndGet();
                        stats.cacheHits.incrementAndGet();
                        return webCall(query, cached.results(), 0L, true, false, "success",
                                requestHash, cached.metadata().getString("normalizedResponseSha256"),
                                0, "CACHE_HIT", null, null);
                    }
                    cacheMisses.incrementAndGet();
                    stats.cacheMisses.incrementAndGet();
                    String logicalRequestId = UUID.randomUUID().toString();
                    int physicalAttempt = 1;
                    if (!tavilyBudget.tryReserveRealCall(logicalRequestId, requestHash, physicalAttempt)) {
                        stats.budgetExceeded.incrementAndGet();
                        ChatToolCallVO denied = webCall(query, List.of(), 0L, false, false,
                                "TAVILY_BUDGET_EXCEEDED", requestHash, null, 0,
                                "BUDGET_EXCEEDED", null, null);
                        JSONObject response = new JSONObject(true);
                        response.put("budgetExceeded", true);
                        response.put("budgetLimit", tavilyBudget.limit());
                        response.put("actualTavilyCalls", tavilyBudget.actualCalls());
                        response.put("remainingAllowedCalls", tavilyBudget.remaining());
                        denied.setResponseData(response);
                        denied.setSummary("TAVILY_BUDGET_EXCEEDED; no provider request sent");
                        return denied;
                    }
                    long started = System.currentTimeMillis();
                    stats.realPhysical.incrementAndGet();
                    try {
                        List<WebSearchItemVO> results = searchService.search(query, webMaxResults);
                        List<WebSearchItemVO> safeResults = results == null ? List.of() : results;
                        WebSearchDiagnostic diagnostic = WebSearchDiagnosticHolder.consume();
                        boolean providerFailed = diagnostic != null
                                && diagnostic.getProviderStatus() == WebProviderStatus.PROVIDER_ERROR;
                        tavilyBudget.recordOutcome(!providerFailed);
                        if (providerFailed) {
                            failedPhysicalCalls.incrementAndGet();
                            stats.failedPhysical.incrementAndGet();
                        } else {
                            tavilyCache.save(webProvider, query, webMaxResults, safeResults);
                        }
                        String responseHash = ExperimentTavilyCache.responseHash(safeResults);
                        return webCall(query, safeResults, System.currentTimeMillis() - started,
                                false, true, "success", requestHash, responseHash, 1,
                                diagnostic == null ? "UNKNOWN" : diagnostic.getProviderStatus().name(),
                                diagnostic == null ? null : diagnostic.getProviderErrorType(),
                                diagnostic == null ? null : diagnostic.getProviderErrorMessage());
                    } catch (Exception failure) {
                        WebSearchDiagnosticHolder.consume();
                        tavilyBudget.recordOutcome(false);
                        failedPhysicalCalls.incrementAndGet();
                        stats.failedPhysical.incrementAndGet();
                        throw failure;
                    }
                }
            }
            if (!enableDb) return disabledBoundary.execute(toolName, query, parsedParameters);
            if (!(parsedParameters instanceof FrozenCallContext context))
                return executeAgentDb(toolName, query);
            realDb.incrementAndGet();
            if ("article_search".equals(toolName)) {
                Object param = FrozenExperimentHarness.readField(context.toolPlan(), "articleParam");
                return (ChatToolCallVO) FrozenExperimentHarness.invoke(frozenChatService, "runArticleTask",
                        context.sessionState(), false, param);
            }
            if ("field_author_search".equals(toolName)) {
                Object param = FrozenExperimentHarness.readField(context.toolPlan(), "fieldParam");
                return (ChatToolCallVO) FrozenExperimentHarness.invoke(frozenChatService, "runFieldTask",
                        context.sessionState(), false, param);
            }
            if ("score_top10".equals(toolName)) {
                return (ChatToolCallVO) FrozenExperimentHarness.invoke(frozenChatService, "runStatsTask",
                        query, context.sessionState());
            }
            throw new IllegalArgumentException("Unsupported common tool: " + toolName);
        }

        private ChatToolCallVO executeAgentDb(String toolName, String query) throws Exception {
            Object state = FrozenExperimentHarness.newSessionState();
            if ("article_search".equals(toolName)) {
                AiSearchPreviewVO preview = (AiSearchPreviewVO) FrozenExperimentHarness.invoke(
                        frozenChatService, "parseArticlePreview", query);
                return (ChatToolCallVO) FrozenExperimentHarness.invoke(frozenChatService, "runArticleTask",
                        state, false, preview == null ? null : preview.getAdvancedSearchParam());
            }
            if ("field_author_search".equals(toolName)) {
                AiFieldAuthorSearchPreviewVO preview = (AiFieldAuthorSearchPreviewVO) FrozenExperimentHarness.invoke(
                        frozenChatService, "parseFieldPreview", query);
                return (ChatToolCallVO) FrozenExperimentHarness.invoke(frozenChatService, "runFieldTask",
                        state, false, preview == null ? null : preview.getFieldAuthorSearchParam());
            }
            if ("score_top10".equals(toolName)) {
                return (ChatToolCallVO) FrozenExperimentHarness.invoke(frozenChatService, "runStatsTask", query, state);
            }
            throw new IllegalArgumentException("Unsupported common tool: " + toolName);
        }

        @Override public int realDbCalls() { return realDb.get(); }
        @Override public int realWebCalls() { return tavilyBudget == null ? 0 : tavilyBudget.actualCalls(); }
        @Override public boolean realDbEnabled() { return enableDb; }
        @Override public boolean realWebEnabled() { return enableWeb; }
        @Override public int tavilyBudgetLimit() { return tavilyBudget == null ? 0 : tavilyBudget.limit(); }
        @Override public int tavilyBudgetRemaining() { return tavilyBudget == null ? 0 : tavilyBudget.remaining(); }
        @Override public int tavilyBudgetExceededCount() {
            return tavilyBudget == null ? 0 : tavilyBudget.exceededCount();
        }
        @Override public int webCacheHits() { return cacheHits.get(); }
        @Override public int logicalWebCalls() { return logicalWebCalls.get(); }
        @Override public int tavilyCacheMisses() { return cacheMisses.get(); }
        @Override public int failedTavilyPhysicalCalls() { return failedPhysicalCalls.get(); }
        @Override public int retryTavilyPhysicalCalls() { return tavilyBudget == null ? 0 : tavilyBudget.retryCalls(); }
        @Override public void beginVariant(String variant) { currentVariant.set(variant); }
        @Override public void endVariant() { currentVariant.remove(); }

        @Override
        public JSONObject tavilyUsageSnapshot() {
            JSONObject value = new JSONObject(true);
            value.put("budgetLimit", tavilyBudgetLimit());
            value.put("actualRealPhysicalCalls", realWebCalls());
            value.put("logicalWebRequests", logicalWebCalls());
            value.put("cacheHits", webCacheHits());
            value.put("cacheMisses", tavilyCacheMisses());
            value.put("failedPhysicalAttempts", failedTavilyPhysicalCalls());
            value.put("retryPhysicalAttempts", retryTavilyPhysicalCalls());
            value.put("budgetExceededCount", tavilyBudgetExceededCount());
            value.put("remainingRunBudget", tavilyBudgetRemaining());
            value.put("uniqueRequestHashes", tavilyBudget == null ? 0 : tavilyBudget.reservations().stream()
                    .map(ExperimentTavilyBudget.Reservation::requestHash).filter(v -> v != null).distinct().count());
            value.put("snapshotId", tavilyCache == null ? null : tavilyCache.snapshotId());
            JSONObject variants = new JSONObject(true);
            perVariant.forEach((name, row) -> variants.put(name, row.toJson()));
            value.put("variants", variants);
            value.put("physicalReservations", tavilyBudget == null ? List.of() : tavilyBudget.reservations());
            value.put("grantedPhysicalReservations", tavilyBudget == null ? 0
                    : tavilyBudget.reservations().stream().filter(ExperimentTavilyBudget.Reservation::granted).count());
            return value;
        }

        @Override
        public JSONObject cacheManifest() {
            JSONObject value = tavilyCache == null ? new JSONObject(true) : tavilyCache.manifest();
            value.put("logicalRequests", logicalWebCalls());
            value.put("hits", webCacheHits());
            value.put("misses", tavilyCacheMisses());
            value.put("physicalCalls", realWebCalls());
            return value;
        }

        private ChatToolCallVO webCall(String query, List<WebSearchItemVO> results, long latency,
                                       boolean cacheHit, boolean providerCalled, String status,
                                       String requestHash, String responseHash, int physicalAttempts,
                                       String providerStatus, String providerErrorType,
                                       String providerErrorMessage) {
            ChatToolCallVO call = new ChatToolCallVO();
            call.setToolName("web_search");
            call.setStatus(status);
            JSONObject request = request(query);
            request.put("provider", webProvider);
            request.put("maxResults", webMaxResults);
            request.put("webCacheHit", cacheHit);
            request.put("realTavilyActuallyCalled", providerCalled);
            request.put("tavilyPhysicalAttempts", physicalAttempts);
            request.put("tavilyRequestHash", requestHash);
            request.put("tavilyResponseHash", responseHash);
            request.put("providerStatus", providerStatus);
            request.put("providerErrorType", providerErrorType);
            request.put("providerErrorMessage", providerErrorMessage);
            call.setRequestPayload(request);
            call.setResponseData(results);
            call.setHitCount(results == null ? 0 : results.size());
            call.setSummary((cacheHit ? "cached " : "") + "web search results=" + call.getHitCount());
            call.setLatencyMs(latency);
            return call;
        }

        private static JSONObject request(String query) {
            JSONObject value = new JSONObject(true); value.put("query", query); return value;
        }

        private VariantWebStats stats() {
            String variant = currentVariant.get();
            return perVariant.computeIfAbsent(variant == null ? "UNKNOWN" : variant,
                    ignored -> new VariantWebStats());
        }

        private static final class VariantWebStats {
            private final AtomicInteger logical = new AtomicInteger();
            private final AtomicInteger cacheHits = new AtomicInteger();
            private final AtomicInteger cacheMisses = new AtomicInteger();
            private final AtomicInteger realPhysical = new AtomicInteger();
            private final AtomicInteger failedPhysical = new AtomicInteger();
            private final AtomicInteger budgetExceeded = new AtomicInteger();

            private JSONObject toJson() {
                JSONObject value = new JSONObject(true);
                value.put("logicalWebRequests", logical.get());
                value.put("cacheHits", cacheHits.get());
                value.put("cacheMisses", cacheMisses.get());
                value.put("realPhysicalCalls", realPhysical.get());
                value.put("retryPhysicalCalls", 0);
                value.put("failedPhysicalCalls", failedPhysical.get());
                value.put("budgetExceededCount", budgetExceeded.get());
                return value;
            }
        }
    }
}
