package com.example.ontologytools.retrieval;

public record SearchHit(
        String id,
        String type,
        String entity,
        String graph,
        String text,
        String method,
        double score,
        int rank) {
}
