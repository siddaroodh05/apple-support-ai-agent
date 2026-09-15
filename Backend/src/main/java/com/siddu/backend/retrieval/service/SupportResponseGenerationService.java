package com.siddu.backend.retrieval.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.siddu.backend.llm_labeling.config.OpenAiProperties;
import com.siddu.backend.retrieval.config.ResponseGenerationProperties;
import com.siddu.backend.retrieval.dto.SupportAnswerResponse;
import com.siddu.backend.retrieval.model.RetrievedSupportCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class SupportResponseGenerationService {

    private static final Logger log = LoggerFactory.getLogger(SupportResponseGenerationService.class);
    private static final String SYSTEM_PROMPT = """
            You are the final Apple Support response decision system.

            Use the customer message, predicted intent, and retrieved historical cases.
            Evidence is sufficient only when at least one case addresses the same underlying
            problem or support need and provides useful troubleshooting, resolution guidance,
            or information that can be safely adapted. Keyword or product overlap alone is not
            sufficient. If cases are unrelated, contradictory, vague, or unsafe to adapt,
            evidence is insufficient and you must escalate.

            Do not invent troubleshooting steps, policies, refunds, account actions, guarantees,
            or facts. For sensitive, ambiguous, unusual, or high-risk account/security issues,
            prefer escalation unless evidence is clearly sufficient. Do not copy a historical
            response blindly; adapt relevant guidance to the current customer.

            Return ONLY valid JSON with exactly these fields:
            {"evidence_sufficient":true,"response":"support response","decision":"AUTO_HANDLE","reason":"brief explanation"}

            For insufficient evidence, response must be null and decision must be ESCALATE.
            Decision must be exactly AUTO_HANDLE or ESCALATE.
            """;

    private final ObjectMapper objectMapper;
    private final OpenAiProperties openAiProperties;
    private final ResponseGenerationProperties retryProperties;
    private final HttpClient httpClient;

    public SupportResponseGenerationService(ObjectMapper objectMapper, OpenAiProperties openAiProperties,
                                            ResponseGenerationProperties retryProperties) {
        this.objectMapper = objectMapper;
        this.openAiProperties = openAiProperties;
        this.retryProperties = retryProperties;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    }

    public SupportAnswerResponse generate(String query, String predictedIntent,
                                          List<RetrievedSupportCase> retrievedCases) {
        int totalAttempts = Math.max(1, retryProperties.retryCount() + 1);
        for (int attempt = 1; attempt <= totalAttempts; attempt++) {
            try {
                return generateOnce(query, predictedIntent, retrievedCases);
            } catch (IOException | IllegalStateException exception) {
                if (attempt == totalAttempts) {
                    log.error("Response generation failed after {} attempts", totalAttempts, exception);
                    return escalation("The response-generation model did not return a valid safe decision.");
                }
                log.warn("Response-generation attempt {}/{} failed: {}", attempt, totalAttempts, exception.getMessage());
                waitBeforeRetry();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return escalation("Response generation was interrupted before a safe answer could be produced.");
            }
        }
        return escalation("The response-generation model did not return a valid safe decision.");
    }

    private SupportAnswerResponse generateOnce(String query, String predictedIntent,
                                               List<RetrievedSupportCase> retrievedCases)
            throws IOException, InterruptedException {
        var body = objectMapper.createObjectNode();
        body.put("model", openAiProperties.chat().model());
        body.put("temperature", openAiProperties.chat().temperature());
        body.putObject("response_format").put("type", "json_object");
        var messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", SYSTEM_PROMPT);
        messages.addObject().put("role", "user").put("content", buildPrompt(query, predictedIntent, retrievedCases));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(openAiProperties.baseUrl().replaceAll("/$", "") + "/chat/completions"))
                .timeout(Duration.ofSeconds(90))
                .header("Authorization", "Bearer " + openAiProperties.apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (httpResponse.statusCode() < 200 || httpResponse.statusCode() >= 300) {
            throw new IOException("LLM API returned HTTP " + httpResponse.statusCode());
        }
        JsonNode message = objectMapper.readTree(httpResponse.body()).path("choices").path(0).path("message");
        JsonNode content = message.path("content");
        if (!content.isTextual() || content.asText().isBlank()) {
            throw new IOException("LLM response has no text content");
        }
        return parseAndValidate(content.asText());
    }

    private SupportAnswerResponse parseAndValidate(String content) throws IOException {
        JsonNode json = objectMapper.readTree(extractJsonObject(content));
        Set<String> fields = new HashSet<>();
        json.fieldNames().forEachRemaining(fields::add);
        if (!fields.equals(Set.of("evidence_sufficient", "response", "decision", "reason"))) {
            throw new IllegalStateException("Response contains missing or unknown fields");
        }
        if (!json.path("evidence_sufficient").isBoolean()
                || !json.path("decision").isTextual()
                || !json.path("reason").isTextual()) {
            throw new IllegalStateException("Response fields have invalid types");
        }
        boolean sufficient = json.get("evidence_sufficient").asBoolean();
        String decision = json.get("decision").asText();
        JsonNode responseNode = json.get("response");
        if (!decision.equals("AUTO_HANDLE") && !decision.equals("ESCALATE")) {
            throw new IllegalStateException("Decision must be AUTO_HANDLE or ESCALATE");
        }
        if (!sufficient && (!decision.equals("ESCALATE") || !responseNode.isNull())) {
            throw new IllegalStateException("Insufficient evidence must escalate with a null response");
        }
        if (sufficient && (!decision.equals("AUTO_HANDLE") || !responseNode.isTextual()
                || responseNode.asText().isBlank())) {
            throw new IllegalStateException("Sufficient evidence must auto-handle with a response");
        }
        return new SupportAnswerResponse(sufficient,
                responseNode.isNull() ? null : responseNode.asText(), decision, json.get("reason").asText());
    }

    private String buildPrompt(String query, String predictedIntent, List<RetrievedSupportCase> cases)
            throws IOException {
        var caseArray = objectMapper.createArrayNode();
        for (RetrievedSupportCase supportCase : cases) {
            var item = caseArray.addObject();
            item.put("tweet_id", supportCase.tweetId());
            item.put("customer_message", supportCase.customerMessage());
            item.put("support_response", supportCase.supportResponse());
            item.put("similarity", supportCase.similarity());
        }
        return "CUSTOMER MESSAGE:\n" + query + "\n\nPREDICTED INTENT:\n" + predictedIntent
                + "\n\nRETRIEVED HISTORICAL CASES:\n" + objectMapper.writeValueAsString(caseArray);
    }

    private String extractJsonObject(String content) throws IOException {
        String trimmed = content.trim();
        if (trimmed.startsWith("```") && trimmed.endsWith("```")) {
            int firstLineEnd = trimmed.indexOf('\n');
            trimmed = trimmed.substring(firstLineEnd + 1, trimmed.length() - 3).trim();
        }
        JsonNode direct = objectMapper.readTree(trimmed);
        if (direct.isObject()) {
            return direct.toString();
        }
        throw new IOException("Response was not a JSON object");
    }

    private void waitBeforeRetry() {
        if (retryProperties.retryDelaySeconds() <= 0) {
            return;
        }
        try {
            Thread.sleep(retryProperties.retryDelaySeconds() * 1000L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Response-generation retry delay interrupted", exception);
        }
    }

    private SupportAnswerResponse escalation(String reason) {
        return new SupportAnswerResponse(false, null, "ESCALATE", reason);
    }
}
