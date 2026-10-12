package com.example.ontologytools.retrieval;

import com.example.ontologytools.config.OntologyProperties;
import com.example.ontologytools.ingest.OntologyStore;
import org.apache.jena.graph.Node;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.ReadWrite;
import org.apache.jena.riot.out.NodeFmtLib;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.tdb2.TDB2Factory;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

@Service
public class LuceneOntologyIndex {
    private static final Logger log = LoggerFactory.getLogger(LuceneOntologyIndex.class);
    private static final int MAX_FACTS_PER_RECORD = 80;
    private static final int MAX_TEXT_LENGTH = 12_000;
    private static final String VECTOR_FIELD = "embedding";

    private final OntologyProperties properties;
    private final OntologyStore store;
    private final EmbeddingProvider embeddings;
    private final Path indexPath;
    private final Path metadataPath;
    private volatile boolean vectorIndexAvailable;

    public LuceneOntologyIndex(OntologyProperties properties, OntologyStore store, EmbeddingProvider embeddings) {
        this.properties = properties;
        this.store = store;
        this.embeddings = embeddings;
        this.indexPath = Path.of(properties.dataDirectory(), "lucene");
        this.metadataPath = Path.of(properties.dataDirectory(), "index-metadata.properties");
        this.vectorIndexAvailable = readMetadata().map(metadata ->
                Boolean.parseBoolean(metadata.getProperty("embeddingsComplete", "false"))).orElse(false);
    }

    public synchronized void rebuildIfNeeded() {
        if (!store.status().loaded() || metadataMatchesCurrentIndex()) return;
        rebuild();
    }

