package com.siddu.backend.llm_labeling.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.siddu.backend.llm_labeling.config.OpenAiProperties;
import com.siddu.backend.llm_labeling.model.Intent;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;

@Service
public class LiveIntentClassificationService {

    private static final String SYSTEM_PROMPT = """
            You are an expert customer-support intent classifier.
            
            Classify the customer message into exactly ONE intent:
            
            SOFTWARE_ISSUE — Existing Apple software/system problem: crashes, errors, freezing, updates, restore, backup, syncing, or broken Apple features.
            
            HARDWARE_ISSUE — Existing physical-device malfunction: screen, battery, camera, speaker, microphone, buttons, charging, power, or other hardware.
            
            ACCOUNT_DATA_ISSUE — Account access, login, password recovery, authentication, verification, lockout, or account access preventing access to account information.
            
            HOW_TO_QUESTION — Asking how to perform an action, configure/use a feature, or how something works, with no existing malfunction as the primary problem.
            
            PURCHASE_BILLING — Purchase, payment, charge, refund, subscription, order, pricing, invoice, receipt, or billing issue.
            
            THIRD_PARTY_APP_ISSUE — Existing problem primarily caused by or involving a third-party app or external service.
            
            GENERAL_COMPLAINT_VAGUE — No sufficiently specific support problem; mainly vague complaint or frustration.
            
            NO_ACTIONABLE_CONTENT — No meaningful support request: greetings, thanks, praise, isolated insults, spam, fragments, or unintelligible content.
           
            RULES:
            - Classify the PRIMARY problem, not keywords.
            - Use customer_message as the main evidence.
            - Use support_response only if customer_message is genuinely ambiguous.
            - An existing malfunction beats HOW_TO_QUESTION, including "How do I fix X?"
            - ACCOUNT_DATA_ISSUE requires account access/recovery/authentication to be the primary problem.
            - iCloud, Apple ID, photos, backup, syncing, or data alone do not imply ACCOUNT_DATA_ISSUE.
            - Missing photos, failed restore/backup/syncing, and broken Apple software are SOFTWARE_ISSUE unless account access is primary.
            - A third-party app/service must be the primary subject of the problem.
            - Specific technical problems beat vague complaints.
            - Never invent missing context.
            - Choose exactly ONE intent.
            Return ONLY valid JSON with exactly this structure and no other fields:
            {"intent":"software_issue"}
            """;

    private final ObjectMapper objectMapper;
    private final OpenAiProperties properties;
    private final HttpClient httpClient;

    public LiveIntentClassificationService(ObjectMapper objectMapper, OpenAiProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    }

    public Intent predict(String query) {
        try {
            String content = callModel(query);
            JsonNode json = objectMapper.readTree(extractJsonObject(content));
            Set<String> fields = new java.util.HashSet<>();
            json.fieldNames().forEachRemaining(fields::add);
            if (!fields.equals(Set.of("intent")) || !json.path("intent").isTextual()) {
                throw new IllegalStateException("Live intent response must contain only an intent field");
            }
            return Intent.valueOf(json.get("intent").asText().trim().toUpperCase());
        } catch (IOException | InterruptedException | IllegalArgumentException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Live intent classification failed: " + exception.getMessage(), exception);
        }
    }

    private String callModel(String query) throws IOException, InterruptedException {
        var body = objectMapper.createObjectNode();
        body.put("model", properties.chat().model());
        body.put("temperature", properties.chat().temperature());
        body.putObject("response_format").put("type", "json_object");
        var messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", SYSTEM_PROMPT);
        messages.addObject().put("role", "user").put("content", "CUSTOMER MESSAGE:\n" + query);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(properties.baseUrl().replaceAll("/$", "") + "/chat/completions"))
                .timeout(Duration.ofSeconds(90))
                .header("Authorization", "Bearer " + properties.apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("LLM API returned HTTP " + response.statusCode());
        }
        JsonNode content = objectMapper.readTree(response.body()).path("choices").path(0).path("message").path("content");
        if (!content.isTextual() || content.asText().isBlank()) {
            throw new IOException("LLM response has no text content");
        }
        return content.asText();
    }

    private String extractJsonObject(String content) throws IOException {
        String trimmed = content.trim();
        if (trimmed.startsWith("```") && trimmed.endsWith("```")) {
            int firstLineEnd = trimmed.indexOf('\n');
            trimmed = trimmed.substring(firstLineEnd + 1, trimmed.length() - 3).trim();
        }
        JsonNode json = objectMapper.readTree(trimmed);
        if (!json.isObject()) {
            throw new IOException("Live intent response was not a JSON object");
        }
        return json.toString();
    }
}
