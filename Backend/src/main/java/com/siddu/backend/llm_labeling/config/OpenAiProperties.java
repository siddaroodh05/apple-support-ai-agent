package com.siddu.backend.llm_labeling.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "openai")
public record OpenAiProperties(String apiKey, String baseUrl, Chat chat) {

    public record Chat(String model, double temperature) {
    }
}