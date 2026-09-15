package com.siddu.backend.retrieval.service;

import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import com.siddu.backend.retrieval.config.EvaluationProperties;
import com.siddu.backend.retrieval.model.RetrievalEvaluationReport;
import com.siddu.backend.retrieval.model.RetrievedSupportCase;
import com.siddu.backend.retrieval.repository.SupportCaseRepository;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

@Service
public class RetrievalEvaluationService {

    private static final List<String> INTENTS = List.of(
            "SOFTWARE_ISSUE", "HARDWARE_ISSUE", "ACCOUNT_DATA_ISSUE", "HOW_TO_QUESTION",
            "PURCHASE_BILLING", "THIRD_PARTY_APP_ISSUE", "GENERAL_COMPLAINT_VAGUE",
            "NO_ACTIONABLE_CONTENT");
    private static final CsvSchema INPUT_SCHEMA = CsvSchema.emptySchema().withHeader();

    private final EvaluationProperties properties;
    private final EmbeddingService embeddingService;
    private final SupportCaseRepository repository;
    private final CsvMapper csvMapper = new CsvMapper();

    public RetrievalEvaluationService(
            EvaluationProperties properties,
            EmbeddingService embeddingService,
            SupportCaseRepository repository) {
        this.properties = properties;
        this.embeddingService = embeddingService;
        this.repository = repository;
    }

    public RetrievalEvaluationReport evaluate(int k) {
        if (k <= 0) {
            throw new IllegalArgumentException("Evaluation k must be positive");
        }
        List<GoldCase> goldCases = loadGoldCases();
        List<RetrievalEvaluationReport.EvaluationCase> cases = new ArrayList<>();
        List<RetrievalEvaluationReport.Error> errors = new ArrayList<>();
        Map<String, Integer> top1Counts = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> confusionMatrix = emptyConfusionMatrix();
        int selfRetrievalCount = 0;
        int duplicateRetrievedIds = 0;

        for (GoldCase goldCase : goldCases) {
            try {
                List<RetrievedSupportCase> retrieved = repository.findMostSimilarExcludingTweetId(
                        embeddingService.embed(goldCase.customerMessage()), k, goldCase.tweetId());
                Set<String> retrievedIds = new HashSet<>();
                for (RetrievedSupportCase result : retrieved) {
                    if (goldCase.tweetId().equals(result.tweetId())) {
                        selfRetrievalCount++;
                        throw new IllegalStateException("Retrieved the evaluation case itself");
                    }
                    if (!retrievedIds.add(result.tweetId())) {
                        duplicateRetrievedIds++;
                    }
                }
                RetrievalEvaluationReport.EvaluationCase evaluationCase = evaluateCase(goldCase, retrieved, k);
                cases.add(evaluationCase);
                if (evaluationCase.top1Intent() != null && isKnownIntent(evaluationCase.top1Intent())) {
                    confusionMatrix.get(canonicalIntent(goldCase.goldIntent()))
                            .merge(canonicalIntent(evaluationCase.top1Intent()), 1, Integer::sum);
                }
                if (!evaluationCase.top1Correct() && evaluationCase.top1Intent() != null) {
                    top1Counts.merge(confusionKey(normalizeIntent(goldCase.goldIntent()),
                            normalizeIntent(evaluationCase.top1Intent())), 1, Integer::sum);
                }
            } catch (RuntimeException exception) {
                errors.add(new RetrievalEvaluationReport.Error(goldCase.tweetId(), exception.getMessage()));
            }
        }

        return buildReport(goldCases.size(), k, resolvePath(properties.goldFile()).toString(), cases, top1Counts,
                confusionMatrix, selfRetrievalCount, duplicateRetrievedIds, errors);
    }

