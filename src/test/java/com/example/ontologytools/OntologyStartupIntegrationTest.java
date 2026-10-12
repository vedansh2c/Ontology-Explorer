package com.example.ontologytools;

import com.example.ontologytools.ingest.OntologyStore;
import com.example.ontologytools.web.OntologyController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class OntologyStartupIntegrationTest {
    @TempDir
    static Path dataDirectory;

    @Autowired
    private OntologyController controller;

    @DynamicPropertySource
    static void configureOntology(DynamicPropertyRegistry registry) {
        registry.add("ontology.source", () -> "src/main/resources/sample-ontology.ttl");
        registry.add("ontology.data-directory", () -> dataDirectory.resolve("tdb").toString());
        registry.add("ontology.embedding-model", () -> "test-unavailable-model");
        registry.add("ontology.reranker-enabled", () -> false);
    }

    @Test
    void startupLoadsAndIndexesConfiguredOntologyBeforeServingStatus() {
        OntologyStore.Status status = controller.status();

        assertTrue(status.loaded());
        assertEquals(267, status.tripleCount());
    }
}
