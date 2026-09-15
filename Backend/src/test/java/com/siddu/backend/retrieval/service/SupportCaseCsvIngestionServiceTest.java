package com.siddu.backend.retrieval.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.siddu.backend.retrieval.config.IngestionProperties;
import com.siddu.backend.retrieval.model.SupportCase;
import com.siddu.backend.retrieval.repository.SupportCaseRepository;

class SupportCaseCsvIngestionServiceTest {

    @Test
    void parsesRequiredCsvFieldsWithoutChangingMessage() {
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        when(embeddingService.embed("exact message, with punctuation"))
                .thenReturn(new float[]{0.1f, 0.2f});
        SupportCaseCsvIngestionService service = new SupportCaseCsvIngestionService(
                new IngestionProperties(false, "data/input.csv", 1, 0, 1),
                embeddingService,
                mock(SupportCaseRepository.class));

        SupportCase result = service.parse(Map.of(
                "tweet_id", "123",
                "created_at", "Sun Oct 22 20:34:00 +0000 2017",
                "customer_message", "exact message, with punctuation",
                "support_response", "response",
                "intent", "hardware_issue"));

        assertEquals("123", result.tweetId());
        assertEquals("exact message, with punctuation", result.customerMessage());
        assertEquals("hardware_issue", result.intent());
        assertEquals(2, result.embedding().length);
    }

        @Test
        void skipsDuplicateTweetIdBeforeEmbedding() throws Exception {
                Path input = Files.createTempFile("support-cases", ".csv");
                Files.writeString(input, "tweet_id,created_at,customer_message,support_response,intent\n"
                                + "123,\"Sun Oct 22 20:34:00 +0000 2017\",message,response,hardware_issue\n");
                EmbeddingService embeddingService = mock(EmbeddingService.class);
                SupportCaseRepository repository = mock(SupportCaseRepository.class);
                when(repository.findByTweetId("123")).thenReturn(Optional.of(mock(SupportCase.class)));
                SupportCaseCsvIngestionService service = new SupportCaseCsvIngestionService(
                                new IngestionProperties(false, input.toString(), 1, 0, 1),
                                embeddingService,
                                repository);

                service.ingest();

                verify(embeddingService, never()).embed("message");
                Files.deleteIfExists(input);
        }
}