    private RetrievalEvaluationReport buildReport(
            int evaluationCaseCount,
            int k,
            String datasetFile,
            List<RetrievalEvaluationReport.EvaluationCase> cases,
            Map<String, Integer> top1Counts,
            Map<String, Map<String, Integer>> confusionMatrix,
            int selfRetrievalCount,
            int duplicateRetrievedIds,
            List<RetrievalEvaluationReport.Error> errors) {
        long top1 = cases.stream().filter(RetrievalEvaluationReport.EvaluationCase::top1Correct).count();
        long top3 = count(cases, RetrievalEvaluationReport.EvaluationCase::top3Correct);
        long top5 = count(cases, RetrievalEvaluationReport.EvaluationCase::top5Correct);
        long top10 = count(cases, RetrievalEvaluationReport.EvaluationCase::top10Correct);
        long topK = cases.stream().filter(RetrievalEvaluationReport.EvaluationCase::topKCorrect).count();
        RetrievalEvaluationReport.Metrics overall = metrics(cases.size(), top1, top3, top5, top10, topK, k);

        Map<String, RetrievalEvaluationReport.IntentMetrics> perIntent = new LinkedHashMap<>();
        for (String intent : INTENTS) {
                List<RetrievalEvaluationReport.EvaluationCase> intentCases = cases.stream()
                    .filter(evaluationCase -> normalizeIntent(intent).equals(normalizeIntent(evaluationCase.goldIntent())))
                    .toList();
                int intentTop1 = (int) intentCases.stream().filter(RetrievalEvaluationReport.EvaluationCase::top1Correct).count();
                int intentTop3 = (int) count(intentCases, RetrievalEvaluationReport.EvaluationCase::top3Correct);
                int intentTop5 = (int) count(intentCases, RetrievalEvaluationReport.EvaluationCase::top5Correct);
                int intentTop10 = (int) count(intentCases, RetrievalEvaluationReport.EvaluationCase::top10Correct);
                int intentTopK = (int) intentCases.stream().filter(RetrievalEvaluationReport.EvaluationCase::topKCorrect).count();
            perIntent.put(intent, new RetrievalEvaluationReport.IntentMetrics(
                    intentCases.size(),
                    intentTop1, rate(intentTop1, intentCases.size()), intentTop3,
                    k >= 3 ? rate(intentTop3, intentCases.size()) : null, intentTop5,
                    k >= 5 ? rate(intentTop5, intentCases.size()) : null, intentTop10,
                    k >= 10 ? rate(intentTop10, intentCases.size()) : null, intentTopK,
                    rate(intentTopK, intentCases.size())));
        }

        List<RetrievalEvaluationReport.Confusion> confusions = top1Counts.entrySet().stream()
                .map(entry -> {
                        String[] parts = entry.getKey().split("\\u0000", 2);
                        return new RetrievalEvaluationReport.Confusion(parts[0].toUpperCase(Locale.ROOT),
                            parts[1].toUpperCase(Locale.ROOT), entry.getValue());
                })
                .sorted(Comparator.comparingInt(RetrievalEvaluationReport.Confusion::count).reversed())
                .toList();
        return new RetrievalEvaluationReport(evaluationCaseCount, cases.size(), errors.size(), k, datasetFile, overall,
            perIntent, confusions, confusionMatrix, statistics(cases), rankDistribution(cases, k),
            new RetrievalEvaluationReport.Validation(selfRetrievalCount, duplicateRetrievedIds), errors);
    }

    private RetrievalEvaluationReport.EvaluationCase evaluateCase(
            GoldCase goldCase, List<RetrievedSupportCase> retrieved, int k) {
        List<RetrievalEvaluationReport.RetrievedResult> results = new ArrayList<>();
        Integer correctRank = null;
        for (int index = 0; index < retrieved.size(); index++) {
            RetrievedSupportCase result = retrieved.get(index);
            int rank = index + 1;
            results.add(new RetrievalEvaluationReport.RetrievedResult(rank, result.tweetId(), result.intent(),
                    result.similarity(), result.customerMessage()));
            if (correctRank == null && normalizeIntent(goldCase.goldIntent()).equals(normalizeIntent(result.intent()))) {
                correctRank = rank;
            }
        }
        String top1Intent = retrieved.isEmpty() ? null : retrieved.get(0).intent();
        Double top1Similarity = retrieved.isEmpty() ? null : retrieved.get(0).similarity();
        return new RetrievalEvaluationReport.EvaluationCase(
                goldCase.tweetId(), goldCase.customerMessage(), goldCase.goldIntent(), top1Intent, top1Similarity, correctRank,
                normalizeIntent(goldCase.goldIntent()).equals(normalizeIntent(top1Intent)), correctRank != null && correctRank <= 3,
                correctRank != null && correctRank <= 5, correctRank != null && correctRank <= 10,
                correctRank != null && correctRank <= k, results);
    }

    private List<GoldCase> loadGoldCases() {
        Path path = resolvePath(properties.goldFile());
        try (MappingIterator<Map<String, String>> rows = csvMapper.readerFor(Map.class)
                .with(INPUT_SCHEMA).readValues(path.toFile())) {
            List<GoldCase> cases = new ArrayList<>();
            while (rows.hasNext()) {
                Map<String, String> row = rows.next();
                cases.add(new GoldCase(required(row, "tweet_id"), required(row, "customer_message"), required(row, "gold_intent")));
            }
            return cases;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read evaluation gold file " + path, exception);
        }
    }

