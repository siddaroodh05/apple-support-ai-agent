package com.siddu.backend.retrieval.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "retrieval.embedding")
public record EmbeddingProperties(
        String provider,
        String baseUrl,
        String apiKey,
        String model,
        int dimension,
        int timeoutSeconds) {
}