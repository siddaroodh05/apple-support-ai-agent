package com.siddu.backend.retrieval.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "retrieval.ingestion")
public record IngestionProperties(
        boolean enabled,
        String inputFile,
        int batchSize,
        int delayMilliseconds,
        int maxRetries) {
}