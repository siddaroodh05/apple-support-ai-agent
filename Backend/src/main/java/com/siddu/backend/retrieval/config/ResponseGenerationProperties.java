package com.siddu.backend.retrieval.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "response-generation")
public record ResponseGenerationProperties(int retryCount, int retryDelaySeconds) {
}
