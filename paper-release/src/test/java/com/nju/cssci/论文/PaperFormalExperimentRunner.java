package com.nju.cssci.论文;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.nju.cssci.CssciApplication;
import com.nju.cssci.service.Impl.ChatServiceImpl;
import com.nju.cssci.service.SearchService;
import com.nju.cssci.论文.support.ExperimentDeepSeekClient;
import com.nju.cssci.论文.support.ExperimentHumanEvalReadOnlyRunner;
import com.nju.cssci.论文.support.ExperimentTavilyBudget;
import com.nju.cssci.论文.support.ExperimentTavilyCache;
import com.nju.cssci.论文.support.ExperimentToolBoundary;
import com.nju.cssci.论文.support.FrozenExperimentHarness;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.HexFormat;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Explicit-mode paper experiment entry. Running it requires an intentional system-property opt-in. */
public class PaperFormalExperimentRunner {
    // ---- Mode is the sole source of effective execution/resource policy. ----
    private static final PaperExperimentRunMode EXPERIMENT_MODE = PaperExperimentRunMode.valueOf(
            System.getProperty("paper.experiment.mode", "RETRIEVAL_FORMAL").trim().toUpperCase(Locale.ROOT));
    private static final int ROUTING_SMOKE_SAMPLE_SIZE = 50;
    private static final int RETRIEVAL_SMOKE_SAMPLE_SIZE = 10;
    private static final boolean RETRIEVAL_SMOKE_REAL_DEEPSEEK = true;
    private static final boolean RETRIEVAL_SMOKE_REAL_DB = false;
    private static final boolean RETRIEVAL_SMOKE_REAL_TAVILY = false;
    private static final int RETRIEVAL_SMOKE_TAVILY_BUDGET = 0;
    private static final int MAX_REAL_TAVILY_CALLS_PER_RUN =
            PaperRetrievalFormalSpec.TAVILY_PHYSICAL_CALL_BUDGET;
    private static final int EXPECTED_DECISION_FORMAL_VALID_QUERIES = 10_000;

    private static final long RANDOM_SEED = 20260903L;
    private static final String DATASET_PATH =
            "D:\\Java Projects\\cssci\\src\\test\\java\\com\\nju\\cssci\\论文\\scholar_dataset_10000_v3.xlsx";
    private static final String RETRIEVAL_FORMAL_SUBSET_MANIFEST =
            "D:\\Java Projects\\cssci\\src\\test\\java\\com\\nju\\cssci\\论文\\retrieval_formal_subset_manifest.json";
    private static final String RETRIEVAL_FORMAL_SUBSET_SHA256 =
            PaperRetrievalFormalSpec.SUBSET_MANIFEST_SHA256;
    private static final String HUMAN_EVAL_SOURCE_DIRECTORY =
            "D:\\Java Projects\\cssci\\validation-output\\paper_formal_smoke\\run_20260903_final_composite";

    private static final boolean RUN_DECISION_EXPERIMENTS = true;
    private static final boolean RUN_RETRIEVAL_EXPERIMENTS = true;
    private static final boolean RUN_END_TO_END_EXPERIMENTS = true;
    private static final boolean RUN_ABLATIONS = true;
    private static final int MAX_CONCURRENCY = 5;
    private static final int MAX_REACT_TOOL_STEPS = 5;
    private static final int BRANCH_SAMPLES_PER_CLASS = 2;

    private static final String ALGORITHM_BASELINE_COMMIT = "e6b0c9b3b7f124d40d598ff8a7ef33c0b754eb7f";
    private static final String EXPERIMENT_INFRASTRUCTURE_COMMIT = "d0545c498cddfed4aa09eb19dbe26834661cb25f";
    private static final String VALIDATION_RECORD_COMMIT = "2181a4b860b6e3c4227fd40a85b2389f7b459eca";
    private static final String FORMAL_EXPERIMENT_RUNNER_COMMIT = "UNCOMMITTED";
    private static final Path PROJECT_ROOT = Path.of("D:\\Java Projects\\cssci").toAbsolutePath().normalize();
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter RUN_ID = DateTimeFormatter
            .ofPattern("yyyyMMdd_HHmmss", Locale.ROOT).withZone(ZONE);

    private ConfigurableApplicationContext productionContext;

