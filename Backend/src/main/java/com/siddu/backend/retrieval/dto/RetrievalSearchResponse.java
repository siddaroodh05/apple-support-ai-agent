package com.siddu.backend.retrieval.dto;

import java.util.List;

public record RetrievalSearchResponse(String query, List<RetrievalResult> results) {
}