package com.siddu.backend.retrieval.service;

import com.siddu.backend.retrieval.model.RetrievedSupportCase;
import com.siddu.backend.retrieval.repository.SupportCaseRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SupportCaseRetrievalServiceTest {

    private final EmbeddingService embeddingService = mock(EmbeddingService.class);
    private final SupportCaseRepository repository = mock(SupportCaseRepository.class);
    private final SupportCaseRetrievalService service =
            new SupportCaseRetrievalService(embeddingService, repository);

    @Test
    void rejectsBlankQueryBeforeEmbedding() {
        Throwable exception = assertThrows(IllegalArgumentException.class, () -> service.search("  "));
        assertEquals("Query must not be blank", exception.getMessage());
    }

    @Test
    void returnsRepositoryResultsInSimilarityOrder() {
        float[] embedding = {0.1f, 0.2f};
        List<RetrievedSupportCase> expected = List.of(
                new RetrievedSupportCase("first", "message 1", "response 1", "intent", 0.91),
                new RetrievedSupportCase("second", "message 2", "response 2", "intent", 0.82));
        when(embeddingService.embed("battery issue")).thenReturn(embedding);
        when(repository.findMostSimilar(embedding, 1)).thenReturn(expected);

        assertEquals(expected, service.search("battery issue"));
        verify(repository).findMostSimilar(any(float[].class), eq(1));
    }
}