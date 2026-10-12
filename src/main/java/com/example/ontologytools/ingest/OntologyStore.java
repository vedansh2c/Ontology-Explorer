package com.example.ontologytools.ingest;

import com.example.ontologytools.config.OntologyProperties;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.ReadWrite;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.tdb2.TDB2Factory;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class OntologyStore {
    private static final Logger log = LoggerFactory.getLogger(OntologyStore.class);

    private final OntologyProperties properties;
    private final Path sourceMetadataPath;
    private final AtomicBoolean loaded = new AtomicBoolean(false);
    private volatile long tripleCount;
    private volatile String sourceFingerprint = "";

    public OntologyStore(OntologyProperties properties) {
        this.properties = properties;
        this.sourceMetadataPath = Path.of(properties.dataDirectory(), "ontology-source.properties");
    }

    public synchronized void load() {
        load(true);
    }

    public synchronized void loadIfChanged() {
        load(false);
    }

    private void load(boolean force) {
        Path source = Path.of(properties.source());
        if (!Files.isRegularFile(source)) {
            loaded.set(false);
            tripleCount = 0;
            sourceFingerprint = "";
            log.warn("Ontology source file was not found: {}", source.toAbsolutePath());
            return;
        }
        Path directory = Path.of(properties.dataDirectory());
        try {
            Files.createDirectories(directory);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Unable to create ontology data directory: " + directory, exception);
        }
        String fingerprint = fingerprint(source);
        Dataset dataset = TDB2Factory.connectDataset(directory.toString());
        try {
            if (!force && isCurrent(dataset, fingerprint)) {
                sourceFingerprint = fingerprint;
                loaded.set(true);
                return;
            }
            loaded.set(false);
            tripleCount = 0;
            dataset.begin(org.apache.jena.query.ReadWrite.WRITE);
            try {
                DatasetGraph graph = dataset.asDatasetGraph();
                graph.clear();
                RDFDataMgr.read(dataset, source.toUri().toString());
                tripleCount = countTriples(graph);
                dataset.commit();
                loaded.set(true);
                sourceFingerprint = fingerprint;
                writeSourceMetadata(fingerprint, tripleCount);
                log.info("Loaded {} triples from ontology source {}", tripleCount, source.toAbsolutePath());
            } catch (RuntimeException exception) {
                dataset.abort();
                throw exception;
            } finally {
                dataset.end();
            }
        } finally {
            dataset.close();
        }
    }

    private boolean isCurrent(Dataset dataset, String fingerprint) {
        if (!Files.isRegularFile(sourceMetadataPath)) {
            return false;
        }
        Properties metadata = new Properties();
        try (var input = Files.newInputStream(sourceMetadataPath)) {
            metadata.load(input);
        } catch (java.io.IOException exception) {
            return false;
        }
        if (!fingerprint.equals(metadata.getProperty("sourceFingerprint"))) {
            return false;
        }
        dataset.begin(ReadWrite.READ);
        try {
            var iterator = dataset.asDatasetGraph().find();
            if (!iterator.hasNext()) {
                return false;
            }
        } finally {
            dataset.end();
        }
        tripleCount = Long.parseLong(metadata.getProperty("tripleCount", "0"));
        return true;
    }

    private String fingerprint(Path source) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(source)) {
                byte[] buffer = new byte[1024 * 1024];
                int bytesRead;
                while ((bytesRead = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.io.IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Unable to fingerprint ontology source: " + source, exception);
        }
    }

    private void writeSourceMetadata(String fingerprint, long count) {
        Properties metadata = new Properties();
        metadata.setProperty("sourceFingerprint", fingerprint);
        metadata.setProperty("tripleCount", Long.toString(count));
        try (var output = Files.newOutputStream(sourceMetadataPath)) {
            metadata.store(output, "Ontology source metadata");
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Unable to save ontology source metadata", exception);
        }
    }

    private long countTriples(DatasetGraph graph) {
        long count = 0;
        var iterator = graph.find();
        while (iterator.hasNext()) {
            iterator.next();
            count++;
        }
        return count;
    }

    public Status status() {
        return new Status(properties.source(), loaded.get(), tripleCount);
    }

    public String sourceFingerprint() {
        return sourceFingerprint;
    }

    public record Status(String source, boolean loaded, long tripleCount) {
    }
}
