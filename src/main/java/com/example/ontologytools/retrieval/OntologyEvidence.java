package com.example.ontologytools.retrieval;

import java.util.Locale;

public record OntologyEvidence(
        String factId,
        String subject,
        String predicate,
        String object,
        String graph,
        String method,
        double score) {
    @Override
    public String toString() {
        String graphLabel = graph == null || graph.isBlank() ? "default graph" : "graph=" + graph;
        return "[" + factId + "] " + subject + " -- " + predicate + " --> " + object
                + " (" + graphLabel + "; via=" + method + "; score=" + String.format(Locale.ROOT, "%.4f", score) + ")";
    }
}
