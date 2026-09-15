package com.siddu.backend.retrieval.dto;

import com.siddu.backend.retrieval.model.RetrievedSupportCase;

public record RetrievalResult(
        String tweetId,
        String customerMessage,
        String supportResponse,
        String intent,
        double similarity) {

    public static RetrievalResult from(RetrievedSupportCase result) {
        return new RetrievalResult(
                result.tweetId(),
                result.customerMessage(),
                result.supportResponse(),
                result.intent(),
                result.similarity());
    }
}