    private Path resolvePath(String configuredPath) {
        if (configuredPath == null || configuredPath.isBlank()) {
            throw new IllegalStateException("retrieval.evaluation.gold-file must be configured");
        }
        Path path = Paths.get(configuredPath);
        return path.isAbsolute() || Files.exists(path) ? path : Paths.get("..").resolve(path).normalize();
    }

    private String required(Map<String, String> row, String field) {
        String value = row.get(field);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing " + field + " in evaluation gold file");
        }
        return value;
    }

    private Map<String, Map<String, Integer>> emptyConfusionMatrix() {
        Map<String, Map<String, Integer>> matrix = new LinkedHashMap<>();
        for (String goldIntent : INTENTS) {
            Map<String, Integer> row = new LinkedHashMap<>();
            for (String retrievedIntent : INTENTS) {
                row.put(retrievedIntent, 0);
            }
            matrix.put(goldIntent, row);
        }
        return matrix;
    }

    private RetrievalEvaluationReport.Metrics metrics(
            long total, long top1, long top3, long top5, long top10, long topK, int k) {
        return new RetrievalEvaluationReport.Metrics(metric(top1, total), metric(top3, total, k >= 3),
                metric(top5, total, k >= 5), metric(top10, total, k >= 10), metric(topK, total));
    }

    private RetrievalEvaluationReport.Metric metric(long correct, long total) {
        return new RetrievalEvaluationReport.Metric((int) correct, (int) total, rate(correct, total));
    }

    private RetrievalEvaluationReport.Metric metric(long correct, long total, boolean meaningful) {
        return meaningful ? metric(correct, total) : null;
    }

    private long count(List<RetrievalEvaluationReport.EvaluationCase> cases,
                       Function<RetrievalEvaluationReport.EvaluationCase, Boolean> metric) {
        return cases.stream().filter(evaluationCase -> Boolean.TRUE.equals(metric.apply(evaluationCase))).count();
    }

    private double rate(long correct, long total) {
        return total == 0 ? 0.0 : (double) correct / total;
    }

    private String confusionKey(String goldIntent, String retrievedIntent) {
        return goldIntent + "\u0000" + retrievedIntent;
    }

    private String normalizeIntent(String intent) {
        return intent == null ? null : intent.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isKnownIntent(String intent) {
        return INTENTS.stream().anyMatch(known -> known.equalsIgnoreCase(normalizeIntent(intent)));
    }

    private String canonicalIntent(String intent) {
        return normalizeIntent(intent).toUpperCase(Locale.ROOT);
    }

    private RetrievalEvaluationReport.RetrievalStatistics statistics(
            List<RetrievalEvaluationReport.EvaluationCase> cases) {
        List<Double> similarities = cases.stream().map(RetrievalEvaluationReport.EvaluationCase::top1Similarity)
                .filter(java.util.Objects::nonNull).toList();
        List<Integer> ranks = cases.stream().map(RetrievalEvaluationReport.EvaluationCase::correctIntentRank)
                .filter(java.util.Objects::nonNull).toList();
        return new RetrievalEvaluationReport.RetrievalStatistics(
                similarities.stream().mapToDouble(Double::doubleValue).average().stream().boxed().findFirst().orElse(null),
                similarities.stream().mapToDouble(Double::doubleValue).min().stream().boxed().findFirst().orElse(null),
                similarities.stream().mapToDouble(Double::doubleValue).max().stream().boxed().findFirst().orElse(null),
                ranks.stream().mapToInt(Integer::intValue).average().stream().boxed().findFirst().orElse(null));
    }

    private Map<String, Integer> rankDistribution(List<RetrievalEvaluationReport.EvaluationCase> cases, int k) {
        Map<String, Integer> distribution = new LinkedHashMap<>();
        for (int rank = 1; rank <= k; rank++) {
            distribution.put(String.valueOf(rank), 0);
        }
        distribution.put("notFound", 0);
        for (RetrievalEvaluationReport.EvaluationCase evaluationCase : cases) {
            Integer rank = evaluationCase.correctIntentRank();
            if (rank == null) {
                distribution.merge("notFound", 1, Integer::sum);
            } else {
                distribution.merge(String.valueOf(rank), 1, Integer::sum);
            }
        }
        return distribution;
    }

    private record GoldCase(String tweetId, String customerMessage, String goldIntent) {
    }
}