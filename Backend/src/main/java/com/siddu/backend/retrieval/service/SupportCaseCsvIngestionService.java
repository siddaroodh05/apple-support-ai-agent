package com.siddu.backend.retrieval.service;

import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import com.siddu.backend.retrieval.config.IngestionProperties;
import com.siddu.backend.retrieval.model.SupportCase;
import com.siddu.backend.retrieval.repository.SupportCaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;

@Service
public class SupportCaseCsvIngestionService {

    private static final Logger log = LoggerFactory.getLogger(SupportCaseCsvIngestionService.class);
    private static final CsvSchema INPUT_SCHEMA = CsvSchema.emptySchema().withHeader();
    private static final DateTimeFormatter CSV_DATE_FORMAT =
            DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss xx yyyy", java.util.Locale.ENGLISH);

    private final IngestionProperties properties;
    private final EmbeddingService embeddingService;
    private final SupportCaseRepository repository;
    private final CsvMapper csvMapper = new CsvMapper();

    public SupportCaseCsvIngestionService(
            IngestionProperties properties,
            EmbeddingService embeddingService,
            SupportCaseRepository repository) {
        this.properties = properties;
        this.embeddingService = embeddingService;
        this.repository = repository;
    }

    public void ingest() {
        Path inputPath = resolveInputPath();
        int processed = 0;
        int skipped = 0;
        try (MappingIterator<Map<String, String>> rows = csvMapper.readerFor(Map.class)
                .with(INPUT_SCHEMA)
                .readValues(inputPath.toFile())) {
            while (rows.hasNext()) {
                int lineNumber = processed + skipped + 2;
                Map<String, String> row = rows.next();
                try {
                    String tweetId = required(row, "tweet_id");

                    if (repository.findByTweetId(tweetId).isPresent()) {
                        skipped++;
                        continue;
                    }
                    SupportCase supportCase = parse(row);
                    insertWithRetry(supportCase);
                    processed++;
                    if (processed % Math.max(1, properties.batchSize()) == 0) {
                        log.info("Indexed {} support cases", processed);
                    }
                    pause();
                } catch (IllegalArgumentException | DateTimeParseException exception) {
                    skipped++;
                    log.warn("Skipping invalid CSV row {}: {}", lineNumber, exception.getMessage());
                }
            }
            log.info("Support-case ingestion complete. Indexed: {}, skipped: {}", processed, skipped);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read ingestion file " + inputPath, exception);
        }
    }

    SupportCase parse(Map<String, String> row) {
        String tweetId = required(row, "tweet_id");
        String createdAt = required(row, "created_at");
        String customerMessage = required(row, "customer_message");
        String supportResponse = required(row, "support_response");
        String intent = row.getOrDefault("intent", row.get("gold_intent"));
        if (intent == null || intent.isBlank()) {
            throw new IllegalArgumentException("Missing intent");
        }
        return new SupportCase(
                tweetId,
                OffsetDateTime.parse(createdAt, CSV_DATE_FORMAT),
                customerMessage,
                supportResponse,
                intent,
                embeddingService.embed(customerMessage));
    }

    private void insertWithRetry(SupportCase supportCase) {
        for (int attempt = 1; attempt <= properties.maxRetries() + 1; attempt++) {
            try {
                repository.insertIfAbsent(supportCase);
                return;
            } catch (RuntimeException exception) {
                if (attempt > properties.maxRetries()) {
                    throw exception;
                }
                log.warn("Transient failure indexing tweet {} (attempt {}/{}): {}",
                        supportCase.tweetId(), attempt, properties.maxRetries() + 1, exception.getMessage());
                pause(Math.min(5000L, 250L * attempt));
            }
        }
    }

    private String required(Map<String, String> row, String field) {
        String value = row.get(field);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing " + field);
        }
        return value;
    }

    private Path resolveInputPath() {
        Path configuredPath = Paths.get(properties.inputFile());
        if (configuredPath.isAbsolute() || Files.exists(configuredPath)) {
            return configuredPath;
        }
        return Paths.get("..").resolve(configuredPath).normalize();
    }

    private void pause() {
        pause(properties.delayMilliseconds());
    }

    private void pause(long milliseconds) {
        if (milliseconds <= 0) {
            return;
        }
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Ingestion interrupted", exception);
        }
    }
}