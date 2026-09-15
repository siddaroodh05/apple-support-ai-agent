package com.siddu.backend.retrieval.service;

import com.siddu.backend.retrieval.config.EmbeddingProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@ConditionalOnProperty(
        name = "retrieval.embedding.provider",
    havingValue = "ollama",
        matchIfMissing = true)
public class OpenAiCompatibleEmbeddingService implements EmbeddingService {

    private final RestClient restClient;
    private final EmbeddingProperties properties;

    @Autowired
    public OpenAiCompatibleEmbeddingService(
            EmbeddingProperties properties) {
        this(createRestClient(properties), properties);
    }

    OpenAiCompatibleEmbeddingService(RestClient restClient, EmbeddingProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    private static RestClient createRestClient(EmbeddingProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setReadTimeout(Duration.ofSeconds(properties.timeoutSeconds()));
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .defaultHeaders(headers -> headers.setBearerAuth(properties.apiKey()))
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Text to embed must not be blank");
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.post()
                    .uri("/embeddings")
                    .body(new EmbeddingRequest(properties.model(), text))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, responseMessage) -> {
                        throw new EmbeddingServiceException(
                                "Embedding provider returned HTTP " + responseMessage.getStatusCode().value());
                    })
                    .body(Map.class);
            return parseEmbedding(response);
        } catch (EmbeddingServiceException | IllegalArgumentException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw new EmbeddingServiceException("Embedding provider request failed", exception);
        }
    }

    private float[] parseEmbedding(Map<String, Object> response) {
        if (response == null || response.isEmpty()) {
            throw new EmbeddingServiceException("Embedding provider returned no vector");
        }

        Object data = response.get("data");
        if (!(data instanceof List<?> dataList) || dataList.isEmpty()) {
            throw new EmbeddingServiceException("Embedding provider returned no vector");
        }

        Object firstItem = dataList.get(0);
        if (!(firstItem instanceof Map<?, ?> itemMap)) {
            throw new EmbeddingServiceException("Embedding provider returned no vector");
        }

        Object values = itemMap.get("embedding");
        if (!(values instanceof List<?> embeddingValues) || embeddingValues.isEmpty()) {
            throw new EmbeddingServiceException("Embedding provider returned no vector");
        }

        List<Float> parsed = new ArrayList<>(embeddingValues.size());
        for (Object value : embeddingValues) {
            if (value instanceof Number number) {
                parsed.add(number.floatValue());
            } else {
                throw new EmbeddingServiceException("Embedding provider returned an invalid vector");
            }
        }
        float[] embedding = new float[parsed.size()];
        for (int index = 0; index < parsed.size(); index++) {
            embedding[index] = parsed.get(index);
        }
        if (embedding.length != properties.dimension()) {
            throw new EmbeddingServiceException(
                    "Embedding dimension " + embedding.length
                            + " does not match configured dimension " + properties.dimension());
        }
        return embedding;
    }

    private record EmbeddingRequest(String model, String input) {
    }
}