    @Test
    void runConfiguredExperimentMode() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("paper.experiment.execute"),
                "External experiment execution requires -Dpaper.experiment.execute=true");
        silenceSensitiveLogging();
        PaperExperimentModeGate.EffectiveConfiguration effective = PaperExperimentModeGate.resolve(settings());
        PaperExperimentModeGate.dispatch(effective,
                () -> { runVariantMode(effective); return null; },
                () -> { runHumanExportMode(effective); return null; });
    }

    private PaperExperimentModeGate.RequestedSettings settings() {
        return new PaperExperimentModeGate.RequestedSettings(EXPERIMENT_MODE,
                ROUTING_SMOKE_SAMPLE_SIZE, RETRIEVAL_SMOKE_SAMPLE_SIZE,
                RETRIEVAL_SMOKE_REAL_DEEPSEEK, RETRIEVAL_SMOKE_REAL_DB,
                RETRIEVAL_SMOKE_REAL_TAVILY, RETRIEVAL_SMOKE_TAVILY_BUDGET,
                RETRIEVAL_FORMAL_SUBSET_MANIFEST, RETRIEVAL_FORMAL_SUBSET_SHA256,
                MAX_REAL_TAVILY_CALLS_PER_RUN, HUMAN_EVAL_SOURCE_DIRECTORY);
    }

    private void runVariantMode(PaperExperimentModeGate.EffectiveConfiguration effective) throws Exception {
        if (!effective.realDeepSeek()) {
            throw new IllegalStateException("No test-only DeepSeek stub is configured for this runner");
        }
        Instant started = Instant.now();
        Path runDirectory = runDirectory(effective.mode(), started);
        PaperExperimentDatasetReader reader = new PaperExperimentDatasetReader();
        PaperExperimentDatasetReader.Dataset dataset = reader.read(Path.of(DATASET_PATH));
        if (effective.mode() == PaperExperimentRunMode.DECISION_FORMAL) {
            require(dataset.validCases().size() == EXPECTED_DECISION_FORMAL_VALID_QUERIES,
                    "DECISION_FORMAL requires exactly 10,000 valid queries; observed="
                            + dataset.validCases().size());
        }
        PaperRetrievalSubsetLoader.FrozenSubset frozenSubset = null;
        List<PaperExperimentCase> selected;
        if (effective.mode() == PaperExperimentRunMode.RETRIEVAL_FORMAL) {
            frozenSubset = new PaperRetrievalSubsetLoader().load(dataset,
                    Path.of(effective.frozenRetrievalSubsetPath()),
                    effective.frozenRetrievalSubsetSha256(), PROJECT_ROOT);
            selected = frozenSubset.cases();
        } else {
            selected = reader.select(dataset, effective.effectiveSampleSize(), RANDOM_SEED);
        }
        List<PaperExperimentCase> branch = effective.mode() == PaperExperimentRunMode.ROUTING_SMOKE
                ? reader.branchCoverage(dataset, BRANCH_SAMPLES_PER_CLASS, RANDOM_SEED) : List.of();

        Properties config = configuration();
        PaperExperimentVariantRegistry registry = new PaperExperimentVariantRegistry(MAX_REACT_TOOL_STEPS);
        List<PaperExperimentVariant> variants = enabled(registry.all(), effective);
        GitState preflightGit = gitState();
        if (effective.mode() == PaperExperimentRunMode.DECISION_FORMAL) {
            decisionFormalPreflight(effective, dataset, selected, variants, preflightGit, config);
        } else if (effective.mode() == PaperExperimentRunMode.RETRIEVAL_FORMAL) {
            retrievalFormalPreflight(effective, dataset, selected, variants, frozenSubset, preflightGit, config);
        } else {
            require(!variants.isEmpty(), "No variants are enabled for " + effective.mode());
        }
        if (Boolean.getBoolean("paper.experiment.preflightOnly")) {
            System.out.println("Preflight-only requested; no DeepSeek/DB/Tavily call was made.");
            return;
        }
        require(!Files.exists(runDirectory), "Output directory already exists: " + runDirectory);
        Files.createDirectories(runDirectory);
        if (effective.mode() == PaperExperimentRunMode.RETRIEVAL_FORMAL) {
            PaperExperimentOutputWriter earlyOutput = new PaperExperimentOutputWriter();
            earlyOutput.jsonl(runDirectory.resolve("selected_queries.jsonl"), selected.stream()
                    .map(PaperExperimentCase::toJson).toList());
            Files.copy(frozenSubset.manifestPath(),
                    runDirectory.resolve("retrieval_formal_subset_manifest.json"));
        }

        ExperimentToolBoundary tools = toolBoundary(effective, config);
        try {
            FrozenExperimentHarness frozen = FrozenExperimentHarness.create(config,
                    ALGORITHM_BASELINE_COMMIT, EXPERIMENT_INFRASTRUCTURE_COMMIT,
                    effective.mode().validationOnly());
            ExperimentDeepSeekClient baseline = baselineClient(config);
            PaperExperimentContext context = new PaperExperimentContext(frozen, baseline, tools,
                    effective.mode().validationOnly(), effective.realDeepSeek());
            Path checkpoint = switch (effective.mode()) {
                case DECISION_FORMAL -> runDirectory.resolve("decision_results.inprogress.jsonl");
                case RETRIEVAL_FORMAL -> runDirectory.resolve("formal_results.inprogress.jsonl");
                default -> null;
            };
            List<PaperExperimentResult> results = execute(selected, variants, context, checkpoint);
            List<PaperExperimentResult> branchResults = branch.isEmpty() ? List.of() : execute(branch,
                    variants.stream().filter(v -> v.supportedTasks()
                            .contains(PaperExperimentVariant.Task.DECISION)).toList(), context);
            PaperExperimentMetrics metrics = new PaperExperimentMetrics();
            JSONObject decisionMetrics = metrics.decisionMetrics(results);
            JSONObject retrievalMetrics = metrics.retrievalMetrics(results);
            JSONObject efficiencyMetrics = metrics.efficiencyMetrics(results);
            JSONArray variantManifest = enabledVariantManifest(
                    registry.manifest(baseline.model(), effective.mode().validationOnly()), variants);
            Instant finished = Instant.now();
            JSONObject manifest = manifest(effective, dataset, selected, branch, variants, results,
                    branchResults, baseline, tools, frozenSubset, started, finished, preflightGit, config);
            List<JSONObject> labelReview = labelCompatibilityReview(dataset.validCases());

            PaperExperimentOutputWriter output = new PaperExperimentOutputWriter();
            JSONObject retrievalIntegrity = null;
            if (effective.mode() == PaperExperimentRunMode.RETRIEVAL_FORMAL) {
                retrievalIntegrity = retrievalFormalIntegrityAudit(runDirectory, dataset, selected,
                        variants, results, tools, frozenSubset);
                output.json(runDirectory.resolve("POSTFLIGHT_INTEGRITY_AUDIT.json"), retrievalIntegrity);
                if (!retrievalIntegrity.getBooleanValue("integrityPassed")) {
                    Files.writeString(runDirectory.resolve("POSTFLIGHT_RECOVERY_FAILED.md"),
                            "# Retrieval Formal Postflight Failed\n\n"
                                    + "- Status: FORMAL_RETRIEVAL_RUN_INCOMPLETE\n"
                                    + "- Usable for paper metrics: NO\n"
                                    + "- See `POSTFLIGHT_INTEGRITY_AUDIT.json` for failed checks.\n",
                            StandardCharsets.UTF_8);
                    throw new IllegalStateException("RETRIEVAL_FORMAL postflight integrity failed");
                }
            }
            output.writeAll(runDirectory, manifest, selected, variantManifest, results,
                    decisionMetrics, retrievalMetrics, efficiencyMetrics, labelReview,
                    RANDOM_SEED, false);
            if (effective.mode() == PaperExperimentRunMode.DECISION_FORMAL) {
                PaperDecisionFormalAnalysis analysis = new PaperDecisionFormalAnalysis();
                JSONObject confusion = analysis.confusionMatrices(decisionMetrics);
                JSONObject pairwise = analysis.pairwise(results);
                output.json(runDirectory.resolve("decision_confusion_matrix.json"), confusion);
                output.json(runDirectory.resolve("decision_pairwise_analysis.json"), pairwise);
                analysis.writeErrorCases(runDirectory.resolve("decision_error_cases.csv"), results);
                finalizeDecisionManifest(manifest, runDirectory, selected, variants, results,
                        decisionMetrics, baseline, tools, started);
                output.json(runDirectory.resolve("experiment_manifest.json"), manifest);
                analysis.writeReport(runDirectory.resolve("DECISION_FORMAL_REPORT.md"), manifest,
                        decisionMetrics, confusion, pairwise, efficiencyMetrics,
                        selected, variants, results);
                decisionFormalPostflight(runDirectory, effective, dataset, selected, variants,
                        results, decisionMetrics, tools, baseline);
            } else if (effective.mode() == PaperExperimentRunMode.RETRIEVAL_FORMAL) {
                PaperRetrievalFormalAnalysis analysis = new PaperRetrievalFormalAnalysis();
                JSONObject formalRetrievalMetrics = analysis.retrievalMetrics(results);
                JSONObject answerMetrics = analysis.answerMetrics(results);
                JSONObject ablationMetrics = analysis.ablationMetrics(results);
                JSONObject pairedAnalysis = analysis.pairedAnalysis(results);
                JSONObject tavilyUsage = tools.tavilyUsageSnapshot();
                JSONObject cacheManifest = tools.cacheManifest();
                output.writeRetrievalFormalArtifacts(runDirectory, results, formalRetrievalMetrics,
                        answerMetrics, efficiencyMetrics, ablationMetrics, pairedAnalysis,
                        tavilyUsage, cacheManifest);
                Files.copy(runDirectory.resolve("formal_results.inprogress.jsonl"),
                        runDirectory.resolve("formal_results.jsonl"),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                finalizeRetrievalManifest(manifest, runDirectory, results, tools, frozenSubset,
                        started, retrievalIntegrity);
                output.json(runDirectory.resolve("experiment_manifest.json"), manifest);
                analysis.writeReport(runDirectory.resolve("RETRIEVAL_FORMAL_REPORT.md"), manifest,
                        formalRetrievalMetrics, answerMetrics, efficiencyMetrics, ablationMetrics,
                        pairedAnalysis, tavilyUsage, retrievalIntegrity);
                retrievalFormalFinalChecks(runDirectory, selected, variants, results, tools,
                        frozenSubset, retrievalIntegrity);
            }
            if (!branch.isEmpty()) {
                output.jsonl(runDirectory.resolve("branch_coverage_results.jsonl"), branchResults);
                output.json(runDirectory.resolve("branch_coverage_manifest.json"), branchManifest(branch));
            }
            if (effective.mode() != PaperExperimentRunMode.DECISION_FORMAL
                    && effective.mode() != PaperExperimentRunMode.RETRIEVAL_FORMAL) {
                writeModeReport(runDirectory, effective, dataset, selected, variants, results,
                        baseline, tools, frozenSubset);
            }
            assertProviderInvariants(effective, tools);
            assertEquals(effective.effectiveSampleSize() == PaperExperimentModeGate.FULL_DATASET
                    ? dataset.validCases().size() : selected.size(), selected.size());
            printSummary(runDirectory, effective, selected, variants, results, baseline, tools);
        } finally {
            if (productionContext != null) productionContext.close();
        }
    }

    private void runHumanExportMode(PaperExperimentModeGate.EffectiveConfiguration effective) throws Exception {
        Instant started = Instant.now();
        Path output = runDirectory(effective.mode(), started);
        require(!Files.exists(output), "Output directory already exists: " + output);
        ExperimentHumanEvalReadOnlyRunner.ExportAudit audit = new ExperimentHumanEvalReadOnlyRunner().export(
                Path.of(effective.humanEvalSourceDirectory()), output, PROJECT_ROOT, RANDOM_SEED);
        assertEquals(0, audit.deepSeekCalls());
        assertEquals(0, audit.dbCalls());
        assertEquals(0, audit.tavilyCalls());
        assertTrue(!audit.variantsExecuted());
        JSONObject manifest = new JSONObject(true);
        manifest.put("experimentMode", effective.mode().name());
        manifest.put("sourceDirectory", effective.humanEvalSourceDirectory());
        manifest.put("answerRows", audit.answerRows());
        manifest.put("variantsExecuted", false);
        manifest.put("realDeepSeekCalls", 0);
        manifest.put("realDbCalls", 0);
        manifest.put("realTavilyCalls", 0);
        manifest.put("timestamp", started.toString());
        new PaperExperimentOutputWriter().json(output.resolve("experiment_manifest.json"), manifest);
    }

    private List<PaperExperimentResult> execute(List<PaperExperimentCase> cases,
                                                List<PaperExperimentVariant> variants,
                                                PaperExperimentContext context) throws Exception {
        return execute(cases, variants, context, null);
    }

    private List<PaperExperimentResult> execute(List<PaperExperimentCase> cases,
                                                List<PaperExperimentVariant> variants,
                                                PaperExperimentContext context,
                                                Path checkpoint) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(MAX_CONCURRENCY);
        try (BufferedWriter checkpointWriter = checkpoint == null ? null
                : Files.newBufferedWriter(checkpoint, StandardCharsets.UTF_8)) {
            Object checkpointLock = new Object();
            AtomicInteger checkpointRows = new AtomicInteger();
            AtomicInteger completedQueries = new AtomicInteger();
            AtomicInteger failures = new AtomicInteger();
            List<Future<List<PaperExperimentResult>>> futures = new ArrayList<>();
            for (PaperExperimentCase item : cases) futures.add(executor.submit(() -> {
                List<PaperExperimentResult> values = new ArrayList<>();
                for (PaperExperimentVariant variant : variants) {
                    PaperExperimentResult result = safeExecute(item, variant, context);
                    values.add(result);
                    if (!result.success || (result.status != null && result.status.startsWith("INCOMPLETE"))) {
                        failures.incrementAndGet();
                    }
                    if (checkpointWriter != null) {
                        synchronized (checkpointLock) {
                            checkpointWriter.write(JSON.toJSONString(result.toJson()));
                            checkpointWriter.newLine();
                            int rows = checkpointRows.incrementAndGet();
                            if (rows % 10 == 0) checkpointWriter.flush();
                        }
                    }
                }
                int queries = completedQueries.incrementAndGet();
                if (checkpointWriter != null && queries % 25 == 0) {
                    System.out.println(effectiveProgressLabel(context) + " progress: completedQueries="
                            + queries + "/" + cases.size() + ", completedRows=" + checkpointRows.get()
                            + ", failures=" + failures.get() + ", realDbCalls="
                            + context.tools().realDbCalls() + ", realTavilyPhysicalCalls="
                            + context.tools().realWebCalls() + ", tavilyCacheHits="
                            + context.tools().webCacheHits() + ", remainingTavilyBudget="
                            + context.tools().tavilyBudgetRemaining());
                }
                return values;
            }));
            List<PaperExperimentResult> all = new ArrayList<>();
            for (Future<List<PaperExperimentResult>> future : futures) {
                List<PaperExperimentResult> completed = future.get();
                all.addAll(completed);
            }
            if (checkpointWriter != null) checkpointWriter.flush();
            return all;
        } finally {
            executor.shutdownNow();
        }
    }

    private static String effectiveProgressLabel(PaperExperimentContext context) {
        return context.validationOnly() ? "SMOKE" : "FORMAL";
    }

    private PaperExperimentResult safeExecute(PaperExperimentCase item, PaperExperimentVariant variant,
                                               PaperExperimentContext context) {
        long start = System.nanoTime();
        context.tools().beginVariant(variant.name());
        try {
            PaperExperimentCase runtimeCase = new PaperExperimentCase(item.queryId(), item.query(),
                    null, null, item.sourceRow(), Map.of());
            PaperExperimentResult result = "ABLATION_NO_EVIDENCE_AUDIT".equals(variant.name())
                    ? context.pairedNoAudit(runtimeCase, variant)
                    : variant.execute(runtimeCase, context);
            if (result.status != null && result.status.startsWith("INCOMPLETE")) result.success = false;
            result.goldAction = item.goldAction();
            result.goldRoute = item.goldRoute();
            context.captureProposedFull(result);
            result.diagnostics.put("supportedTasks", variant.supportedTasks().stream().map(Enum::name).toList());
            result.diagnostics.put("goldVisibleDuringVariantExecution", false);
            return result;
        } catch (Throwable error) {
            Throwable root = root(error);
            if (systemicProviderFailure(root)) {
                throw new IllegalStateException("GLOBAL_PROVIDER_CONFIGURATION_FAILURE: "
                        + root.getClass().getName() + ": " + safe(root.getMessage()), root);
            }
            PaperExperimentResult result = PaperExperimentResult.base(item, variant, context.marker());
            result.success = false;
            result.status = "FAILED";
            result.failureStage = "VARIANT_EXECUTION";
            result.exceptionType = root.getClass().getName();
            result.exceptionMessage = safe(root.getMessage());
            result.latencyMs = (System.nanoTime() - start) / 1_000_000L;
            result.diagnostics.put("supportedTasks", variant.supportedTasks().stream().map(Enum::name).toList());
            result.diagnostics.put("goldVisibleDuringVariantExecution", false);
            if (root instanceof ExperimentDeepSeekClient.CallFailure failure) {
                FrozenExperimentHarness.addLogicalCalls(result, failure.attempts(), false);
            }
            return result;
        } finally {
            context.tools().endVariant();
        }
    }

    private List<PaperExperimentVariant> enabled(List<PaperExperimentVariant> all,
                                                 PaperExperimentModeGate.EffectiveConfiguration effective) {
        return all.stream().filter(v -> {
            if (!PaperExperimentModeGate.allowsVariant(effective, v)) return false;
            if (effective.mode() == PaperExperimentRunMode.RETRIEVAL_FORMAL
                    && !PaperRetrievalFormalSpec.PRIMARY_VARIANTS.contains(v.name())) return false;
            String onlyVariant = System.getProperty("paper.experiment.onlyVariant");
            if (onlyVariant != null && !onlyVariant.isBlank() && !onlyVariant.equals(v.name())) return false;
            if (v.supportedTasks().contains(PaperExperimentVariant.Task.DECISION)) return RUN_DECISION_EXPERIMENTS;
            if (v.supportedTasks().contains(PaperExperimentVariant.Task.ABLATION)) return RUN_ABLATIONS;
            if (v.supportedTasks().contains(PaperExperimentVariant.Task.END_TO_END)) return RUN_END_TO_END_EXPERIMENTS;
            return v.supportedTasks().contains(PaperExperimentVariant.Task.RETRIEVAL_DYNAMIC_ROUTING)
                    && RUN_RETRIEVAL_EXPERIMENTS;
        }).toList();
    }

    private ExperimentToolBoundary toolBoundary(PaperExperimentModeGate.EffectiveConfiguration effective,
                                                  Properties config) {
        PaperExperimentModeGate.validateEffective(effective);
        if (!effective.realDb() && !effective.realTavily()) return new ExperimentToolBoundary.ValidationStub();
        /*
         * The formal retrieval boundary needs selected production service beans, not
         * an embedded server or eager initialization of unrelated @PostConstruct
         * caches.  Lazy, non-web startup prevents untracked database reads before a
         * variant explicitly crosses ExperimentToolBoundary.
         */
        productionContext = new SpringApplicationBuilder(CssciApplication.class)
                .web(WebApplicationType.NONE)
                .headless(true)
                .properties("spring.main.lazy-initialization=true")
                .run();
        String[] experimentPropertyBeans = productionContext.getBeanNamesForType(
                com.nju.cssci.experiment.ExperimentProperties.class, false, false);
        require(experimentPropertyBeans.length == 1,
                "Formal retrieval context requires exactly one ExperimentProperties bean; observed="
                        + java.util.Arrays.toString(experimentPropertyBeans));
        ExperimentTavilyBudget budget = new ExperimentTavilyBudget(effective.tavilyBudget());
        ExperimentTavilyCache cache = new ExperimentTavilyCache(
                PROJECT_ROOT.resolve("experiment-cache/tavily/"
                        + PaperRetrievalFormalSpec.RETRIEVAL_SNAPSHOT_ID),
                PaperRetrievalFormalSpec.RETRIEVAL_SNAPSHOT_ID);
        return new ExperimentToolBoundary.Production(
                productionContext.getBean(ChatServiceImpl.class), productionContext.getBean(SearchService.class),
                effective.realDb(), effective.realTavily(),
                integer(config, "deepseek.chat.web-max-results", 5), "TAVILY", budget, cache);
    }

    private Path runDirectory(PaperExperimentRunMode mode, Instant started) {
        Path base = switch (mode) {
            case ROUTING_SMOKE -> PROJECT_ROOT.resolve("validation-output/paper_mode_refactor/routing_smoke");
            case RETRIEVAL_SMOKE -> PROJECT_ROOT.resolve("validation-output/paper_mode_refactor/retrieval_smoke");
            case DECISION_FORMAL -> PROJECT_ROOT.resolve("experiment-output/paper_formal/decision_10k");
            case RETRIEVAL_FORMAL -> PROJECT_ROOT.resolve("experiment-output/paper_formal/retrieval_subset");
            case HUMAN_EVAL_EXPORT -> PROJECT_ROOT.resolve("experiment-output/paper_formal/human_eval");
        };
        Path directory = base.resolve("run_" + RUN_ID.format(started)).toAbsolutePath().normalize();
        require(directory.startsWith(PROJECT_ROOT), "Output path escapes project root");
        return directory;
    }

    private JSONObject manifest(PaperExperimentModeGate.EffectiveConfiguration effective,
                                PaperExperimentDatasetReader.Dataset dataset,
                                List<PaperExperimentCase> selected, List<PaperExperimentCase> branch,
                                List<PaperExperimentVariant> variants, List<PaperExperimentResult> results,
                                List<PaperExperimentResult> branchResults, ExperimentDeepSeekClient llm,
                                ExperimentToolBoundary tools, PaperRetrievalSubsetLoader.FrozenSubset subset,
                                Instant started, Instant finished, GitState git,
                                Properties config) throws Exception {
        JSONObject value = new JSONObject(true);
        value.put("marker", effective.mode().validationOnly() ? PaperExperimentContext.VALIDATION_MARKER
                : "FORMAL PAPER EXPERIMENT");
        value.put("experimentMode", effective.mode().name());
        value.put("formal", effective.formal());
        value.put("runId", "run_" + RUN_ID.format(started));
        value.put("timestamp", started.toString());
        value.put("startedAt", started.toString());
        value.put("finishedAt", finished.toString());
        value.put("durationMs", java.time.Duration.between(started, finished).toMillis());
        value.put("sampleSize", selected.size());
        value.put("totalRows", dataset.totalRows());
        value.put("validRows", dataset.validCases().size());
        value.put("selectedRows", selected.size());
        value.put("effectiveSampleSize", effective.effectiveSampleSize());
        value.put("randomSeed", subset == null ? RANDOM_SEED : subset.randomSeed());
        value.put("datasetPath", dataset.path().toString());
        value.put("datasetSha256", dataset.sha256());
        value.put("selectedQueryIds", selected.stream().map(PaperExperimentCase::queryId).toList());
        value.put("branchCoverageQueryIds", branch.stream().map(PaperExperimentCase::queryId).toList());
        value.put("frozenSubsetManifest", subset == null ? null : subset.manifestPath().toString());
        value.put("frozenSubsetSha256", subset == null ? null : subset.manifestSha256());
        value.put("subsetManifestSha256", subset == null ? null : subset.manifestSha256());
        value.put("subsetSamplingStrategy", subset == null ? null : subset.samplingStrategy());
        value.put("subsetCreatedAt", subset == null ? null : subset.createdAt());
        value.put("retrievalSnapshotId", subset == null ? null : subset.retrievalSnapshotId());
        value.put("routeDistribution", subset == null ? null : subset.routeDistribution());
        value.put("algorithmBaselineCommit", ALGORITHM_BASELINE_COMMIT);
        value.put("experimentInfrastructureCommit", EXPERIMENT_INFRASTRUCTURE_COMMIT);
        value.put("validationRecordCommit", VALIDATION_RECORD_COMMIT);
        value.put("formalExperimentRunnerCommit", FORMAL_EXPERIMENT_RUNNER_COMMIT);
        value.put("experimentLayerFileHashes", experimentLayerHashes());
        value.put("gitDiffSummary", process("git", "diff", "--stat"));
        value.put("currentGitCommit", git.commit());
        value.put("branch", git.branch());
        value.put("dirtyWorkingTree", git.dirty());
        value.put("model", llm.model());
        value.put("endpoint", llm.endpoint());
        value.put("realDeepSeekEnabled", effective.realDeepSeek());
        value.put("realDbEnabled", effective.realDb());
        value.put("realTavilyEnabled", effective.realTavily());
        value.put("executeVariants", effective.executeVariants());
        value.put("enabledVariants", variants.stream().map(PaperExperimentVariant::name).toList());
        value.put("variants", variants.stream().map(PaperExperimentVariant::name).toList());
        value.put("realDeepSeekCalls", realDeepSeekCalls(results, branchResults, llm));
        value.put("actualRealDbCalls", tools.realDbCalls());
        value.put("actualRealTavilyCalls", tools.realWebCalls());
        value.put("actualRealTavilyPhysicalCalls", tools.realWebCalls());
        value.put("logicalWebCalls", tools.logicalWebCalls());
        value.put("realDbCalls", tools.realDbCalls());
        value.put("realTavilyCalls", tools.realWebCalls());
        value.put("tavilyBudgetLimit", tools.tavilyBudgetLimit());
        value.put("remainingAllowedTavilyCalls", tools.tavilyBudgetRemaining());
        value.put("tavilyBudgetExceededCount", tools.tavilyBudgetExceededCount());
        value.put("webCacheHits", tools.webCacheHits());
        value.put("tavilyCacheMisses", tools.tavilyCacheMisses());
        value.put("failedTavilyPhysicalCalls", tools.failedTavilyPhysicalCalls());
        value.put("retryTavilyPhysicalCalls", tools.retryTavilyPhysicalCalls());
        value.put("tavilyCacheEnabled", effective.mode() == PaperExperimentRunMode.RETRIEVAL_FORMAL);
        value.put("reactEnabled", variants.stream().anyMatch(v -> "REACT_STYLE".equals(v.name())));
        value.put("humanEvaluationEnabled", false);
        value.put("maxConcurrency", MAX_CONCURRENCY);
        JSONObject auditTokenBudget = new JSONObject(true);
        auditTokenBudget.put("stage", "EVIDENCE_AUDIT");
        auditTokenBudget.put("version", config.getProperty(
                "deepseek.chat.audit-decision.adaptive-max-tokens.version",
                "evidence-audit-adaptive-token-v1"));
        auditTokenBudget.put("initialMaxTokens", integer(config,
                "deepseek.chat.audit-decision.max-tokens", 300));
        auditTokenBudget.put("adaptiveEnabled", bool(config,
                "deepseek.chat.audit-decision.adaptive-max-tokens.enabled", true));
        auditTokenBudget.put("increment", integer(config,
                "deepseek.chat.audit-decision.adaptive-max-tokens.increment", 3000));
        auditTokenBudget.put("ceiling", integer(config,
                "deepseek.chat.audit-decision.adaptive-max-tokens.ceiling", 15000));
        auditTokenBudget.put("saturationRatio", decimal(config,
                "deepseek.chat.audit-decision.adaptive-max-tokens.saturation-ratio", 0.99D));
        auditTokenBudget.put("maxAttempts", integer(config,
                "deepseek.chat.route-decision.retry-times", 2));
        auditTokenBudget.put("escalationCondition",
                "EMPTY_OR_INVALID_JSON_AND_(finish_reason=length_OR_completionTokens>=ceil(maxTokens*saturationRatio))");
        value.put("evidenceAuditTokenBudgetPolicy", auditTokenBudget);
        value.put("originalConcurrency", MAX_CONCURRENCY);
        value.put("effectiveConcurrency", MAX_CONCURRENCY);
        value.put("concurrencyAdjustmentReason", null);
        return value;
    }

    private void decisionFormalPreflight(PaperExperimentModeGate.EffectiveConfiguration effective,
                                         PaperExperimentDatasetReader.Dataset dataset,
                                         List<PaperExperimentCase> selected,
                                         List<PaperExperimentVariant> variants,
                                         GitState git, Properties config) {
        System.out.println("=== DECISION_FORMAL PREFLIGHT ===");
        try {
            require(effective.mode() == PaperExperimentRunMode.DECISION_FORMAL,
                    "Experiment mode must be DECISION_FORMAL");
            require(effective.formal(), "Formal must be true");
            require(effective.effectiveSampleSize() == PaperExperimentModeGate.FULL_DATASET,
                    "Effective sample size must be -1");
            require(dataset.validCases().size() == EXPECTED_DECISION_FORMAL_VALID_QUERIES,
                    "Dataset valid query count must be 10000");
            require(selected.size() == dataset.validCases().size(), "All valid queries must be selected");
            long unique = selected.stream().map(PaperExperimentCase::queryId).distinct().count();
            require(unique == selected.size(), "Duplicate query IDs detected: " + (selected.size() - unique));
            require(effective.realDeepSeek(), "Real DeepSeek must be enabled");
            require(!effective.realDb(), "Real DB must be disabled");
            require(!effective.realTavily(), "Real Tavily must be disabled");
            require(effective.tavilyBudget() == 0, "Tavily budget must be zero");
            require(effective.executeVariants(), "Variant execution must be enabled");
            require(!variants.isEmpty(), "Enabled variant list is empty");
            require(variants.stream().allMatch(v -> v.readiness() == PaperExperimentVariant.Readiness.FORMAL_READY),
                    "All enabled variants must be FORMAL_READY");
            require(variants.stream().allMatch(v -> v.supportedTasks().contains(PaperExperimentVariant.Task.DECISION)),
                    "Every enabled variant must support Task.DECISION");
            require(Set.copyOf(variants.stream().map(PaperExperimentVariant::name).toList())
                            .equals(Set.of(PaperDecisionFormalAnalysis.PROPOSED, PaperDecisionFormalAnalysis.DIRECT)),
                    "Unexpected Decision variants enabled: " + variants.stream().map(PaperExperimentVariant::name).toList());
            require(required(config, "deepseek.api.url") != null
                            && required(config, "deepseek.api.key") != null
                            && required(config, "deepseek.model") != null,
                    "DeepSeek provider configuration is globally invalid");

            System.out.println("Experiment mode = " + effective.mode());
            System.out.println("Formal = " + effective.formal());
            System.out.println("Effective sample size = " + effective.effectiveSampleSize());
            System.out.println("Expected valid queries = " + EXPECTED_DECISION_FORMAL_VALID_QUERIES);
            System.out.println("Dataset SHA-256 = " + dataset.sha256());
            System.out.println("Real DeepSeek = " + effective.realDeepSeek());
            System.out.println("Real DB = " + effective.realDb());
            System.out.println("Real Tavily = " + effective.realTavily());
            System.out.println("Tavily budget = " + effective.tavilyBudget());
            System.out.println("Execute variants = " + effective.executeVariants());
            System.out.println("Enabled variants = " + variants.stream().map(PaperExperimentVariant::name).toList());
            System.out.println("Current Git commit = " + git.commit());
            System.out.println("Branch = " + git.branch());
            System.out.println("Dirty working tree = " + git.dirty());
            System.out.println("DECISION_FORMAL PREFLIGHT PASSED");
        } catch (RuntimeException failed) {
            System.out.println("DECISION_FORMAL PREFLIGHT FAILED");
            System.out.println("Blocker = " + failed.getMessage());
            throw failed;
        }
    }

    private void retrievalFormalPreflight(PaperExperimentModeGate.EffectiveConfiguration effective,
                                           PaperExperimentDatasetReader.Dataset dataset,
                                           List<PaperExperimentCase> selected,
                                           List<PaperExperimentVariant> variants,
                                           PaperRetrievalSubsetLoader.FrozenSubset subset,
                                           GitState git, Properties config) {
        System.out.println("=== RETRIEVAL_FORMAL PREFLIGHT ===");
        try {
            require(effective.mode() == PaperExperimentRunMode.RETRIEVAL_FORMAL,
                    "Experiment mode must be RETRIEVAL_FORMAL");
            require(effective.formal() && effective.executeVariants(),
                    "RETRIEVAL_FORMAL must be formal and execute variants");
            require(effective.effectiveSampleSize() == PaperExperimentModeGate.FROZEN_SUBSET_ONLY,
                    "RETRIEVAL_FORMAL must use FROZEN_SUBSET_ONLY");
            require(dataset.validCases().size() == PaperRetrievalFormalSpec.DATASET_VALID_ROWS,
                    "Dataset valid query count must be 10000");
            require(PaperRetrievalFormalSpec.DATASET_SHA256.equalsIgnoreCase(dataset.sha256()),
                    "Dataset SHA-256 mismatch");
            require(subset != null, "Frozen subset was not loaded");
            require(selected.size() == PaperRetrievalFormalSpec.SUBSET_SIZE,
                    "Frozen subset must contain exactly 300 queries");
            require(selected.stream().map(PaperExperimentCase::queryId).distinct().count() == selected.size(),
                    "Frozen subset contains duplicate query IDs");
            require(selected.stream().allMatch(item -> "RETRIEVE".equals(item.goldAction())),
                    "Frozen subset contains a non-RETRIEVE Gold Action");
            for (Map.Entry<String, Integer> expected : PaperRetrievalFormalSpec.ROUTE_DISTRIBUTION.entrySet()) {
                long actual = selected.stream().filter(item -> expected.getKey().equals(item.goldRoute())).count();
                require(actual == expected.getValue(), "Gold route distribution mismatch for " + expected.getKey());
            }
            require(RETRIEVAL_FORMAL_SUBSET_SHA256.equalsIgnoreCase(subset.manifestSha256()),
                    "Frozen subset manifest SHA-256 mismatch");
            require(PaperRetrievalFormalSpec.RETRIEVAL_SNAPSHOT_ID.equals(subset.retrievalSnapshotId()),
                    "Retrieval snapshot ID mismatch");
            require(effective.realDeepSeek() && effective.realDb() && effective.realTavily(),
                    "Real DeepSeek, DB, and Tavily must be enabled for RETRIEVAL_FORMAL");
            require(effective.tavilyBudget() == PaperRetrievalFormalSpec.TAVILY_PHYSICAL_CALL_BUDGET,
                    "Tavily physical-call budget must be exactly 700");
            require(effective.tavilyBudget() <= PaperExperimentModeGate.MAX_TAVILY_HARD_LIMIT,
                    "Tavily budget exceeds the global hard cap");
            require(!variants.isEmpty(), "Primary Variant list is empty");
            require(variants.stream().allMatch(v -> v.readiness() == PaperExperimentVariant.Readiness.FORMAL_READY),
                    "Every enabled Variant must be FORMAL_READY");
            require(Set.copyOf(variants.stream().map(PaperExperimentVariant::name).toList())
                            .equals(PaperRetrievalFormalSpec.PRIMARY_VARIANTS),
                    "Unexpected Retrieval Primary Variant set: "
                            + variants.stream().map(PaperExperimentVariant::name).toList());
            require(variants.stream().noneMatch(v -> "REACT_STYLE".equals(v.name())),
                    "ReAct must be disabled for this 300-query run");
            require(variants.stream().noneMatch(v -> "ABLATION_NO_STATEFUL_CLARIFICATION".equals(v.name())),
                    "Stateful clarification ablation requires a separate multi-turn dataset");

            System.out.println("Experiment mode = " + effective.mode());
            System.out.println("Formal = true");
            System.out.println("Dataset valid rows = " + dataset.validCases().size());
            System.out.println("Dataset SHA = " + dataset.sha256());
            System.out.println("Effective sample size = FROZEN_SUBSET_ONLY");
            System.out.println("Frozen subset size = " + selected.size());
            System.out.println("Unique subset IDs = "
                    + selected.stream().map(PaperExperimentCase::queryId).distinct().count());
            System.out.println("Gold Action RETRIEVE = "
                    + selected.stream().filter(item -> "RETRIEVE".equals(item.goldAction())).count());
            System.out.println("Gold Route = " + PaperRetrievalFormalSpec.ROUTE_DISTRIBUTION);
            System.out.println("Subset manifest path = " + subset.manifestPath());
            System.out.println("Subset manifest SHA = " + subset.manifestSha256());
            System.out.println("Real DeepSeek = true");
            System.out.println("Real DB = true");
            System.out.println("Real Tavily = true");
            System.out.println("Tavily physical-call budget = " + effective.tavilyBudget());
            System.out.println("Tavily cache = ENABLED");
            System.out.println("Evidence Audit token policy = "
                    + integer(config, "deepseek.chat.audit-decision.max-tokens", 300)
                    + " + " + integer(config,
                    "deepseek.chat.audit-decision.adaptive-max-tokens.increment", 3000)
                    + " per confirmed saturation, ceiling=" + integer(config,
                    "deepseek.chat.audit-decision.adaptive-max-tokens.ceiling", 15000)
                    + ", maxAttempts=" + integer(config,
                    "deepseek.chat.route-decision.retry-times", 2));
            System.out.println("Retrieval snapshot = " + subset.retrievalSnapshotId());
            System.out.println("Primary variants = " + variants.stream().map(PaperExperimentVariant::name).toList());
            System.out.println("ReAct = DISABLED FOR THIS RUN");
            System.out.println("Human Evaluation = DISABLED");
            System.out.println("Current Git commit = " + git.commit());
            System.out.println("Dirty working tree = " + git.dirty());
            System.out.println("RETRIEVAL_FORMAL PREFLIGHT PASSED");
        } catch (RuntimeException failed) {
            System.out.println("RETRIEVAL_FORMAL PREFLIGHT FAILED");
            System.out.println("Blocker = " + failed.getMessage());
            throw failed;
        }
    }

    private JSONArray enabledVariantManifest(JSONArray all, List<PaperExperimentVariant> enabled) {
        Set<String> names = Set.copyOf(enabled.stream().map(PaperExperimentVariant::name).toList());
        JSONArray selected = new JSONArray();
        for (Object raw : all) {
            JSONObject row = (JSONObject) raw;
            if (names.contains(row.getString("variantName"))) {
                row.put("enabledForRun", true);
                selected.add(row);
            }
        }
        return selected;
    }

    private void finalizeDecisionManifest(JSONObject manifest, Path directory,
                                          List<PaperExperimentCase> selected,
                                          List<PaperExperimentVariant> variants,
                                          List<PaperExperimentResult> results,
                                          JSONObject decisionMetrics,
                                          ExperimentDeepSeekClient baseline,
                                          ExperimentToolBoundary tools,
                                          Instant started) throws Exception {
        Instant finished = Instant.now();
        Path selectedFile = directory.resolve("selected_queries.jsonl");
        Path decisionFile = directory.resolve("decision_results.jsonl");
        manifest.put("finishedAt", finished.toString());
        manifest.put("durationMs", java.time.Duration.between(started, finished).toMillis());
        manifest.put("selectedQueriesRowCount", countLines(selectedFile));
        manifest.put("selectedQueriesUniqueQueryCount", selected.stream()
                .map(PaperExperimentCase::queryId).distinct().count());
        manifest.put("selectedQueriesSha256", sha256(selectedFile));
        manifest.put("decisionResultsRowCount", countLines(decisionFile));
        manifest.put("decisionResultsSha256", sha256(decisionFile));
        manifest.put("expectedDecisionResultsRows", (long) selected.size() * variants.size());
        manifest.put("realDeepSeekCalls", realDeepSeekCalls(results, List.of(), baseline));
        manifest.put("successfulBaselineDeepSeekCalls", baseline.successfulCalls());
        manifest.put("failedBaselineDeepSeekAttempts", baseline.failedCalls());
        manifest.put("actualRealDbCalls", tools.realDbCalls());
        manifest.put("actualRealTavilyCalls", tools.realWebCalls());
        manifest.put("formalRunValidity", tools.realDbCalls() == 0 && tools.realWebCalls() == 0
                ? "VALID" : "INVALID_FORMAL_RUN");
        manifest.put("metricsDenominators", metricDenominators(decisionMetrics));
    }

    private JSONObject metricDenominators(JSONObject metrics) {
        JSONObject output = new JSONObject(true);
        JSONObject variants = metrics.getJSONObject("variants");
        if (variants == null) return output;
        for (String name : variants.keySet()) {
            JSONObject row = variants.getJSONObject(name);
            JSONObject value = new JSONObject(true);
            value.put("action", row.getJSONObject("action").getIntValue("evaluated"));
            value.put("initialRoute", row.getJSONObject("initialRoute").getIntValue("evaluated"));
            output.put(name, value);
        }
        return output;
    }

    private void decisionFormalPostflight(Path directory,
                                          PaperExperimentModeGate.EffectiveConfiguration effective,
                                          PaperExperimentDatasetReader.Dataset dataset,
                                          List<PaperExperimentCase> selected,
                                          List<PaperExperimentVariant> variants,
                                          List<PaperExperimentResult> results,
                                          JSONObject decisionMetrics,
                                          ExperimentToolBoundary tools,
                                          ExperimentDeepSeekClient baseline) throws Exception {
        require(effective.mode() == PaperExperimentRunMode.DECISION_FORMAL, "Postflight mode mismatch");
        require(dataset.validCases().size() == 10_000 && selected.size() == 10_000,
                "Postflight dataset count mismatch");
        require(selected.stream().map(PaperExperimentCase::queryId).distinct().count() == 10_000,
                "Postflight duplicate query IDs detected");
        require(results.size() == selected.size() * variants.size(), "Decision result row count mismatch");
        require(realDeepSeekCalls(results, List.of(), baseline) > 0, "No real DeepSeek calls were observed");
        require(tools.realDbCalls() == 0, "INVALID_FORMAL_RUN: real DB call observed");
        require(tools.realWebCalls() == 0, "INVALID_FORMAL_RUN: real Tavily call observed");
        for (PaperExperimentVariant variant : variants) {
            int evaluated = decisionMetrics.getJSONObject("variants").getJSONObject(variant.name())
                    .getJSONObject("action").getIntValue("evaluated");
            require(evaluated == 10_000, "Action metrics denominator mismatch for " + variant.name());
        }
        long expectedFailures = results.stream().filter(r -> !r.success).count();
        require(countLines(directory.resolve("failed_cases.jsonl")) == expectedFailures,
                "failed_cases.jsonl does not match failed results");
        for (String required : List.of("experiment_manifest.json", "selected_queries.jsonl",
                "variant_manifest.json", "decision_results.jsonl", "decision_metrics.json",
                "decision_confusion_matrix.json", "decision_pairwise_analysis.json",
                "decision_error_cases.csv", "efficiency_metrics.json", "failed_cases.jsonl",
                "LABEL_COMPATIBILITY_REVIEW.csv", "DECISION_FORMAL_REPORT.md")) {
            require(Files.isRegularFile(directory.resolve(required)), "Missing output file: " + required);
        }
    }

    private JSONObject retrievalFormalIntegrityAudit(Path directory,
                                                      PaperExperimentDatasetReader.Dataset dataset,
                                                      List<PaperExperimentCase> selected,
                                                      List<PaperExperimentVariant> variants,
                                                      List<PaperExperimentResult> results,
                                                      ExperimentToolBoundary tools,
                                                      PaperRetrievalSubsetLoader.FrozenSubset subset) throws Exception {
        Path checkpoint = directory.resolve("formal_results.inprogress.jsonl");
        long expectedRows = (long) selected.size() * variants.size();
        Set<String> expectedIds = new LinkedHashSet<>(selected.stream().map(PaperExperimentCase::queryId).toList());
        Set<String> expectedVariants = new LinkedHashSet<>(variants.stream().map(PaperExperimentVariant::name).toList());
        Map<String, PaperExperimentCase> datasetById = dataset.validCases().stream().collect(
                java.util.stream.Collectors.toMap(PaperExperimentCase::queryId, v -> v,
                        (a, b) -> a, LinkedHashMap::new));
        Set<String> pairs = new LinkedHashSet<>();
        Map<String, Integer> variantCounts = new LinkedHashMap<>();
        long rows = 0, parseErrors = 0, duplicates = 0, unknownIds = 0, unknownVariants = 0, goldMismatches = 0;
        if (Files.isRegularFile(checkpoint)) {
            try (java.io.BufferedReader reader = Files.newBufferedReader(checkpoint, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    rows++;
                    try {
                        JSONObject row = JSON.parseObject(line);
                        if (row == null) throw new IllegalArgumentException("not an object");
                        String queryId = row.getString("queryId");
                        String variant = row.getString("variant");
                        if (!expectedIds.contains(queryId)) unknownIds++;
                        if (!expectedVariants.contains(variant)) unknownVariants++;
                        if (!pairs.add(queryId + "\u0000" + variant)) duplicates++;
                        variantCounts.merge(variant, 1, Integer::sum);
                        PaperExperimentCase source = datasetById.get(queryId);
                        if (source == null || !java.util.Objects.equals(source.goldAction(), row.getString("goldAction"))
                                || !java.util.Objects.equals(source.goldRoute(), row.getString("goldRoute"))) {
                            goldMismatches++;
                        }
                    } catch (RuntimeException invalid) {
                        parseErrors++;
                    }
                }
            }
        }
        long missingPairs = 0;
        for (String id : expectedIds) for (String variant : expectedVariants) {
            if (!pairs.contains(id + "\u0000" + variant)) missingPairs++;
        }
        long tracedDbCalls = results.stream().flatMap(r -> r.toolCalls.stream())
                .filter(v -> !"web_search".equals(v.getString("toolName")))
                .filter(v -> Boolean.TRUE.equals(v.getBoolean("realProviderCalled"))).count();
        long tracedTavilyPhysical = results.stream().mapToLong(r -> r.tavilyPhysicalAttempts).sum();
        JSONObject usage = tools.tavilyUsageSnapshot();
        long grantedReservations = usage.getLongValue("grantedPhysicalReservations");
        boolean integrity = Files.isRegularFile(checkpoint)
                && rows == expectedRows && parseErrors == 0 && duplicates == 0 && missingPairs == 0
                && unknownIds == 0 && unknownVariants == 0 && goldMismatches == 0
                && expectedIds.size() == PaperRetrievalFormalSpec.SUBSET_SIZE
                && dataset.validCases().size() == PaperRetrievalFormalSpec.DATASET_VALID_ROWS
                && PaperRetrievalFormalSpec.DATASET_SHA256.equalsIgnoreCase(dataset.sha256())
                && PaperRetrievalFormalSpec.PRIMARY_VARIANTS.equals(expectedVariants)
                && results.size() == expectedRows
                && tools.realWebCalls() <= PaperRetrievalFormalSpec.TAVILY_PHYSICAL_CALL_BUDGET
                && tracedDbCalls == tools.realDbCalls()
                && tracedTavilyPhysical == tools.realWebCalls()
                && grantedReservations == tools.realWebCalls()
                && subset.manifestSha256().equalsIgnoreCase(RETRIEVAL_FORMAL_SUBSET_SHA256);

        JSONObject audit = new JSONObject(true);
        audit.put("runId", directory.getFileName().toString());
        audit.put("experimentMode", "RETRIEVAL_FORMAL");
        audit.put("checkpointRows", rows); audit.put("expectedRows", expectedRows);
        audit.put("jsonParseErrors", parseErrors);
        audit.put("uniqueQueryIds", pairs.stream().map(v -> v.substring(0, v.indexOf('\u0000'))).distinct().count());
        audit.put("expectedUniqueQueryIds", PaperRetrievalFormalSpec.SUBSET_SIZE);
        audit.put("uniqueQueryVariantPairs", pairs.size());
        audit.put("duplicateQueryVariantPairs", duplicates); audit.put("missingVariantPairs", missingPairs);
        audit.put("unknownQueryIds", unknownIds); audit.put("unknownVariants", unknownVariants);
        audit.put("variantRowCounts", variantCounts); audit.put("goldSnapshotMismatches", goldMismatches);
        audit.put("datasetValidRows", dataset.validCases().size()); audit.put("datasetSha256", dataset.sha256());
        audit.put("datasetShaMatches", PaperRetrievalFormalSpec.DATASET_SHA256.equalsIgnoreCase(dataset.sha256()));
        audit.put("subsetManifestShaMatches", subset.manifestSha256().equalsIgnoreCase(RETRIEVAL_FORMAL_SUBSET_SHA256));
        audit.put("realDbCallsObserved", tools.realDbCalls());
        audit.put("tracedRealDbCalls", tracedDbCalls);
        audit.put("realTavilyPhysicalCallsObserved", tools.realWebCalls());
        audit.put("tracedTavilyPhysicalCalls", tracedTavilyPhysical);
        audit.put("grantedTavilyBudgetReservations", grantedReservations);
        audit.put("tavilyBudgetLimit", tools.tavilyBudgetLimit());
        audit.put("failedResults", results.stream().filter(r -> !r.success
                || (r.status != null && r.status.startsWith("INCOMPLETE"))).count());
        audit.put("integrityPassed", integrity);
        return audit;
    }

    private void finalizeRetrievalManifest(JSONObject manifest, Path directory,
                                           List<PaperExperimentResult> results,
                                           ExperimentToolBoundary tools,
                                           PaperRetrievalSubsetLoader.FrozenSubset subset,
                                           Instant started, JSONObject integrity) throws Exception {
        Instant finished = Instant.now();
        manifest.put("finishedAt", finished.toString());
        manifest.put("durationMs", java.time.Duration.between(started, finished).toMillis());
        manifest.put("status", "COMPLETED_WITH_POSTFLIGHT");
        manifest.put("usableForPaperMetrics", integrity.getBooleanValue("integrityPassed"));
        manifest.put("predictionCheckpointComplete", integrity.getBooleanValue("integrityPassed"));
        manifest.put("postflightIntegrityPassed", integrity.getBooleanValue("integrityPassed"));
        manifest.put("subsetManifestSha256", subset.manifestSha256());
        manifest.put("retrievalSnapshotId", subset.retrievalSnapshotId());
        manifest.put("formalResultsRows", countLines(directory.resolve("formal_results.jsonl")));
        manifest.put("formalResultsSha256", sha256(directory.resolve("formal_results.jsonl")));
        manifest.put("checkpointSha256", sha256(directory.resolve("formal_results.inprogress.jsonl")));
        manifest.put("actualRealDbCalls", tools.realDbCalls());
        manifest.put("actualRealTavilyPhysicalCalls", tools.realWebCalls());
        manifest.put("logicalWebCalls", tools.logicalWebCalls());
        manifest.put("tavilyCacheHits", tools.webCacheHits());
        manifest.put("tavilyCacheMisses", tools.tavilyCacheMisses());
        manifest.put("tavilyBudgetExceededCount", tools.tavilyBudgetExceededCount());
        manifest.put("remainingTavilyBudget", tools.tavilyBudgetRemaining());
        manifest.put("failedResults", results.stream().filter(r -> !r.success
                || (r.status != null && r.status.startsWith("INCOMPLETE"))).count());
    }

    private void retrievalFormalFinalChecks(Path directory, List<PaperExperimentCase> selected,
                                            List<PaperExperimentVariant> variants,
                                            List<PaperExperimentResult> results,
                                            ExperimentToolBoundary tools,
                                            PaperRetrievalSubsetLoader.FrozenSubset subset,
                                            JSONObject integrity) throws Exception {
        require(integrity.getBooleanValue("integrityPassed"), "Retrieval postflight integrity did not pass");
        require(selected.size() == 300 && selected.stream().map(PaperExperimentCase::queryId).distinct().count() == 300,
                "Final frozen subset identity check failed");
        require(results.size() == (long) selected.size() * variants.size(), "Final result-row count mismatch");
        require(PaperRetrievalFormalSpec.PRIMARY_VARIANTS.equals(
                Set.copyOf(variants.stream().map(PaperExperimentVariant::name).toList())),
                "Final Variant-set check failed");
        require(tools.realWebCalls() <= 700, "INVALID_FORMAL_RUN: Tavily physical budget exceeded");
        require(subset.manifestSha256().equalsIgnoreCase(RETRIEVAL_FORMAL_SUBSET_SHA256),
                "Final subset hash check failed");
        for (String required : List.of("retrieval_formal_subset_manifest.json", "experiment_manifest.json",
                "variant_manifest.json", "selected_queries.jsonl", "formal_results.inprogress.jsonl",
                "formal_results.jsonl", "retrieval_results.jsonl", "answer_results.jsonl",
                "tool_calls.jsonl", "source_evidence.jsonl", "retrieval_metrics.json",
                "answer_execution_metrics.json", "efficiency_metrics.json", "ablation_metrics.json",
                "paired_analysis.json", "failed_cases.jsonl", "tavily_usage_report.json",
                "cache_manifest.json", "POSTFLIGHT_INTEGRITY_AUDIT.json", "RETRIEVAL_FORMAL_REPORT.md")) {
            require(Files.isRegularFile(directory.resolve(required)), "Missing retrieval output: " + required);
        }
    }

    private void assertProviderInvariants(PaperExperimentModeGate.EffectiveConfiguration effective,
                                          ExperimentToolBoundary tools) {
        if (effective.mode() == PaperExperimentRunMode.ROUTING_SMOKE
                || effective.mode() == PaperExperimentRunMode.DECISION_FORMAL) {
            assertEquals(0, tools.realDbCalls(), effective.mode() + " real DB calls must be zero");
            assertEquals(0, tools.realWebCalls(), effective.mode() + " real Tavily calls must be zero");
        }
        assertTrue(tools.realWebCalls() <= effective.tavilyBudget(), "Tavily hard budget exceeded");
    }

    private void writeModeReport(Path directory, PaperExperimentModeGate.EffectiveConfiguration effective,
                                 PaperExperimentDatasetReader.Dataset dataset,
                                 List<PaperExperimentCase> selected,
                                 List<PaperExperimentVariant> variants,
                                 List<PaperExperimentResult> results,
                                 ExperimentDeepSeekClient llm, ExperimentToolBoundary tools,
                                 PaperRetrievalSubsetLoader.FrozenSubset subset) throws Exception {
        long failures = results.stream().filter(r -> !r.success).count();
        long incomplete = results.stream().filter(r -> r.status != null && r.status.startsWith("INCOMPLETE")).count();
        String reportName = switch (effective.mode()) {
            case DECISION_FORMAL -> "DECISION_FORMAL_REPORT.md";
            case RETRIEVAL_FORMAL -> "RETRIEVAL_FORMAL_REPORT.md";
            default -> "MODE_SMOKE_REPORT.md";
        };
        String text = "# " + effective.mode() + " Report\n\n"
                + (effective.mode().validationOnly() ? PaperExperimentContext.VALIDATION_MARKER
                : PaperExperimentContext.FORMAL_MARKER) + "\n\n"
                + "- Dataset rows: " + dataset.totalRows() + "\n"
                + "- Selected queries: " + selected.size() + "\n"
                + "- Variants: " + variants.stream().map(PaperExperimentVariant::name).toList() + "\n"
                + "- Attempts: " + results.size() + "\n"
                + "- Failures: " + failures + "\n"
                + "- Incomplete: " + incomplete + "\n"
                + "- Frozen subset SHA-256: " + (subset == null ? "N/A" : subset.manifestSha256()) + "\n"
                + "- Real baseline DeepSeek calls: " + llm.physicalCalls() + "\n"
                + "- Real DB calls: " + tools.realDbCalls() + "\n"
                + "- Tavily budget limit: " + tools.tavilyBudgetLimit() + "\n"
                + "- Actual Tavily calls: " + tools.realWebCalls() + "\n"
                + "- Remaining allowed Tavily calls: " + tools.tavilyBudgetRemaining() + "\n"
                + "- Tavily budget exceeded count: " + tools.tavilyBudgetExceededCount() + "\n"
                + "- Tavily cache hits: " + tools.webCacheHits() + "\n";
        Files.writeString(directory.resolve(reportName), text, StandardCharsets.UTF_8);
    }

    private JSONObject branchManifest(List<PaperExperimentCase> branch) {
        JSONObject value = new JSONObject(true);
        value.put("marker", PaperExperimentContext.VALIDATION_MARKER);
        value.put("purpose", "ROUTING_BRANCH_COVERAGE_SMOKE_ONLY_NOT_POPULATION_METRICS");
        value.put("perClassTarget", BRANCH_SAMPLES_PER_CLASS);
        value.put("randomSeed", RANDOM_SEED);
        value.put("queryIds", branch.stream().map(PaperExperimentCase::queryId).toList());
        return value;
    }

    private List<JSONObject> labelCompatibilityReview(List<PaperExperimentCase> cases) {
        List<JSONObject> rows = new ArrayList<>();
        for (PaperExperimentCase item : cases) {
            boolean badAction = !PaperExperimentMetrics.ACTION_LABELS.contains(item.goldAction());
            boolean badRoute = "RETRIEVE".equals(item.goldAction())
                    && !PaperExperimentMetrics.ROUTE_LABELS.contains(item.goldRoute());
            if (!badAction && !badRoute) continue;
            JSONObject row = new JSONObject(true);
            row.put("queryId", item.queryId());
            row.put("query", item.query());
            row.put("oldGoldAction", item.goldAction());
            row.put("oldGoldRoute", item.goldRoute());
            row.put("suspectedIssue", badAction ? "ACTION_LABEL_OUTSIDE_FIXED_SET"
                    : "RETRIEVE_ROUTE_OUTSIDE_FIXED_SET");
            row.put("reason", "Manual compatibility review required; Gold was not changed");
            rows.add(row);
        }
        return rows;
    }

    private void printSummary(Path directory, PaperExperimentModeGate.EffectiveConfiguration effective,
                              List<PaperExperimentCase> selected, List<PaperExperimentVariant> variants,
                              List<PaperExperimentResult> results, ExperimentDeepSeekClient llm,
                              ExperimentToolBoundary tools) {
        System.out.println("=== " + effective.mode() + " ===");
        if (effective.mode().validationOnly()) {
            System.out.println("VALIDATION / SMOKE TEST");
            System.out.println("NOT FORMAL PAPER RESULT");
        }
        System.out.println("Selected queries=" + selected.size() + ", variants=" + variants.size()
                + ", attempts=" + results.size());
        System.out.println("Real baseline DeepSeek calls=" + llm.physicalCalls());
        System.out.println("Real DB calls=" + tools.realDbCalls());
        System.out.println("Real Tavily calls=" + tools.realWebCalls());
        System.out.println("Tavily cache hits=" + tools.webCacheHits());
        System.out.println("Output=" + directory);
    }

    private ExperimentDeepSeekClient baselineClient(Properties config) {
        return new ExperimentDeepSeekClient(required(config, "deepseek.api.url"),
                required(config, "deepseek.api.key"), required(config, "deepseek.model"),
                decimal(config, "deepseek.chat.temperature", 0.7D),
                integer(config, "deepseek.chat.route-decision.max-tokens", 1200),
                integer(config, "deepseek.chat.retry-times", 2),
                integer(config, "deepseek.chat.retry-backoff-ms", 300),
                integer(config, "deepseek.timeout.connect-ms", 5000),
                integer(config, "deepseek.timeout.read-ms", 15000));
    }

    private Properties configuration() {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource("application.yml"));
        Properties value = factory.getObject();
        if (value == null) throw new IllegalStateException("application.yml could not be read");
        return value;
    }

    private long realDeepSeekCalls(List<PaperExperimentResult> results,
                                   List<PaperExperimentResult> branchResults,
                                   ExperimentDeepSeekClient baseline) {
        java.util.Set<String> frozenEventIds = new java.util.LinkedHashSet<>();
        List<PaperExperimentResult> all = new ArrayList<>(results);
        all.addAll(branchResults);
        for (PaperExperimentResult result : all) {
            for (JSONObject call : result.llmCalls) {
                String eventId = call.getString("eventId");
                if (eventId != null && !eventId.isBlank()) frozenEventIds.add(eventId);
            }
        }
        return baseline.physicalCalls() + frozenEventIds.size();
    }

    private GitState gitState() throws Exception {
        String commit = process("git", "rev-parse", "HEAD");
        String branch = process("git", "branch", "--show-current");
        boolean dirty = !process("git", "status", "--porcelain", "--untracked-files=all").isBlank();
        return new GitState(commit, branch, dirty);
    }

    private String process(String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(PROJECT_ROOT.toFile())
                .redirectErrorStream(true).start();
        Charset processCharset = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? Charset.forName("GBK") : StandardCharsets.UTF_8;
        String output = new String(process.getInputStream().readAllBytes(), processCharset).trim();
        if (process.waitFor() != 0) throw new IllegalStateException("Command failed: " + command[0]);
        return output;
    }

    private JSONObject experimentLayerHashes() throws Exception {
        JSONObject hashes = new JSONObject(true);
        for (String relative : List.of(
                "src/test/java/com/nju/cssci/论文/PaperFormalExperimentRunner.java",
                "src/test/java/com/nju/cssci/论文/PaperExperimentRunMode.java",
                "src/test/java/com/nju/cssci/论文/PaperExperimentModeGate.java",
                "src/test/java/com/nju/cssci/论文/PaperExperimentMetrics.java",
                "src/test/java/com/nju/cssci/论文/PaperDecisionFormalAnalysis.java",
                "src/test/java/com/nju/cssci/论文/PaperRetrievalFormalSpec.java",
                "src/test/java/com/nju/cssci/论文/PaperRetrievalSubsetLoader.java",
                "src/test/java/com/nju/cssci/论文/PaperRetrievalFormalAnalysis.java",
                "src/test/java/com/nju/cssci/论文/retrieval_formal_subset_manifest.json",
                "src/test/java/com/nju/cssci/论文/variants/PaperVariants.java",
                "src/test/java/com/nju/cssci/论文/support/FrozenExperimentHarness.java",
                "src/test/java/com/nju/cssci/论文/support/ExperimentDeepSeekClient.java",
                "src/test/java/com/nju/cssci/论文/support/ExperimentToolBoundary.java",
                "src/test/java/com/nju/cssci/论文/support/ExperimentTavilyBudget.java",
                "src/test/java/com/nju/cssci/论文/support/ExperimentTavilyCache.java")) {
            Path path = PROJECT_ROOT.resolve(relative).normalize();
            require(path.startsWith(PROJECT_ROOT) && Files.isRegularFile(path),
                    "Experiment-layer identity file missing: " + relative);
            hashes.put(relative, sha256(path));
        }
        return hashes;
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

    private static long countLines(Path path) throws Exception {
        try (java.util.stream.Stream<String> lines = Files.lines(path, StandardCharsets.UTF_8)) {
            return lines.count();
        }
    }

    private static boolean systemicProviderFailure(Throwable error) {
        String type = error.getClass().getName().toLowerCase(Locale.ROOT);
        String message = safe(error.getMessage());
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return type.contains("unauthorized") || type.contains("forbidden") || type.contains("unknownhost")
                || normalized.contains("401 unauthorized") || normalized.contains("403 forbidden")
                || normalized.contains("invalid api key") || normalized.contains("authentication failed")
                || normalized.contains("permission denied: connect") || normalized.contains("unknown host");
    }

    private void silenceSensitiveLogging() {
        for (String name : List.of("org.springframework.beans.factory.config.YamlPropertiesFactoryBean",
                "org.springframework.test.util.ReflectionTestUtils",
                "org.springframework.web.client.RestTemplate", "org.springframework.web.HttpLogging",
                "org.apache.poi")) {
            Object logger = LoggerFactory.getLogger(name);
            if (logger instanceof ch.qos.logback.classic.Logger logback) {
                logback.setLevel(ch.qos.logback.classic.Level.WARN);
            }
        }
    }

    private static Throwable root(Throwable value) {
        Throwable current = value;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current;
    }

    private static String safe(String value) {
        if (value == null) return null;
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    private static String required(Properties values, String key) {
        String value = values.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing config: " + key);
        return value;
    }

    private static int integer(Properties values, String key, int fallback) {
        try { return Integer.parseInt(values.getProperty(key, String.valueOf(fallback))); }
        catch (Exception ignored) { return fallback; }
    }

    private static double decimal(Properties values, String key, double fallback) {
        try { return Double.parseDouble(values.getProperty(key, String.valueOf(fallback))); }
        catch (Exception ignored) { return fallback; }
    }

    private static boolean bool(Properties values, String key, boolean fallback) {
        String value = values.getProperty(key);
        return value == null ? fallback : Boolean.parseBoolean(value);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    private record GitState(String commit, String branch, boolean dirty) { }
}
