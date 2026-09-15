package com.siddu.backend.retrieval.model;

public record RetrievedSupportCase(
        String tweetId,
        String customerMessage,
        String supportResponse,
        String intent,
        double similarity) {
}