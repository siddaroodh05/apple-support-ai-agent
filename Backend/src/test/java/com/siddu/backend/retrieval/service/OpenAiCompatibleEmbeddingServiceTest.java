package com.siddu.backend.retrieval.service;

import com.siddu.backend.retrieval.config.EmbeddingProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OpenAiCompatibleEmbeddingServiceTest {

    @Test
    void wrapsProviderTransportErrors() {
        RestClient restClient = mock(RestClient.class);
        when(restClient.post()).thenThrow(new RestClientException("provider unavailable"));
        OpenAiCompatibleEmbeddingService service = new OpenAiCompatibleEmbeddingService(
                restClient,
                new EmbeddingProperties("openai-compatible", "http://localhost", "secret", "model", 2, 10));

        assertThrows(EmbeddingServiceException.class, () -> service.embed("customer message"));
    }

    @Test
    void rejectsBlankInputBeforeCallingProvider() {
        OpenAiCompatibleEmbeddingService service = new OpenAiCompatibleEmbeddingService(
                mock(RestClient.class),
                new EmbeddingProperties("openai-compatible", "http://localhost", "secret", "model", 2, 10));

        assertThrows(IllegalArgumentException.class, () -> service.embed(" "));
    }

    @Test
    void parsesOpenAiCompatibleEmbeddingResponse() {
        RestClient.Builder builder = RestClient.builder()
            .baseUrl("http://localhost");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        server.expect(requestTo("http://localhost/embeddings"))
            .andExpect(jsonPath("$.model").value("model"))
            .andExpect(jsonPath("$.input").value("customer message"))
            .andRespond(withSuccess("{\"data\":[{\"embedding\":[1.0,2.0]}]}", MediaType.APPLICATION_JSON));

        OpenAiCompatibleEmbeddingService service = new OpenAiCompatibleEmbeddingService(
                restClient,
                new EmbeddingProperties("openai-compatible", "http://localhost", "secret", "model", 2, 10));

        assertArrayEquals(new float[] {1.0f, 2.0f}, service.embed("customer message"), 0.0001f);
        server.verify();
    }
}