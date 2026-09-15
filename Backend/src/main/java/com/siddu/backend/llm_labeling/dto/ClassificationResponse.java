package com.siddu.backend.llm_labeling.dto;

import java.util.List;

public record ClassificationResponse(List<ClassificationResult> results) {
}