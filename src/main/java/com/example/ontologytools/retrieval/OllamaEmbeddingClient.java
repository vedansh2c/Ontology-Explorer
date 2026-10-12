package com.example.ontologytools.retrieval;

import com.example.ontologytools.config.OntologyProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.net.http.HttpClient;
import java.util.List;

@Component
public class OllamaEmbeddingClient implements EmbeddingProvider {
    private final OntologyProperties properties;
    private final WebClient client;

    public OllamaEmbeddingClient(OntologyProperties properties) {
        this.properties = properties;
        this.client = WebClient.builder()
                .baseUrl(properties.ollamaBaseUrl())
                .clientConnector(new JdkClientHttpConnector(HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5)).build()))
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        if (texts.isEmpty()) return List.of();
        EmbedResponse response = client.post()
                .uri("/api/embed")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new EmbedRequest(properties.embeddingModel(), texts, properties.embeddingDimensions()))
                .retrieve()
                .bodyToMono(EmbedResponse.class)
                .timeout(Duration.ofSeconds(120))
                .onErrorMap(error -> new IllegalStateException(
                        "Ollama embedding request failed for model " + properties.embeddingModel(), error))
                .block();
        if (response == null || response.embeddings() == null || response.embeddings().size() != texts.size()) {
            throw new IllegalStateException("Ollama returned an incomplete embedding batch.");
        }
        for (float[] vector : response.embeddings()) {
            if (vector == null || vector.length == 0) {
                throw new IllegalStateException("Ollama returned an empty embedding vector.");
            }
        }
        return response.embeddings();
    }

    private record EmbedRequest(String model, List<String> input, int dimensions) { }
    private record EmbedResponse(String model, List<float[]> embeddings) { }
}
