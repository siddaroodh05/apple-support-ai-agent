package com.siddu.backend.llm_labeling.dto;

import com.siddu.backend.llm_labeling.model.Intent;

public record ClassificationResult(String tweetId, Intent intent) {
}