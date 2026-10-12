package com.example.ontologytools.retrieval;

import java.util.List;

public interface EmbeddingProvider {
    List<float[]> embed(List<String> texts);
}
