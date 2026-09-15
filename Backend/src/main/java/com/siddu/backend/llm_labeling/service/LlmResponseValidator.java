package com.siddu.backend.llm_labeling.service;

import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import com.fasterxml.jackson.databind.MappingIterator;
import com.siddu.backend.llm_labeling.dto.ClassificationCase;
import com.siddu.backend.llm_labeling.model.Intent;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class LlmResponseValidator {

    private static final CsvSchema RESPONSE_SCHEMA = CsvSchema.emptySchema().withHeader();
    private final CsvMapper csvMapper = new CsvMapper();

    public List<Intent> validateAndMap(String responseBody, List<ClassificationCase> cases) throws IOException {
        List<Intent> intents = new ArrayList<>();
        try (MappingIterator<Map<String, String>> rows = csvMapper.readerFor(Map.class)
                .with(RESPONSE_SCHEMA)
                .readValues(responseBody)) {
            int rowIndex = 0;
            while (rows.hasNext()) {
                Map<String, String> row = rows.next();
                if (!row.containsKey("tweet_id") || !row.containsKey("intent")) {
                    throw new IOException("LLM response must have tweet_id,intent columns");
                }
                if (rowIndex >= cases.size()) {
                    throw new IOException("Received more results than input cases");
                }
                String expectedId = cases.get(rowIndex).processingId();
                if (!expectedId.equals(row.get("tweet_id"))) {
                    throw new IOException("Expected tweet_id " + expectedId + " at row " + (rowIndex + 2)
                            + " but received " + row.get("tweet_id"));
                }
                intents.add(parseIntent(row.get("intent")));
                rowIndex++;
            }
            if (rowIndex != cases.size()) {
                throw new IOException("Expected " + cases.size() + " results but received " + rowIndex);
            }
        }
        return intents;
    }

    private Intent parseIntent(String value) throws IOException {
        try {
            return Intent.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid intent: " + value, exception);
        }
    }
}
