package com.example.ontologytools.retrieval;

import com.example.ontologytools.config.OntologyProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class CandidateReranker {
    private static final Logger log = LoggerFactory.getLogger(CandidateReranker.class);

    private final OntologyProperties properties;
    private final WebClient client;

    public CandidateReranker(OntologyProperties properties) {
        this.properties = properties;
        this.client = WebClient.builder()
                .clientConnector(new JdkClientHttpConnector(HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5)).build()))
                .build();
    }

    public List<SearchHit> rerank(String question, List<SearchHit> candidates) {
        if (!properties.rerankerEnabled() || properties.rerankerUrl().isBlank() || candidates.isEmpty()) {
            return candidates;
        }
        try {
            WebClient.RequestBodySpec request = client.post()
                    .uri(properties.rerankerUrl())
                    .contentType(MediaType.APPLICATION_JSON);
            if (!properties.rerankerApiKey().isBlank()) {
                request.header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.rerankerApiKey());
            }
            RerankResponse response = request.bodyValue(Map.of(
                            "model", properties.rerankerModel(),
                            "query", question,
                            "documents", candidates.stream().map(SearchHit::text).toList(),
                            "top_n", candidates.size()))
                    .retrieve()
                    .bodyToMono(RerankResponse.class)
                    .timeout(Duration.ofSeconds(30))
                    .block();
            if (response == null || response.results() == null) return candidates;

            List<SearchHit> reranked = new ArrayList<>();
            for (RankedItem item : response.results()) {
                if (item.index() >= 0 && item.index() < candidates.size()) {
                    SearchHit hit = candidates.get(item.index());
                    reranked.add(new SearchHit(hit.id(), hit.type(), hit.entity(), hit.graph(), hit.text(),
                            "reranker", item.relevanceScore(), reranked.size() + 1));
                }
            }
            return reranked.isEmpty() ? candidates : reranked;
        } catch (Exception exception) {
            log.warn("Reranker request failed; continuing with fused retrieval ranking: {}", exception.getMessage());
            return candidates;
        }
    }

    private record RerankResponse(List<RankedItem> results) { }
    private record RankedItem(int index, @JsonProperty("relevance_score") double relevanceScore) { }
}
