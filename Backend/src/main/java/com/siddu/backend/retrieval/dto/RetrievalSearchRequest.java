package com.siddu.backend.retrieval.dto;

import jakarta.validation.constraints.NotBlank;

public record RetrievalSearchRequest(@NotBlank String query) {
}