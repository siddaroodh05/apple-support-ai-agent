package com.siddu.backend.llm_labeling.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.siddu.backend.llm_labeling.config.OpenAiProperties;
import com.siddu.backend.llm_labeling.dto.ClassificationCase;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

@Service
public class LlmClassificationClient {

         private static final String SYSTEM_PROMPT = """
                                  You are an expert customer-support intent classifier.

                                  Classify each customer case into exactly ONE intent:

                                  SOFTWARE_ISSUE
                                  Existing software/system problem: crashes, errors, freezing, updates,
                                  restore, backup, syncing, or broken Apple software/features.

                                  HARDWARE_ISSUE
                                  Existing physical-device problem: screen, battery, camera, speaker,
                                  microphone, buttons, charging port, power, or other hardware malfunction.

                                  ACCOUNT_DATA_ISSUE
                                  Primary problem is Apple account access, login, password recovery,
                                  authentication, verification, being locked out, or account access preventing
                                  access to account information.

                                  HOW_TO_QUESTION
                                  Customer asks how to perform an action, use/configure a feature, or how
                                  something works, without an existing malfunction being the primary problem.

                                  PURCHASE_BILLING
                                  The PRIMARY problem is a purchase, payment, charge, refund, subscription,
                                  order, pricing, invoice, receipt, or billing issue.

                                  THIRD_PARTY_APP_ISSUE
                                  Existing problem primarily involving a third-party app or external service.
                                  The app/service itself must be the primary subject of the problem.
           
                                  GENERAL_COMPLAINT_VAGUE
                                  Complaint/frustration with no sufficiently specific actionable problem.

                                  NO_ACTIONABLE_CONTENT
                                  No meaningful support issue: greetings, thanks, praise, isolated insults,
                                  spam, fragments, unintelligible content, etc.


                                  KEY RULES:

                                  1. Classify the customer's PRIMARY problem, not keywords.
                                  2. Use customer_message as the primary evidence.
                                  3. Use support_response only when customer_message is genuinely ambiguous.
                                  4. Do not use business/brand information.
                                  5. Do not infer information from URLs, images, or videos.
                                  6. tweet_id must not influence classification.
                                  7. An existing malfunction takes priority over HOW_TO_QUESTION.
                                  8. "How do I fix X?" is classified by X's underlying problem, not automatically
                                     as HOW_TO_QUESTION.
                                  9. iCloud, Apple ID, password, photos, backup, or data do NOT automatically
                                     mean ACCOUNT_DATA_ISSUE.
                                  10. ACCOUNT_DATA_ISSUE requires account access/recovery/authentication to be
                                      the PRIMARY problem.
                                  11. Missing/lost photos, failed restore, failed backup, syncing failure, or
                                      broken Apple software → SOFTWARE_ISSUE unless account access is clearly
                                      the primary problem.
                                  12. A third-party app must be the primary source/subject of the problem to
                                      use THIRD_PARTY_APP_ISSUE.
                                  13. A specific technical problem beats a vague complaint.
                                  14. Do not invent a problem when evidence is insufficient.
                                  15. Choose exactly ONE intent for every case.

                                  OUTPUT:

                                  Return ONLY valid CSV. No markdown or commentary.

                                  First line:
                                  tweet_id,intent

                                  Then one row per input case, in the SAME ORDER as the input.

                                  Use the exact tweet_id.

                                  Do not add, modify, truncate, or omit tweet_ids.

                                  Allowed intents:
                                  SOFTWARE_ISSUE
                                  HARDWARE_ISSUE
                                  ACCOUNT_DATA_ISSUE
                                  HOW_TO_QUESTION
                                  PURCHASE_BILLING
                                  THIRD_PARTY_APP_ISSUE
                                  GENERAL_COMPLAINT_VAGUE
                                  NO_ACTIONABLE_CONTENT
                 """;

    private final ObjectMapper objectMapper;
    private final OpenAiProperties openAiProperties;
    private final HttpClient httpClient;

    public LlmClassificationClient(ObjectMapper objectMapper, OpenAiProperties openAiProperties) {
        this.objectMapper = objectMapper;
        this.openAiProperties = openAiProperties;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    }

    public String classify(List<ClassificationCase> cases) throws IOException, InterruptedException {
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", openAiProperties.chat().model());
        requestBody.put("temperature", openAiProperties.chat().temperature());

        ArrayNode messages = requestBody.putArray("messages");
        messages.addObject().put("role", "system").put("content", SYSTEM_PROMPT);
        messages.addObject().put("role", "user").put("content", buildUserPrompt(cases));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(openAiProperties.baseUrl().replaceAll("/$", "") + "/chat/completions"))
                .timeout(Duration.ofSeconds(90))
                .header("Authorization", "Bearer " + openAiProperties.apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("LLM API returned HTTP " + response.statusCode());
        }
        var completion = objectMapper.readTree(response.body());
        var content = completion.path("choices").path(0).path("message").path("content");
        if (!content.isTextual()) {
            throw new IOException("LLM API response has no text content");
        }
        return content.asText();
    }

    private String buildUserPrompt(List<ClassificationCase> cases) throws IOException {
        ArrayNode input = objectMapper.createArrayNode();
        for (ClassificationCase classificationCase : cases) {
            ObjectNode item = input.addObject();
            item.put("tweet_id", classificationCase.processingId());
            item.put("customer_message", classificationCase.customerMessage());
            item.put("support_response", classificationCase.supportResponse());
        }
        return "Classify these cases and return one result for every case:\n" + objectMapper.writeValueAsString(input);
    }
}