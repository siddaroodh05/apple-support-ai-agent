package com.siddu.backend.llm_labeling.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "classification")
public record ClassificationProperties(
        String inputFile,
        String outputFile,
        int batchSize,
        int retryCount,
        int delaySeconds) {
}