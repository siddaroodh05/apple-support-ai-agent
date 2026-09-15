package com.siddu.backend.retrieval.service;

import com.siddu.backend.retrieval.model.RetrievedSupportCase;
import com.siddu.backend.retrieval.repository.SupportCaseRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SupportCaseRetrievalService {

    private static final int RESULT_LIMIT = 5;

    private final EmbeddingService embeddingService;
    private final SupportCaseRepository repository;

    public SupportCaseRetrievalService(
            EmbeddingService embeddingService,
            SupportCaseRepository repository) {
        this.embeddingService = embeddingService;
        this.repository = repository;
    }

    public List<RetrievedSupportCase> search(String query) {
        return search(query, RESULT_LIMIT);
    }

    public List<RetrievedSupportCase> searchTop(String query, int limit) {
        return search(query, limit);
    }

    private List<RetrievedSupportCase> search(String query, int limit) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Query must not be blank");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("Search limit must be positive");
        }
        return repository.findMostSimilar(embeddingService.embed(query), limit);
    }
}