package com.siddu.backend.retrieval.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "retrieval.evaluation")
public record EvaluationProperties(String goldFile) {
}