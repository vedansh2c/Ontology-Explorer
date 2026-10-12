# Ontology Tools

A local Java/Spring Boot starting point for asking questions about a large OWL ontology with Apache Jena, RAG retrieval, and GitHub Copilot SDK integration.

## Prerequisites

- JDK 21 or newer
- Node.js 20.19 or newer (or 22.12 or newer) and npm
- A GitHub Copilot token for generated answers (optional; retrieval works without it)
- Ollama for embeddings (optional for the current BM25 retrieval slice)

The backend automatically loads and indexes the included `src/main/resources/sample-ontology.ttl` at startup. Set `ONTOLOGY_SOURCE` to select another RDF/OWL file; you can put it in the project-root `.env` file or export it in the shell. The configured source replaces the data in the local TDB2 store when its SHA-256 fingerprint changes. The sample ontology remains unchanged; entity, property, and neighborhood records are derived into search indexes.

## Included sample ontology

`src/main/resources/sample-ontology.ttl` is a compact but connected example for trying ingestion and retrieval. It models a general hierarchy of things, agents (people and organizations), places, artifacts, events, projects, and services, with additional classes for health, species, and natural resources. Object properties link entities (for example, a person works for an organization, a project uses a dataset, and a project monitors a resource). Datatype properties hold literal values such as names, dates, status, population, and coordinates.

The example data connects Maya Chen and Omar Diallo with Greenfield University, Harbor City Environmental Lab, and the River Watch research project. River Watch uses a dataset, produces a report, monitors freshwater, observes blue herons, depends on a sensor network, and is associated with a regional forum. The ontology therefore supports questions that cross several domains, such as who leads a project, what data it uses, where it operates, and what it monitors.

The file uses Turtle syntax and the RDF vocabulary used by Apache Jena. Its `ex:` namespace is `https://example.org/ontology/sample#`; the individuals are illustrative sample data.

## Run in development

Install the frontend dependencies once:

```sh
npm --prefix frontend install
```

Start the backend in one terminal. The Maven Wrapper downloads Maven 3.9.9 on its first run, so a separate Maven install is not needed:

```sh
./mvnw spring-boot:run
```

Start the React dev server in a second terminal:

```sh
npm --prefix frontend run dev
```

Open http://127.0.0.1:5173. Vite proxies `/api` requests to Spring Boot on port 8080. The application searches its contextual Lucene index and RDF graph, then sends selected evidence to Copilot.

The backend loads and indexes the configured ontology before it is ready. To reload it manually while the backend is running:

```sh
curl -X POST http://localhost:8080/api/ingest
```

Reloading replaces the data in Jena TDB2 and rebuilds the Lucene index. Set `ONTOLOGY_RETRIEVAL_LIMIT` to control how many evidence statements are sent to the agent.

## Hybrid ontology retrieval

Retrieval combines Lucene BM25, exact entity/property label lookup, optional Ollama embeddings, Reciprocal Rank Fusion, and bounded incoming/outgoing graph traversal. The index contains contextual entity, property, and neighborhood records; retrieved answers include exact RDF facts, graph names, stable fact IDs, and retrieval provenance.

To enable dense retrieval locally, start Ollama and install the configured model before starting the backend:

```sh
ollama pull qwen3-embedding:4b
```

The default is `qwen3-embedding:4b` at 1024 dimensions, batched in groups of 32. Use `qwen3-embedding:0.6b` on lower-memory machines or `qwen3-embedding:8b` for higher capacity. If Ollama is unavailable, startup continues with BM25 and exact lookup; once Ollama is available, call `/api/ingest` to rebuild the index with vectors.

Optional reranking uses a configurable Cohere-compatible `/rerank` endpoint and is disabled by default. Set `ONTOLOGY_RERANKER_ENABLED=true`, `ONTOLOGY_RERANKER_URL`, and `ONTOLOGY_RERANKER_MODEL`; set `ONTOLOGY_RERANKER_API_KEY` when the endpoint requires a bearer token. Reranker failures fall back to fused retrieval.

The main tuning variables are `ONTOLOGY_RETRIEVAL_LIMIT` (20 facts), `ONTOLOGY_BM25_CANDIDATES` (50), `ONTOLOGY_VECTOR_CANDIDATES` (50), `ONTOLOGY_GRAPH_DEPTH` (2), `ONTOLOGY_GRAPH_NODE_LIMIT` (100), `ONTOLOGY_EMBEDDING_BATCH_SIZE` (32), `ONTOLOGY_EMBEDDING_DIMENSIONS` (1024), and `ONTOLOGY_INDEX_VERSION` (2). Index metadata is stored under the configured ontology data directory and tracks the source fingerprint, model, dimensions, version, and build time.

Run the retrieval evaluation suite with `./mvnw test`. Its expected questions live in `src/test/resources/retrieval-evaluation.json`, separate from the sample ontology. The evaluation reports Recall@20 for BM25, dense retrieval, and hybrid graph retrieval; a deterministic test embedder keeps it runnable without Ollama.

Ask a question after startup:

```sh
curl -N -X POST http://localhost:8080/api/question \
  -H 'Content-Type: application/json' \
  --data '{"question":"What does River Watch monitor?"}'
```

Set the ontology path and optional Copilot token in the local `.env` file before starting the application. The file is ignored by Git:

```text
ONTOLOGY_SOURCE=/absolute/path/to/ontology.owl
COPILOT_GITHUB_TOKEN=your-token
```

For a fine-grained PAT, enable the `Copilot Requests` repository/account permission. A token that works with the regular GitHub API can still be rejected by Copilot if this permission is missing or the token is expired.

The application also accepts `GH_TOKEN`. A process environment variable takes precedence over `.env`. Without a token, the app still retrieves and returns matching ontology evidence, but it cannot generate a Copilot answer.

## Build the production frontend

Build the React app into Spring Boot's static resources, then package or run the backend:

```sh
npm --prefix frontend run build
./mvnw spring-boot:run
```

The built UI is served by Spring Boot at http://localhost:8080. Rebuild it after changing files in `frontend/src`.
