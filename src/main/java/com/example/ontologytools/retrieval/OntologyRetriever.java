package com.example.ontologytools.retrieval;

import com.example.ontologytools.config.OntologyProperties;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@Service
public class OntologyRetriever {
    private final LuceneOntologyIndex index;
    private final OntologyProperties properties;
    private final GraphEvidenceRetriever graph;
    private final CandidateReranker reranker;

    public OntologyRetriever(LuceneOntologyIndex index, OntologyProperties properties,
                             GraphEvidenceRetriever graph, CandidateReranker reranker) {
        this.index = index;
        this.properties = properties;
        this.graph = graph;
        this.reranker = reranker;
    }

    public List<OntologyEvidence> search(String question) {
        CompletableFuture<List<SearchHit>> bm25 = CompletableFuture.supplyAsync(
                () -> index.searchBm25(question, Math.max(1, properties.bm25Candidates())));
        CompletableFuture<List<SearchHit>> dense = CompletableFuture.supplyAsync(
                () -> index.searchDense(question, Math.max(1, properties.vectorCandidates())));
        CompletableFuture<List<SearchHit>> exact = CompletableFuture.supplyAsync(
                () -> index.searchExact(question, Math.max(1, properties.bm25Candidates())));

        List<List<SearchHit>> rankings = List.of(bm25.join(), dense.join(), exact.join());
        int candidateLimit = Math.max(properties.bm25Candidates(), properties.vectorCandidates())
                + Math.max(0, properties.bm25Candidates());
        List<SearchHit> fused = ReciprocalRankFusion.fuse(rankings, candidateLimit);
        List<SearchHit> reranked = reranker.rerank(question, fused);
        return graph.expand(reranked);
    }

    public void rebuild() {
        index.rebuild();
    }

    public void rebuildIfNeeded() {
        index.rebuildIfNeeded();
    }
}
