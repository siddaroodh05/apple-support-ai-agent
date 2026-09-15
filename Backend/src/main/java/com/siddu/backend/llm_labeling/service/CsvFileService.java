package com.siddu.backend.llm_labeling.service;

import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import com.fasterxml.jackson.databind.MappingIterator;
import com.siddu.backend.llm_labeling.dto.ClassificationCase;
import com.siddu.backend.llm_labeling.model.Intent;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class CsvFileService {

    private static final String TWEET_ID = "tweet_id";
    private static final String CREATED_AT = "created_at";
    private static final String CUSTOMER_MESSAGE = "customer_message";
    private static final String SUPPORT_RESPONSE = "support_response";
    private static final String INTENT = "intent";
    private static final CsvSchema INPUT_SCHEMA = CsvSchema.emptySchema().withHeader();
    private static final CsvSchema OUTPUT_SCHEMA = CsvSchema.builder()
            .addColumn(TWEET_ID)
            .addColumn(CREATED_AT)
            .addColumn(CUSTOMER_MESSAGE)
            .addColumn(SUPPORT_RESPONSE)
            .addColumn(INTENT)
            .setUseHeader(true)
            .build();
    private static final CsvSchema OUTPUT_SCHEMA_WITHOUT_HEADER = OUTPUT_SCHEMA.withoutHeader();

    private final CsvMapper csvMapper;

    public CsvFileService() {
        this.csvMapper = new CsvMapper();
    }

    public List<ClassificationCase> readCases(Path inputPath) throws IOException {
        List<ClassificationCase> cases = new ArrayList<>();
        Set<String> tweetIds = new HashSet<>();
        try (MappingIterator<Map<String, String>> rows = csvMapper.readerFor(Map.class)
                .with(INPUT_SCHEMA)
                .readValues(inputPath.toFile())) {
            int lineNumber = 1;
            while (rows.hasNext()) {
                lineNumber++;
                Map<String, String> row = rows.next();
                String tweetId = required(row, TWEET_ID, lineNumber);
                if (!tweetIds.add(tweetId)) {
                    throw new IOException("Duplicate tweet_id at CSV line " + lineNumber + ": " + tweetId);
                }
                cases.add(new ClassificationCase(
                        tweetId,
                        tweetId,
                        row.get(CREATED_AT),
                        row.get(CUSTOMER_MESSAGE),
                        row.get(SUPPORT_RESPONSE),
                        "tweet_id:" + tweetId));
            }
        }
        return cases;
    }

    public Set<String> readProcessedKeys(Path outputPath) throws IOException {
        Set<String> processedKeys = new HashSet<>();
        if (!Files.exists(outputPath) || Files.size(outputPath) == 0) {
            return processedKeys;
        }
        try (MappingIterator<Map<String, String>> rows = csvMapper.readerFor(Map.class)
                .with(INPUT_SCHEMA)
                .readValues(outputPath.toFile())) {
            while (rows.hasNext()) {
                Map<String, String> row = rows.next();
                String tweetId = row.get(TWEET_ID);
                if (tweetId != null && !tweetId.isBlank()) {
                    processedKeys.add("tweet_id:" + tweetId);
                }
            }
        }
        return processedKeys;
    }

    public void appendResults(Path outputPath, List<ClassificationCase> cases, List<Intent> intents) throws IOException {
        Path parent = outputPath.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        boolean writeHeader = !Files.exists(outputPath) || Files.size(outputPath) == 0;
        try (BufferedWriter writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            if (writeHeader) {
                writer.write(String.join(",", TWEET_ID, CREATED_AT, CUSTOMER_MESSAGE, SUPPORT_RESPONSE, INTENT));
                writer.newLine();
            }
            for (int index = 0; index < cases.size(); index++) {
                ClassificationCase classificationCase = cases.get(index);
                Map<String, String> output = new LinkedHashMap<>();
                output.put(TWEET_ID, classificationCase.tweetId());
                output.put(CREATED_AT, classificationCase.createdAt());
                output.put(CUSTOMER_MESSAGE, classificationCase.customerMessage());
                output.put(SUPPORT_RESPONSE, classificationCase.supportResponse());
                output.put(INTENT, intents.get(index).name().toLowerCase());
                writer.write(csvMapper.writer(OUTPUT_SCHEMA_WITHOUT_HEADER).writeValueAsString(output).trim());
                writer.newLine();
            }
        }
    }

    private String required(Map<String, String> row, String field, int lineNumber) throws IOException {
        String value = row.get(field);
        if (value == null || value.isBlank()) {
            throw new IOException("Missing required " + field + " at CSV line " + lineNumber);
        }
        return value;
    }
}
