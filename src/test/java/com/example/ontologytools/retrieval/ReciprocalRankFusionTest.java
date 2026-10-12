package com.example.ontologytools.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReciprocalRankFusionTest {
    @Test
    void combinesRankingsAndPromotesResultsFoundByMultipleRetrievers() {
        SearchHit shared = hit("shared", "bm25", 1);
        List<SearchHit> fused = ReciprocalRankFusion.fuse(List.of(
                List.of(shared, hit("lexical-only", "bm25", 2)),
                List.of(hit("vector-only", "vector", 1), hit("shared", "vector", 2))), 10);

        assertEquals("shared", fused.getFirst().id());
        assertEquals(3, fused.size());
        assertTrue(fused.getFirst().method().contains("bm25"));
        assertTrue(fused.getFirst().method().contains("vector"));
    }

    private SearchHit hit(String id, String method, int rank) {
        return new SearchHit(id, "entity", "urn:" + id, "", id, method, 1, rank);
    }
}
