package com.siddu.backend.retrieval.controller;

import com.siddu.backend.retrieval.dto.RetrievalResult;
import com.siddu.backend.retrieval.dto.RetrievalSearchRequest;
import com.siddu.backend.retrieval.dto.RetrievalSearchResponse;
import com.siddu.backend.retrieval.dto.SupportAnswerRequest;
import com.siddu.backend.retrieval.dto.SupportAnswerResponse;
import com.siddu.backend.llm_labeling.service.LiveIntentClassificationService;
import com.siddu.backend.llm_labeling.model.Intent;
import com.siddu.backend.retrieval.service.SupportCaseIngestionService;
import com.siddu.backend.retrieval.service.SupportCaseRetrievalService;
import com.siddu.backend.retrieval.service.SupportResponseGenerationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/retrieval")
public class RetrievalController {

    private final SupportCaseRetrievalService retrievalService;
    private final SupportCaseIngestionService ingestionService;
    private final SupportResponseGenerationService responseGenerationService;
    private final LiveIntentClassificationService intentService;

    public RetrievalController(
            SupportCaseRetrievalService retrievalService,
            SupportCaseIngestionService ingestionService,
            SupportResponseGenerationService responseGenerationService,
            LiveIntentClassificationService intentService) {
        this.retrievalService = retrievalService;
        this.ingestionService = ingestionService;
        this.responseGenerationService = responseGenerationService;
        this.intentService = intentService;
    }

    @PostMapping("/search")
    public ResponseEntity<RetrievalSearchResponse> search(
            @Valid @RequestBody RetrievalSearchRequest request) {
        return ResponseEntity.ok(new RetrievalSearchResponse(
                request.query(),
                retrievalService.search(request.query()).stream()
                        .map(RetrievalResult::from)
                        .toList()));
    }

    @PostMapping("/answer")
    public ResponseEntity<SupportAnswerResponse> answer(
            @Valid @RequestBody SupportAnswerRequest request) {
        CompletableFuture<Intent> intentFuture = CompletableFuture.supplyAsync(
            () -> intentService.predict(request.query()));
        CompletableFuture<java.util.List<com.siddu.backend.retrieval.model.RetrievedSupportCase>> retrievalFuture =
            CompletableFuture.supplyAsync(() -> retrievalService.searchTop(request.query(), 5));

        Intent predictedIntent = intentFuture.join();
        return ResponseEntity.ok(responseGenerationService.generate(
            request.query(), predictedIntent.name().toLowerCase(), retrievalFuture.join()));
    }

    @PostMapping("/ingestion/start")
    public ResponseEntity<String> startIngestion() {
        if (!ingestionService.start()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body("ingestion_already_running");
        }
        return ResponseEntity.accepted().body("ingestion_started");
    }

}