package com.siddu.backend.llm_labeling.service;

import com.siddu.backend.llm_labeling.config.ClassificationProperties;
import com.siddu.backend.llm_labeling.dto.ClassificationCase;
import com.siddu.backend.llm_labeling.model.Intent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class IntentClassificationService {

    private static final Logger log = LoggerFactory.getLogger(IntentClassificationService.class);
    private static final int RETRY_DELAY_SECONDS = 20;
    private final ClassificationProperties properties;
    private final CsvFileService csvFileService;
    private final LlmClassificationClient llmClient;
    private final LlmResponseValidator validator;
    private final AtomicBoolean running = new AtomicBoolean();

    public IntentClassificationService(
            ClassificationProperties properties,
            CsvFileService csvFileService,
            LlmClassificationClient llmClient,
            LlmResponseValidator validator) {
        this.properties = properties;
        this.csvFileService = csvFileService;
        this.llmClient = llmClient;
        this.validator = validator;
    }

    public boolean start() {
        return running.compareAndSet(false, true);
    }

    @Async
    public void processInBackground() {
        try {
            process();
        } finally {
            running.set(false);
        }
    }

    private void process() {
        Path inputPath = resolveInputPath();
        Path outputPath = resolveOutputPath(inputPath);
        try {
            List<ClassificationCase> allCases = csvFileService.readCases(inputPath);
            Set<String> processedKeys = csvFileService.readProcessedKeys(outputPath);
            List<ClassificationCase> pendingCases = allCases.stream()
                    .filter(classificationCase -> !processedKeys.contains(classificationCase.outputKey()))
                    .toList();
            int totalBatches = (pendingCases.size() + properties.batchSize() - 1) / properties.batchSize();
            log.info("Starting AppleSupport classification. Input: {}, Output: {}, Total cases: {}, Pending cases: {}, Batch size: {}, Total batches: {}",
                    inputPath, outputPath, allCases.size(), pendingCases.size(), properties.batchSize(), totalBatches);

            for (int offset = 0; offset < pendingCases.size(); offset += properties.batchSize()) {
                int batchNumber = offset / properties.batchSize() + 1;
                List<ClassificationCase> batch = new ArrayList<>(pendingCases.subList(offset,
                        Math.min(offset + properties.batchSize(), pendingCases.size())));
                processBatch(batch, batchNumber, totalBatches, outputPath);
                if (offset + properties.batchSize() < pendingCases.size()) {
                    waitBetweenBatches();
                }
            }
            log.info("AppleSupport classification complete");
        } catch (IOException | RuntimeException exception) {
            log.error("Classification job could not start or read its files", exception);
        }
    }

    private void processBatch(List<ClassificationCase> batch, int batchNumber, int totalBatches, Path outputPath) {
        log.info("Processing batch {}/{} with {} cases", batchNumber, totalBatches, batch.size());
        for (int attempt = 1; attempt <= properties.retryCount() + 1; attempt++) {
            try {
                String response = llmClient.classify(batch);
                List<Intent> intents = validator.validateAndMap(response, batch);
                csvFileService.appendResults(outputPath, batch, intents);
                log.info("Batch {} validated and saved {} results", batchNumber, batch.size());
                return;
            } catch (IOException | InterruptedException | RuntimeException exception) {
                log.warn("Batch {} attempt {} failed: {}", batchNumber, attempt, exception.getMessage());
                if (exception instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                if (attempt <= properties.retryCount()) {
                    waitBeforeRetry();
                    log.info("Retrying batch {}", batchNumber);
                }
            }
        }
        log.error("Batch {} failed after {} attempts. Tweet IDs: {}. Skipping batch.",
                batchNumber, properties.retryCount() + 1,
                batch.stream().map(ClassificationCase::processingId).toList());
    }

    private void waitBeforeRetry() {
        try {
            log.info("Waiting {} seconds before retrying request", RETRY_DELAY_SECONDS);
            Thread.sleep(RETRY_DELAY_SECONDS * 1000L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Retry delay interrupted", exception);
        }
    }

    private void waitBetweenBatches() {
        try {
            log.info("Waiting {} seconds before next request", properties.delaySeconds());
            Thread.sleep(properties.delaySeconds() * 1000L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Classification delay interrupted", exception);
        }
    }

    private Path resolveInputPath() {
        Path configuredPath = Paths.get(properties.inputFile());
        if (configuredPath.isAbsolute() || java.nio.file.Files.exists(configuredPath)) {
            return configuredPath;
        }
        Path parentRelativePath = Paths.get("..").resolve(configuredPath).normalize();
        if (java.nio.file.Files.exists(parentRelativePath)) {
            return parentRelativePath;
        }
        return configuredPath;
    }

    private Path resolveOutputPath(Path inputPath) {
        Path configuredPath = Paths.get(properties.outputFile());
        if (configuredPath.isAbsolute() || !inputPath.startsWith(Paths.get(".."))) {
            return configuredPath;
        }
        return Paths.get("..").resolve(configuredPath).normalize();
    }
}