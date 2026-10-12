package com.example.ontologytools.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ontology")
public record OntologyProperties(
        String source,
        String dataDirectory,
        int retrievalLimit,
        String ollamaBaseUrl,
        String embeddingModel,
        int bm25Candidates,
        int vectorCandidates,
        int graphDepth,
        int graphNodeLimit,
        int embeddingBatchSize,
        int embeddingDimensions,
        String indexVersion,
        boolean rerankerEnabled,
        String rerankerUrl,
        String rerankerModel,
        String rerankerApiKey) {
}
