package com.example.ontologytools.ingest;

import com.example.ontologytools.retrieval.OntologyRetriever;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class OntologyStartupRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(OntologyStartupRunner.class);

    private final OntologyStore store;
    private final OntologyRetriever retriever;

    public OntologyStartupRunner(OntologyStore store, OntologyRetriever retriever) {
        this.store = store;
        this.retriever = retriever;
    }

    @Override
    public void run(ApplicationArguments args) {
        store.loadIfChanged();
        if (!store.status().loaded()) {
            log.warn("Ontology startup load was skipped. Set ONTOLOGY_SOURCE to an existing RDF/OWL file.");
            return;
        }
        retriever.rebuildIfNeeded();
        log.info("Ontology search index is ready.");
    }
}
