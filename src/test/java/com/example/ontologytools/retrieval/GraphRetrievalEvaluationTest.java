package com.example.ontologytools.retrieval;

import com.example.ontologytools.config.OntologyProperties;
import com.example.ontologytools.ingest.OntologyStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphRetrievalEvaluationTest {
    private static final String SAMPLE = "src/main/resources/sample-ontology.ttl";

    @TempDir
    Path tempDirectory;

    @Test
    void evaluatesLexicalDenseAndHybridGraphRetrievalAgainstFixture() throws Exception {
        OntologyProperties properties = properties(SAMPLE, tempDirectory.resolve("tdb").toString());
        OntologyStore store = new OntologyStore(properties);
        store.load();
        assertTrue(store.status().loaded());

        LuceneOntologyIndex index = new LuceneOntologyIndex(properties, store, new DeterministicEmbeddings());
        long indexStarted = System.nanoTime();
        index.rebuild();
        long indexingNanos = System.nanoTime() - indexStarted;
        GraphEvidenceRetriever graph = new GraphEvidenceRetriever(properties);
        List<EvaluationCase> cases = fixture();
        int bm25Recall = 0;
        int vectorRecall = 0;
        int hybridRecall = 0;
        int groundedFacts = 0;
        int totalFacts = 0;
        long retrievalNanos = 0;
        int expectedTotal = cases.stream().mapToInt(item -> item.expected().size()).sum();

        for (EvaluationCase item : cases) {
            long queryStarted = System.nanoTime();
            List<SearchHit> bm25 = index.searchBm25(item.question(), properties.bm25Candidates());
            List<SearchHit> dense = index.searchDense(item.question(), properties.vectorCandidates());
            List<SearchHit> exact = index.searchExact(item.question(), properties.bm25Candidates());
            List<SearchHit> fused = ReciprocalRankFusion.fuse(List.of(bm25, dense, exact), 80);
            List<OntologyEvidence> facts = graph.expand(fused);
            retrievalNanos += System.nanoTime() - queryStarted;

            bm25Recall += matched(item.expected(), text(bm25));
            vectorRecall += matched(item.expected(), text(dense));
            hybridRecall += matched(item.expected(), text(facts));
            totalFacts += facts.size();
            groundedFacts += (int) facts.stream().filter(fact -> fact.factId().matches("F[0-9A-F]{12}")).count();
        }

        assertTrue(bm25Recall > 0, "BM25 should retrieve at least one expected item");
        assertTrue(vectorRecall > 0, "dense retrieval should retrieve at least one expected item");
        assertTrue(hybridRecall >= bm25Recall, "graph-expanded hybrid retrieval should preserve BM25 coverage");
        assertTrue(hybridRecall > 0, "hybrid retrieval should find expected graph evidence");
        assertTrue(expectedTotal > 0);
        System.out.printf(Locale.ROOT,
                "Retrieval evaluation Recall@20: BM25 %.3f, dense %.3f, hybrid+graph %.3f; "
                        + "grounding %.3f; index %.1f ms; mean query %.1f ms%n",
                (double) bm25Recall / expectedTotal, (double) vectorRecall / expectedTotal,
                (double) hybridRecall / expectedTotal, totalFacts == 0 ? 0 : (double) groundedFacts / totalFacts,
                indexingNanos / 1_000_000.0, retrievalNanos / cases.size() / 1_000_000.0);
    }

    @Test
    void fallsBackToBm25WhenEmbeddingProviderIsUnavailable() {
        OntologyProperties properties = properties(SAMPLE, tempDirectory.resolve("tdb-fallback").toString());
        OntologyStore store = new OntologyStore(properties);
        store.load();
        EmbeddingProvider unavailable = texts -> { throw new IllegalStateException("Ollama is offline"); };
        LuceneOntologyIndex index = new LuceneOntologyIndex(properties, store, unavailable);
        index.rebuild();

        assertFalse(index.searchBm25("River Watch", 10).isEmpty());
        assertTrue(index.searchDense("River Watch", 10).isEmpty());
    }

    @Test
    void sourceFingerprintAndTripleCountArePersistedForStartupReuse() {
        String dataDirectory = tempDirectory.resolve("tdb-reuse").toString();
        OntologyProperties properties = properties(SAMPLE, dataDirectory);
        OntologyStore first = new OntologyStore(properties);
        first.load();
        String fingerprint = first.sourceFingerprint();
        long triples = first.status().tripleCount();

        OntologyStore second = new OntologyStore(properties);
        second.loadIfChanged();

        assertEquals(fingerprint, second.sourceFingerprint());
        assertEquals(triples, second.status().tripleCount());
        assertTrue(second.status().loaded());
    }

    @Test
    void changedOntologySourceRefreshesTheGraphAndDerivedIndex() throws Exception {
        Path changedSource = tempDirectory.resolve("changed-ontology.ttl");
        Files.copy(Path.of(SAMPLE), changedSource);
        String dataDirectory = tempDirectory.resolve("tdb-changed").toString();
        OntologyProperties properties = properties(changedSource.toString(), dataDirectory);
        OntologyStore firstStore = new OntologyStore(properties);
        firstStore.load();
        String firstFingerprint = firstStore.sourceFingerprint();
        long firstCount = firstStore.status().tripleCount();
        LuceneOntologyIndex firstIndex = new LuceneOntologyIndex(properties, firstStore, new DeterministicEmbeddings());
        firstIndex.rebuild();

        Files.writeString(changedSource,
                "\n<https://example.org/ontology/sample#temporaryService> "
                        + "<http://www.w3.org/2000/01/rdf-schema#label> \"Temporary service\" .\n",
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        OntologyStore changedStore = new OntologyStore(properties);
        changedStore.loadIfChanged();
        LuceneOntologyIndex changedIndex = new LuceneOntologyIndex(properties, changedStore, new DeterministicEmbeddings());
        changedIndex.rebuildIfNeeded();

        assertFalse(firstFingerprint.equals(changedStore.sourceFingerprint()));
        assertEquals(firstCount + 1, changedStore.status().tripleCount());
        assertFalse(changedIndex.searchBm25("Temporary service", 10).isEmpty());
    }

    @Test
    void missingOntologySourceLeavesAUsefulNotLoadedStatus() {
        OntologyProperties properties = properties(tempDirectory.resolve("missing.ttl").toString(),
                tempDirectory.resolve("tdb-missing").toString());
        OntologyStore store = new OntologyStore(properties);
        store.loadIfChanged();

        assertFalse(store.status().loaded());
        assertEquals(0, store.status().tripleCount());
    }

    private OntologyProperties properties(String source, String dataDirectory) {
        return new OntologyProperties(source, dataDirectory, 20, "http://localhost:11434", "test-embedder",
                50, 50, 2, 100, 8, 64, "test-index", false, "", "", "");
    }

    private List<EvaluationCase> fixture() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream input = getClass().getResourceAsStream("/retrieval-evaluation.json")) {
            return mapper.readValue(input, new TypeReference<>() { });
        }
    }

    private String text(List<?> values) {
        return values.toString().toLowerCase(Locale.ROOT);
    }

    private int matched(List<String> expected, String actual) {
        return (int) expected.stream().filter(term -> actual.contains(term.toLowerCase(Locale.ROOT))).count();
    }

    private record EvaluationCase(String question, List<String> expected) { }

    private static final class DeterministicEmbeddings implements EmbeddingProvider {
        @Override
        public List<float[]> embed(List<String> texts) {
            List<float[]> vectors = new ArrayList<>();
            for (String text : texts) {
                float[] vector = new float[64];
                for (String token : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
                    if (!token.isBlank()) vector[Math.floorMod(token.hashCode(), vector.length)] += 1f;
                }
                vectors.add(vector);
            }
            return vectors;
        }
    }
}
