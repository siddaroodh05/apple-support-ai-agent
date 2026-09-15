package com.siddu.backend.retrieval.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SupportAnswerResponse(
        @JsonProperty("evidence_sufficient") boolean evidenceSufficient,
        String response,
        String decision,
        String reason) {
}
