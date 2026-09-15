package com.siddu.backend.llm_labeling.dto;

public record ClassificationCase(
        String processingId,
        String tweetId,
        String createdAt,
        String customerMessage,
        String supportResponse,
        String outputKey) {
}