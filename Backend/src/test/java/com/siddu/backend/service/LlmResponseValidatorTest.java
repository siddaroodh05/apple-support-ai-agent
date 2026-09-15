package com.siddu.backend.service;

import com.siddu.backend.llm_labeling.dto.ClassificationCase;
import com.siddu.backend.llm_labeling.model.Intent;
import com.siddu.backend.llm_labeling.service.LlmResponseValidator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LlmResponseValidatorTest {

    private final LlmResponseValidator validator = new LlmResponseValidator();
    private final List<ClassificationCase> cases = List.of(
            testCase("case-1"),
            testCase("case-2"),
            testCase("case-3"));

    @Test
    void mapsValidCsvResultsToInputOrder() throws Exception {
        String response = "tweet_id,intent\n"
            + "case-1,software_issue\n"
                + "case-2,how_to_question\n"
                + "case-3,hardware_issue\n";

        assertEquals(List.of(Intent.SOFTWARE_ISSUE, Intent.HOW_TO_QUESTION, Intent.HARDWARE_ISSUE),
                validator.validateAndMap(response, cases));
    }

    @Test
    void rejectsWrongResultOrder() {
        String response = "tweet_id,intent\n"
                + "case-2,how_to_question\n"
            + "case-1,software_issue\n"
                + "case-3,hardware_issue\n";

        assertNotNull(assertThrows(IOException.class, () -> validator.validateAndMap(response, cases)).getMessage());
    }

    @Test
    void rejectsWrongResultCount() {
        String response = "tweet_id,intent\ncase-1,how_to_question\n";

        assertNotNull(assertThrows(IOException.class, () -> validator.validateAndMap(response, cases)).getMessage());
    }

    @Test
    void rejectsUnknownIntent() {
        String response = "tweet_id,intent\n"
                + "case-1,unknown\n"
                + "case-2,how_to_question\n"
                + "case-3,hardware_issue\n";

        assertNotNull(assertThrows(IOException.class, () -> validator.validateAndMap(response, cases)).getMessage());
    }

    private ClassificationCase testCase(String processingId) {
        return new ClassificationCase(processingId, processingId, "date", "customer", "response",
                "tweet_id:" + processingId);
    }
}
