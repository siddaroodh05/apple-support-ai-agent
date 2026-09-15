package com.siddu.backend.retrieval.dto;

import com.siddu.backend.retrieval.model.RetrievedSupportCase;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RetrievalResultTest {

    @Test
    void mapsRetrievedCaseToApiResult() {
        RetrievalResult result = RetrievalResult.from(new RetrievedSupportCase(
                "123", "customer", "support", "hardware_issue", 0.95));

        assertEquals("123", result.tweetId());
        assertEquals("customer", result.customerMessage());
        assertEquals(0.95, result.similarity());
    }
}