package com.siddu.backend.retrieval.model;

import java.time.OffsetDateTime;

public record SupportCase(
        String tweetId,
        OffsetDateTime createdAt,
        String customerMessage,
        String supportResponse,
        String intent,
        float[] embedding) {
}