package com.example.ontologytools.retrieval;

import java.util.List;

public record SearchRecord(
        String id,
        String type,
        String entity,
        String graph,
        String text,
        List<String> exactTerms) {
}
