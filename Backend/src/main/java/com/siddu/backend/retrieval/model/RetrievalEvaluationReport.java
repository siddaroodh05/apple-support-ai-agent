package com.siddu.backend.retrieval.model;

import java.util.List;
import java.util.Map;

public record RetrievalEvaluationReport(
        int evaluationCases,
        int successfullyEvaluated,
        int failedCases,
        int k,
        String datasetFile,
        Metrics overall,
        Map<String, IntentMetrics> perIntent,
        List<Confusion> confusionPairs,
        Map<String, Map<String, Integer>> confusionMatrix,
        RetrievalStatistics retrievalStatistics,
        Map<String, Integer> correctIntentRankDistribution,
        Validation validation,
        List<Error> errors) {

    public record Metrics(
            Metric top1,
            Metric top3,
            Metric top5,
            Metric top10,
            Metric topK) {
    }

    public record Metric(int correct, int total, double rate) {
    }

    public record IntentMetrics(
            int caseCount,
            int top1Correct,
            double top1Accuracy,
            int top3Correct,
            Double top3Recall,
            int top5Correct,
            Double top5Recall,
            int top10Correct,
            Double top10Recall,
            int topKCorrect,
            double topKRecall) {
    }

    public record Confusion(String goldIntent, String retrievedIntent, int count) {
    }

    public record RetrievedResult(
            int rank,
            String tweetId,
            String intent,
            double similarity,
            String customerMessage) {
    }

    public record EvaluationCase(
            String tweetId,
            String customerMessage,
            String goldIntent,
            String top1Intent,
            Double top1Similarity,
            Integer correctIntentRank,
            boolean top1Correct,
            Boolean top3Correct,
            Boolean top5Correct,
            Boolean top10Correct,
            boolean topKCorrect,
            List<RetrievedResult> retrievedResults) {
    }

    public record Failure(
            String tweetId,
            String customerMessage,
            String goldIntent,
            String top1Intent,
            Double top1Similarity,
            Integer correctIntentRank,
            List<RetrievedResult> retrievedResults) {
    }

    public record RetrievalStatistics(
            Double averageTop1Similarity,
            Double minTop1Similarity,
            Double maxTop1Similarity,
            Double averageCorrectIntentRank) {
    }

    public record Validation(int selfRetrievalCount, int duplicateRetrievedIds) {
    }

    public record Error(String tweetId, String message) {
    }
}