package com.example.ontologytools.retrieval;

import com.example.ontologytools.config.OntologyProperties;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.ReadWrite;
import org.apache.jena.riot.out.NodeFmtLib;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.tdb2.TDB2Factory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

@Service
public class GraphEvidenceRetriever {
    private final OntologyProperties properties;

    public GraphEvidenceRetriever(OntologyProperties properties) {
        this.properties = properties;
    }

    public List<OntologyEvidence> expand(List<SearchHit> candidates) {
        if (candidates.isEmpty()) return List.of();
        Map<String, EvidenceScore> evidence = new LinkedHashMap<>();
        Set<String> visited = new HashSet<>();
        Queue<Visit> pending = new ArrayDeque<>();
        int maxNodes = Math.max(1, properties.graphNodeLimit());
        int roots = 0;
        for (SearchHit hit : candidates) {
            if (roots++ >= maxNodes) break;
            Node entity = parseNode(hit.entity());
            if (entity != null && !entity.isLiteral()) {
                pending.add(new Visit(entity, parseGraph(hit.graph()), 0, hit.score(), hit.method()));
            }
        }

        Dataset dataset = TDB2Factory.connectDataset(properties.dataDirectory());
        try {
            dataset.begin(ReadWrite.READ);
            try {
                DatasetGraph graph = dataset.asDatasetGraph();
                while (!pending.isEmpty() && visited.size() < maxNodes) {
                    Visit current = pending.remove();
                    String key = NodeFmtLib.strNT(current.graph()) + "|" + NodeFmtLib.strNT(current.node());
                    if (!visited.add(key)) continue;
                    collect(graph.find(current.graph(), current.node(), Node.ANY, Node.ANY), current, evidence, pending);
                    collect(graph.find(current.graph(), Node.ANY, Node.ANY, current.node()), current, evidence, pending);
                }
            } finally {
                dataset.end();
            }
        } finally {
            dataset.close();
        }

        return evidence.values().stream()
                .sorted(Comparator.comparingDouble((EvidenceScore item) -> item.score).reversed())
                .limit(Math.max(1, properties.retrievalLimit()))
                .map(EvidenceScore::toEvidence)
                .toList();
    }

    private void collect(Iterator<Quad> iterator, Visit current, Map<String, EvidenceScore> evidence,
                         Queue<Visit> pending) {
        int factsForNode = 0;
        while (iterator.hasNext() && factsForNode < 100) {
            Quad quad = iterator.next();
            factsForNode++;
            double score = current.score() / (current.depth() + 1.0);
            String method = "graph-depth-" + current.depth() + "[" + current.method() + "]";
            String id = factId(quad);
            EvidenceScore previous = evidence.get(id);
            if (previous == null || previous.score < score) {
                evidence.put(id, new EvidenceScore(id, quad, method, score));
            }
            if (current.depth() < Math.max(0, properties.graphDepth())) {
                Node neighbor = quad.getSubject().equals(current.node()) ? quad.getObject() : quad.getSubject();
                if (neighbor.isURI() || neighbor.isBlank()) {
                    pending.add(new Visit(neighbor, quad.getGraph(), current.depth() + 1, score, current.method()));
                }
            }
        }
    }

    private String factId(Quad quad) {
        String canonical = NodeFmtLib.strNT(quad.getGraph()) + " " + NodeFmtLib.strNT(quad.getSubject()) + " "
                + NodeFmtLib.strNT(quad.getPredicate()) + " " + NodeFmtLib.strNT(quad.getObject());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return "F" + HexFormat.of().formatHex(digest).substring(0, 12).toUpperCase();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private Node parseNode(String value) {
        if (value == null || value.isBlank()) return null;
        if (value.startsWith("<") && value.endsWith(">")) return NodeFactory.createURI(value.substring(1, value.length() - 1));
        if (value.startsWith("_:")) return NodeFactory.createBlankNode(value.substring(2));
        return NodeFactory.createURI(value);
    }

    private Node parseGraph(String value) {
        if (value == null || value.isBlank() || value.contains("DefaultGraphNode")) {
            return Quad.defaultGraphNodeGenerated;
        }
        return parseNode(value);
    }

    private record Visit(Node node, Node graph, int depth, double score, String method) { }

    private static final class EvidenceScore {
        private final String id;
        private final Quad quad;
        private final String method;
        private final double score;

        private EvidenceScore(String id, Quad quad, String method, double score) {
            this.id = id;
            this.quad = quad;
            this.method = method;
            this.score = score;
        }

        private OntologyEvidence toEvidence() {
            String graph = Quad.isDefaultGraph(quad.getGraph()) ? "" : NodeFmtLib.strNT(quad.getGraph());
            return new OntologyEvidence(id, NodeFmtLib.strNT(quad.getSubject()), NodeFmtLib.strNT(quad.getPredicate()),
                    NodeFmtLib.strNT(quad.getObject()), graph, method, score);
        }
    }
}
