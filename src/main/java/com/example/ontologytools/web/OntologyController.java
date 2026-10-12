package com.example.ontologytools.web;

import com.example.ontologytools.ingest.OntologyStore;
import com.example.ontologytools.agent.CopilotAgent;
import com.example.ontologytools.retrieval.OntologyEvidence;
import com.example.ontologytools.retrieval.OntologyRetriever;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class OntologyController {
    private final OntologyStore store;
    private final OntologyRetriever retriever;
    private final CopilotAgent agent;

    public OntologyController(OntologyStore store, OntologyRetriever retriever, CopilotAgent agent) {
        this.store = store;
        this.retriever = retriever;
        this.agent = agent;
    }

    @GetMapping("/status")
    public OntologyStore.Status status() {
        return store.status();
    }

    @PostMapping("/ingest")
    public OntologyStore.Status ingest() {
        store.load();
        retriever.rebuild();
        return store.status();
    }

    @PostMapping(value = "/question", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<Map<String, String>> question(@RequestBody Question request) {
        return Flux.concat(
            Flux.just(event("status", "Searching lexical, semantic, and exact ontology indexes...")),
            Mono.fromCallable(() -> retriever.search(request.question()))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(evidence -> {
                    String evidenceText = formatEvidence(evidence);
                    String evidenceSummary = evidence.isEmpty()
                        ? "No matching ontology statements found."
                        : "Retrieved " + evidence.size() + " grounded graph facts.";
                    return Flux.concat(
                        Flux.just(event("status", evidenceSummary)),
                        Flux.just(event("evidence", evidenceText)),
                        Flux.just(event("status", "Preparing grounded context for Copilot...")),
                        Flux.just(event("status", "Waiting for the ontology agent response...")),
                        Mono.fromFuture(agent.answer(request.question(), evidenceText))
                            .map(answer -> event("answer", answer)),
                        Flux.just(event("rationale", "Answer constrained to retrieved ontology evidence.")));
                })
        ).delayElements(Duration.ofMillis(180));
    }

    private Map<String, String> event(String type, String message) {
        return Map.of("type", type, "message", message);
    }

    private String formatEvidence(List<OntologyEvidence> evidence) {
        if (evidence.isEmpty()) return "No matching ontology statements were found.";
        String entities = evidence.stream().map(OntologyEvidence::subject).distinct().limit(20)
                .map(subject -> "- " + subject).collect(java.util.stream.Collectors.joining("\n"));
        String facts = evidence.stream().map(OntologyEvidence::toString)
                .collect(java.util.stream.Collectors.joining("\n"));
        return "MATCHED ENTITIES\n" + entities + "\n\nEXACT RDF EVIDENCE\n" + facts;
    }

    public record Question(String question) {
    }
}