    public synchronized void rebuild() {
        if (!store.status().loaded()) return;
        try {
            Files.createDirectories(indexPath);
            Files.deleteIfExists(metadataPath);
            vectorIndexAvailable = false;
            long totalDocuments = 0;
            long embeddedDocuments = 0;
            int embeddingDimension = 0;
            boolean embeddingAvailable = true;
            List<SearchRecord> batch = new ArrayList<>(Math.max(1, properties.embeddingBatchSize()));

            try (Directory directory = FSDirectory.open(indexPath);
                 IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(new StandardAnalyzer()))) {
                writer.deleteAll();
                Dataset dataset = TDB2Factory.connectDataset(properties.dataDirectory());
                try {
                    dataset.begin(ReadWrite.READ);
                    try {
                        DatasetGraph graph = dataset.asDatasetGraph();
                        Iterator<Quad> iterator = graph.find();
                        List<Quad> subjectFacts = new ArrayList<>();
                        Node subject = null;
                        Node graphName = null;
                        while (iterator.hasNext()) {
                            Quad quad = iterator.next();
                            if (!subjectFacts.isEmpty() && (!quad.getSubject().equals(subject)
                                    || !quad.getGraph().equals(graphName))) {
                                batch.addAll(recordsFor(subject, graphName, subjectFacts));
                                subjectFacts.clear();
                                if (batch.size() >= Math.max(1, properties.embeddingBatchSize())) {
                                    BatchResult result = writeBatch(writer, batch, embeddingAvailable, embeddingDimension);
                                    totalDocuments += result.total();
                                    embeddedDocuments += result.embedded();
                                    embeddingAvailable = result.embeddingAvailable();
                                    embeddingDimension = result.dimension();
                                    batch.clear();
                                }
                            }
                            if (subjectFacts.isEmpty()) {
                                subject = quad.getSubject();
                                graphName = quad.getGraph();
                            }
                            if (subjectFacts.size() < MAX_FACTS_PER_RECORD) subjectFacts.add(quad);
                        }
                        if (!subjectFacts.isEmpty()) batch.addAll(recordsFor(subject, graphName, subjectFacts));
                        if (!batch.isEmpty()) {
                            BatchResult result = writeBatch(writer, batch, embeddingAvailable, embeddingDimension);
                            totalDocuments += result.total();
                            embeddedDocuments += result.embedded();
                        }
                    } finally {
                        dataset.end();
                    }
                } finally {
                    dataset.close();
                }
                writer.commit();
            }

            vectorIndexAvailable = totalDocuments > 0 && embeddedDocuments == totalDocuments;
            writeMetadata(totalDocuments, embeddedDocuments, embeddingDimension);
            log.info("Built ontology index: {} contextual records, {} embedded, model={}, dimension={}",
                    totalDocuments, embeddedDocuments, properties.embeddingModel(), embeddingDimension);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to build ontology search index", exception);
        }
    }

    public List<SearchHit> searchBm25(String question, int limit) {
        if (!store.status().loaded() || limit <= 0) return List.of();
        try {
            Query query = new QueryParser("text", new StandardAnalyzer()).parse(QueryParser.escape(question));
            return search(query, limit, "bm25");
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to search ontology text index", exception);
        }
    }

    public List<SearchHit> searchExact(String question, int limit) {
        if (!store.status().loaded() || limit <= 0) return List.of();
        Set<String> terms = exactNgrams(question);
        if (terms.isEmpty()) return List.of();
        BooleanQuery.Builder query = new BooleanQuery.Builder();
        for (String term : terms) {
            query.add(new TermQuery(new Term("exactTerm", term)), BooleanClause.Occur.SHOULD);
        }
        query.setMinimumNumberShouldMatch(1);
        return search(query.build(), limit, "exact");
    }

    public List<SearchHit> searchDense(String question, int limit) {
        if (!store.status().loaded() || !vectorIndexAvailable || limit <= 0) return List.of();
        try {
            List<float[]> queryVectors = embeddings.embed(List.of(question));
            if (queryVectors.isEmpty()) return List.of();
            Query query = new KnnFloatVectorQuery(VECTOR_FIELD, queryVectors.getFirst(), limit);
            return search(query, limit, "vector");
        } catch (Exception exception) {
            log.warn("Dense retrieval unavailable; continuing with lexical and graph retrieval: {}", exception.getMessage());
            return List.of();
        }
    }

    private List<SearchHit> search(Query query, int limit, String method) {
        try (Directory directory = FSDirectory.open(indexPath);
             DirectoryReader reader = DirectoryReader.open(directory)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            ScoreDoc[] results = searcher.search(query, limit).scoreDocs;
            List<SearchHit> hits = new ArrayList<>(results.length);
            for (int index = 0; index < results.length; index++) {
                ScoreDoc result = results[index];
                Document document = searcher.storedFields().document(result.doc);
                hits.add(new SearchHit(
                        document.get("id"), document.get("recordType"), document.get("entity"),
                        document.get("graph"), document.get("content"), method, result.score, index + 1));
            }
            return hits;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read ontology search index", exception);
        }
    }

    private BatchResult writeBatch(IndexWriter writer, List<SearchRecord> records,
                                   boolean embeddingAvailable, int currentDimension) throws IOException {
        List<float[]> vectors = List.of();
        if (embeddingAvailable) {
            try {
                vectors = embeddings.embed(records.stream().map(SearchRecord::text).toList());
                if (vectors.size() != records.size()) throw new IllegalStateException("Embedding batch size mismatch.");
                int dimension = vectors.getFirst().length;
                if (vectors.stream().anyMatch(vector -> vector.length != dimension)
                        || (currentDimension != 0 && dimension != currentDimension)) {
                    throw new IllegalStateException("Embedding dimensions changed within an index build.");
                }
                currentDimension = dimension;
            } catch (Exception exception) {
                log.warn("Ollama embeddings unavailable; this index build will use BM25 only: {}", exception.getMessage());
                embeddingAvailable = false;
                vectors = List.of();
            }
        }

        for (int index = 0; index < records.size(); index++) {
            SearchRecord record = records.get(index);
            Document document = new Document();
            document.add(new StringField("id", record.id(), Field.Store.YES));
            document.add(new StringField("recordType", record.type(), Field.Store.YES));
            document.add(new StringField("entity", record.entity(), Field.Store.YES));
            document.add(new StringField("graph", record.graph(), Field.Store.YES));
            document.add(new TextField("text", record.text(), Field.Store.NO));
            document.add(new StoredField("content", record.text()));
            for (String exactTerm : record.exactTerms()) {
                document.add(new StringField("exactTerm", exactTerm, Field.Store.NO));
            }
            if (embeddingAvailable && !vectors.isEmpty()) {
                document.add(new KnnFloatVectorField(VECTOR_FIELD, vectors.get(index), VectorSimilarityFunction.COSINE));
            }
            writer.addDocument(document);
        }
        return new BatchResult(records.size(), vectors.size(), embeddingAvailable, currentDimension);
    }

    private List<SearchRecord> recordsFor(Node subject, Node graph, List<Quad> facts) {
        String entity = NodeFmtLib.strNT(subject);
        String graphValue = isDefaultGraph(graph) ? "" : NodeFmtLib.strNT(graph);
        String baseId = graphValue + "|" + entity;
        String localName = localName(subject);
        StringBuilder summary = new StringBuilder(entity).append(' ').append(pretty(localName));
        StringBuilder neighborhood = new StringBuilder("Neighborhood for ").append(pretty(localName))
                .append(" ").append(entity).append('.');
        Set<String> exactTerms = new HashSet<>();
        addExactTerm(exactTerms, localName);
        int relevant = 0;
        boolean property = false;
        List<String> values = new ArrayList<>();

        for (Quad fact : facts) {
            String predicate = localName(fact.getPredicate());
            String object = nodeText(fact.getObject());
            String line = pretty(predicate) + " " + object;
            if (isLabelPredicate(predicate)) {
                addExactTerm(exactTerms, nodeText(fact.getObject()));
                if (summary.length() < MAX_TEXT_LENGTH) summary.append(". Label: ").append(object);
            } else if (isDescriptionPredicate(predicate) && summary.length() < MAX_TEXT_LENGTH) {
                summary.append(". Description: ").append(object);
            } else if (isTypePredicate(predicate)) {
                summary.append(". Type: ").append(pretty(localName(fact.getObject())));
                if (isPropertyType(localName(fact.getObject()))) property = true;
            } else if (predicate.equalsIgnoreCase("domain") || predicate.equalsIgnoreCase("range")) {
                property = true;
            }
            if (relevant < MAX_FACTS_PER_RECORD && neighborhood.length() < MAX_TEXT_LENGTH) {
                neighborhood.append(" ").append(line).append('.');
                values.add(line);
                relevant++;
            }
        }
        String summaryText = trim(summary.toString());
        String neighborhoodText = trim(neighborhood.toString());
        List<String> exact = List.copyOf(exactTerms);
        List<SearchRecord> records = new ArrayList<>(3);
        records.add(new SearchRecord("entity|" + baseId, "entity", entity, graphValue, summaryText, exact));
        if (!values.isEmpty()) {
            records.add(new SearchRecord("neighborhood|" + baseId, "neighborhood", entity, graphValue,
                    neighborhoodText, exact));
        }
        if (property) {
            records.add(new SearchRecord("property|" + baseId, "property", entity, graphValue,
                    "Ontology property " + pretty(localName) + ". " + neighborhoodText, exact));
        }
        return records;
    }

    private Set<String> exactNgrams(String question) {
        String[] tokens = question.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+");
        List<String> words = new ArrayList<>();
        for (String token : tokens) if (!token.isBlank()) words.add(token);
        Set<String> terms = new LinkedHashSet<>();
        for (int start = 0; start < words.size(); start++) {
            StringBuilder phrase = new StringBuilder();
            for (int end = start; end < Math.min(words.size(), start + 5); end++) {
                phrase.append(words.get(end));
                terms.add(phrase.toString());
                if (terms.size() >= 128) return terms;
            }
        }
        return terms;
    }

    private void writeMetadata(long totalDocuments, long embeddedDocuments, int dimension) {
        Properties metadata = new Properties();
        metadata.setProperty("sourceFingerprint", store.sourceFingerprint());
        metadata.setProperty("embeddingModel", properties.embeddingModel());
        metadata.setProperty("embeddingDimension", Integer.toString(dimension));
        metadata.setProperty("requestedEmbeddingDimension", Integer.toString(properties.embeddingDimensions()));
        metadata.setProperty("indexVersion", properties.indexVersion());
        metadata.setProperty("builtAt", Instant.now().toString());
        metadata.setProperty("documentCount", Long.toString(totalDocuments));
        metadata.setProperty("embeddedDocumentCount", Long.toString(embeddedDocuments));
        metadata.setProperty("embeddingsComplete", Boolean.toString(totalDocuments > 0 && totalDocuments == embeddedDocuments));
        try (var output = Files.newOutputStream(metadataPath)) {
            metadata.store(output, "Ontology Lucene index metadata");
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to write ontology index metadata", exception);
        }
    }

    private boolean metadataMatchesCurrentIndex() {
        var metadata = readMetadata();
        if (metadata.isEmpty()) return false;
        Properties values = metadata.get();
        boolean matches = store.sourceFingerprint().equals(values.getProperty("sourceFingerprint"))
                && properties.embeddingModel().equals(values.getProperty("embeddingModel"))
                && Integer.toString(properties.embeddingDimensions()).equals(values.getProperty("requestedEmbeddingDimension"))
                && properties.indexVersion().equals(values.getProperty("indexVersion"));
        if (!matches) return false;
        try (Directory directory = FSDirectory.open(indexPath)) {
            return DirectoryReader.indexExists(directory);
        } catch (IOException exception) {
            return false;
        }
    }

    private java.util.Optional<Properties> readMetadata() {
        if (!Files.isRegularFile(metadataPath)) return java.util.Optional.empty();
        Properties metadata = new Properties();
        try (var input = Files.newInputStream(metadataPath)) {
            metadata.load(input);
            return java.util.Optional.of(metadata);
        } catch (IOException exception) {
            log.warn("Unable to read ontology index metadata: {}", exception.getMessage());
            return java.util.Optional.empty();
        }
    }

    private String nodeText(Node node) {
        if (node.isLiteral()) {
            String language = node.getLiteralLanguage();
            return node.getLiteralLexicalForm() + (language == null || language.isBlank() ? "" : "@" + language);
        }
        return NodeFmtLib.strNT(node);
    }

    private String localName(Node node) {
        if (node.isURI()) {
            String uri = node.getURI();
            int split = Math.max(uri.lastIndexOf('#'), Math.max(uri.lastIndexOf('/'), uri.lastIndexOf(':')));
            return split >= 0 && split + 1 < uri.length() ? uri.substring(split + 1) : uri;
        }
        return node.isBlank() ? node.getBlankNodeLabel() : nodeText(node);
    }

    private String pretty(String value) {
        return value.replaceAll("([a-z0-9])([A-Z])", "$1 $2").replace('_', ' ');
    }

    private void addExactTerm(Set<String> terms, String value) {
        String normalized = value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
        if (!normalized.isBlank()) terms.add(normalized);
    }

    private boolean isLabelPredicate(String predicate) {
        String value = predicate.toLowerCase(Locale.ROOT);
        return value.equals("label") || value.equals("name") || value.equals("altlabel")
                || value.equals("preflabel") || value.equals("alias");
    }

    private boolean isDescriptionPredicate(String predicate) {
        String value = predicate.toLowerCase(Locale.ROOT);
        return value.equals("comment") || value.equals("description") || value.equals("abstract")
                || value.equals("definition") || value.equals("scopenote");
    }

    private boolean isTypePredicate(String predicate) { return predicate.equals("type"); }

    private boolean isPropertyType(String type) {
        return type.equals("ObjectProperty") || type.equals("DatatypeProperty") || type.equals("Property")
                || type.equals("AnnotationProperty") || type.equals("FunctionalProperty")
                || type.equals("TransitiveProperty") || type.equals("SymmetricProperty")
                || type.equals("InverseFunctionalProperty") || type.equals("AsymmetricProperty")
                || type.equals("ReflexiveProperty") || type.equals("IrreflexiveProperty");
    }

    private boolean isDefaultGraph(Node graph) {
        return Quad.isDefaultGraph(graph) || Quad.defaultGraphIRI.equals(graph);
    }

    private String trim(String value) {
        return value.length() <= MAX_TEXT_LENGTH ? value : value.substring(0, MAX_TEXT_LENGTH);
    }

    private record BatchResult(int total, int embedded, boolean embeddingAvailable, int dimension) { }
}
