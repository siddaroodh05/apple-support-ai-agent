package com.siddu.backend.retrieval.service;

import com.siddu.backend.retrieval.config.EvaluationProperties;
import com.siddu.backend.retrieval.model.RetrievedSupportCase;
import com.siddu.backend.retrieval.model.RetrievalEvaluationReport;
import com.siddu.backend.retrieval.repository.SupportCaseRepository;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetrievalEvaluationServiceTest {

    @Test
    void calculatesMetricsFailuresPerIntentAndConfusions() throws Exception {
        Path goldFile = Files.createTempFile("gold-evaluation", ".csv");
        Files.writeString(goldFile, "tweet_id,customer_message,support_response,intent,gold_intent\n"
                + "gold-1,software question,response,ignored,SOFTWARE_ISSUE\n"
                + "gold-2,hardware question,response,ignored,HARDWARE_ISSUE\n"
                + "gold-3,how to question,response,ignored,HOW_TO_QUESTION\n");

        EmbeddingService embeddingService = mock(EmbeddingService.class);
        SupportCaseRepository repository = mock(SupportCaseRepository.class);
        float[] embedding = {0.1f, 0.2f};
        when(embeddingService.embed(any())).thenReturn(embedding);
        when(repository.findMostSimilarExcludingTweetId(eq(embedding), eq(5), eq("gold-1")))
                .thenReturn(List.of(
                        result("1", "wrong", "hardware_issue", 0.95),
                        result("2", "correct", "software_issue", 0.90),
                        result("3", "other", "ACCOUNT_DATA_ISSUE", 0.80)));
        when(repository.findMostSimilarExcludingTweetId(eq(embedding), eq(5), eq("gold-2")))
                .thenReturn(List.of(
                        result("4", "wrong", "SOFTWARE_ISSUE", 0.95),
                        result("5", "other", "ACCOUNT_DATA_ISSUE", 0.90)));
        when(repository.findMostSimilarExcludingTweetId(eq(embedding), eq(5), eq("gold-3")))
                .thenReturn(List.of(result("6", "correct", "HOW_TO_QUESTION", 0.95)));

        RetrievalEvaluationReport report = new RetrievalEvaluationService(
                new EvaluationProperties(goldFile.toString()), embeddingService, repository).evaluate(5);

        assertEquals(3, report.evaluationCases());
        assertEquals(1, report.overall().top1().correct());
        assertEquals(1.0 / 3.0, report.overall().top1().rate());
        assertEquals(2, report.overall().top3().correct());
        assertEquals(2.0 / 3.0, report.overall().top3().rate());
        assertEquals(2.0 / 3.0, report.overall().top5().rate());
        assertEquals(2.0 / 3.0, report.overall().topK().rate());

        assertEquals(1, report.perIntent().get("SOFTWARE_ISSUE").caseCount());
        assertEquals(1, report.perIntent().get("SOFTWARE_ISSUE").top3Correct());
        assertEquals(1.0, report.perIntent().get("SOFTWARE_ISSUE").top3Recall());
        assertEquals(2, report.confusionPairs().size());
        assertEquals("SOFTWARE_ISSUE", report.confusionPairs().get(0).goldIntent());
        assertEquals("HARDWARE_ISSUE", report.confusionPairs().get(0).retrievedIntent());
        assertEquals(1, report.confusionPairs().get(0).count());
        assertEquals(1, report.confusionMatrix().get("SOFTWARE_ISSUE").get("HARDWARE_ISSUE"));
        verify(repository).findMostSimilarExcludingTweetId(eq(embedding), eq(5), eq("gold-1"));
        verify(repository).findMostSimilarExcludingTweetId(eq(embedding), eq(5), eq("gold-2"));
        verify(repository).findMostSimilarExcludingTweetId(eq(embedding), eq(5), eq("gold-3"));

        Files.deleteIfExists(goldFile);
    }

    @Test
    void omitsTopThreeAndTopFiveWhenKIsTooSmall() throws Exception {
        Path goldFile = Files.createTempFile("gold-evaluation", ".csv");
        Files.writeString(goldFile, "tweet_id,customer_message,support_response,intent,gold_intent\n"
                + "gold-1,message,response,ignored,SOFTWARE_ISSUE\n");
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        SupportCaseRepository repository = mock(SupportCaseRepository.class);
        float[] embedding = {0.1f};
        when(embeddingService.embed("message")).thenReturn(embedding);
        when(repository.findMostSimilarExcludingTweetId(embedding, 2, "gold-1")).thenReturn(List.of(
                result("1", "correct", "SOFTWARE_ISSUE", 0.9)));

        RetrievalEvaluationReport report = new RetrievalEvaluationService(
                new EvaluationProperties(goldFile.toString()), embeddingService, repository).evaluate(2);

        assertNull(report.overall().top3());
        assertNull(report.overall().top5());
        assertNull(report.perIntent().get("SOFTWARE_ISSUE").top3Recall());
        assertNull(report.perIntent().get("SOFTWARE_ISSUE").top5Recall());
        Files.deleteIfExists(goldFile);
    }

    @Test
    void recordsSelfRetrievalAsAnEvaluationError() throws Exception {
        Path goldFile = Files.createTempFile("gold-evaluation", ".csv");
        Files.writeString(goldFile, "tweet_id,customer_message,support_response,intent,gold_intent\n"
                + "gold-1,message,response,ignored,SOFTWARE_ISSUE\n");
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        SupportCaseRepository repository = mock(SupportCaseRepository.class);
        float[] embedding = {0.1f};
        when(embeddingService.embed("message")).thenReturn(embedding);
        when(repository.findMostSimilarExcludingTweetId(embedding, 2, "gold-1")).thenReturn(List.of(
                result("gold-1", "message", "SOFTWARE_ISSUE", 1.0)));

        RetrievalEvaluationReport report = new RetrievalEvaluationService(
                new EvaluationProperties(goldFile.toString()), embeddingService, repository).evaluate(2);

        assertEquals(1, report.evaluationCases());
        assertEquals(0, report.successfullyEvaluated());
        assertEquals(1, report.failedCases());
        assertEquals(1, report.validation().selfRetrievalCount());
        assertEquals(1, report.errors().size());
        Files.deleteIfExists(goldFile);
    }

    private static RetrievedSupportCase result(
            String tweetId, String customerMessage, String intent, double similarity) {
        return new RetrievedSupportCase(tweetId, customerMessage, "response", intent, similarity);
    }
}
