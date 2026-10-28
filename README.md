# ⚡ Enterprise Spring AI RAG Pipeline (Java 25)

An end-to-end, production-grade **Retrieval-Augmented Generation (RAG)** pipeline built with **Spring Boot 3.4**, **Spring AI 1.0.0-M6**, **Java 25 (Loom)**, **Apache Tika**, **Ollama**, and **Spring VectorStore**.

This application provides a complete, 100% local, privacy-first document ingestion and question-answering workflow with **zero cloud dependencies**. It combines an intelligent sliding-window text chunker, local vector embeddings, persistent disk-backed vector storage, and an interactive dark-mode Glassmorphic Web Dashboard.

---

## 📑 Table of Contents

- [Key Highlights](#-key-highlights)
- [Architecture & Workflow](#-architecture--workflow)
- [Chunking Strategy: Why Overlap Matters](#-chunking-strategy-why-overlap-matters)
- [Tech Stack](#-tech-stack)
- [Project Structure](#-project-structure)
- [Prerequisites & Setup](#-prerequisites--setup)
- [Running the Application](#-running-the-application)
- [Web UI Dashboard](#-web-ui-dashboard)
- [REST API Reference](#-rest-api-reference)
- [Configuration Reference (`application.yml`)](#-configuration-reference)
- [Engineering & Troubleshooting Notes](#-engineering--troubleshooting-notes)
- [Testing](#-testing)

---

## ✨ Key Highlights

- **🔒 100% Local & Privacy-First**: Run entirely on your local machine using **Ollama** for embeddings (`nomic-embed-text`) and completions (`qwen2.5:1.5b` or `llama3.2`). No external API keys or cloud calls required.
- **📄 Universal Document Parsing**: Supports `.pdf`, `.docx`, `.doc`, `.txt`, `.md`, `.html`, `.csv`, `.json`, and `.pptx` via Apache Tika and PDF readers.
- **✂️ Intelligent Overlapping Splitter**: Chunks documents using a sliding-window algorithm that respects natural sentence and word boundaries, avoiding truncated context.
- **💾 Auto-Persisting Vector Database**: Vector embeddings are indexed in `SimpleVectorStore` and automatically saved to disk (`data/vectors/vector_store.json`), surviving server restarts.
- **🎯 Precision Retrieval & Prompt Augmentation**: Computes cosine similarity to select top-K chunks above a configurable threshold and injects them into an augmented system prompt.
- **💬 Source Citations & Transparency**: Every chat response returns the exact document source, chunk index, and similarity confidence score for verification.
- **🎨 Modern Glassmorphic Web Dashboard**: Single-page application with responsive dark-mode styling, real-time chunk preview, similarity inspector, and interactive chat interface.
- **☕ Built on Java 25 (Loom)**: Leverages modern Java virtual threads, records, and pattern matching on Spring Boot 3.4.

---

## 🏛️ Architecture & Workflow

```
+-----------------------------------------------------------------------------------+
|                                 1. INGESTION PHASE                                |
|                                                                                   |
|  [User Upload / API]                                                              |
|        │                                                                          |
|        ▼                                                                          |
|  [FileStorageService] ──────> Persists file to data/uploads/                      |
|        │                                                                          |
|        ▼                                                                          |
|  [Apache Tika / PDF Reader] > Extracts raw text, headings & document metadata     |
|        │                                                                          |
|        ▼                                                                          |
|  [OverlappingTextSplitter] ──> Sliding-window chunking (chunkSize=500, overlap=100)|
|        │                      Preserves natural sentence & word boundaries        |
|        ▼                                                                          |
|  [Ollama EmbeddingModel] ───> Generates dense vector representations (768-dim)    |
|        │                      via nomic-embed-text                                |
|        ▼                                                                          |
|  [VectorStoreService] ───────> Indexes vectors in SimpleVectorStore               |
|                               & persists index to data/vectors/vector_store.json  |
+-----------------------------------------------------------------------------------+
                                        │
                                        ▼
+-----------------------------------------------------------------------------------+
|                                2. RAG CHAT PHASE                                  |
|                                                                                   |
|  [User Query: "Why is chunk overlap important?"]                                 |
|        │                                                                          |
|        ▼                                                                          |
|  [Query Embedding] ──────────> nomic-embed-text embeds query string               |
|        │                                                                          |
|        ▼                                                                          |
|  [Vector Similarity Search] ─> Cosine similarity search (Top-K=4, Threshold=0.55) |
|        │                                                                          |
|        ▼                                                                          |
|  [Prompt Augmentation] ──────> Formulates grounded context:                       |
|                                "Answer based on: [Source 1, Source 2]..."         |
|        │                                                                          |
|        ▼                                                                          |
|  [ChatModel / ChatClient] ───> qwen2.5:1.5b synthesizes grounded response         |
|        │                                                                          |
|        ▼                                                                          |
|  [Response Delivery] ────────> Answer + Source Citations + Similarity Scores     |
+-----------------------------------------------------------------------------------+
```

---

## ✂️ Chunking Strategy: Why Overlap Matters

### The Boundary Loss Problem
When splitting text into fixed chunks without overlap, critical facts that span across chunk boundaries are cut in half:
```
[ Chunk 1: ...The system ensures high availability by ] | [ Chunk 2: replicating data across 3 regions... ]
```
During retrieval, a query about *"how the system ensures high availability"* might retrieve Chunk 1 without Chunk 2, causing the LLM to hallucinate or lack sufficient context.

### Sliding-Window Overlap with Natural Boundaries
The custom [`OverlappingTextSplitter`](file:///Users/rajesh/Desktop/ragpipeline/src/main/java/com/example/ragpipeline/splitter/OverlappingTextSplitter.java) solves this:
1. **Sliding Window**: Consecutive chunks share a configurable overlap (e.g., 100 characters).
2. **Boundary Snapping**: When scanning up to `chunkSize`, it scans backward for natural break points (sentence terminators `. `, `! `, `? `, newlines `\n`, or spaces ` `) so that words and sentences are never split in half.
3. **Context Preservation**: Key entities and assertions remain intact within both preceding and succeeding chunks.

---

## 🛠️ Tech Stack

| Component | Technology | Version | Description |
| :--- | :--- | :--- | :--- |
| **Language** | Java (OpenJDK Loom) | `25-loom` / `25+` | Modern records, pattern matching, virtual thread support |
| **Framework** | Spring Boot | `3.4.0` | Core container, Spring MVC, auto-configuration |
| **Core Libraries** | Spring Framework | `6.2.10` | Enterprise application foundation |
| **AI Framework** | Spring AI | `1.0.0-M6` | Starters for Ollama, VectorStore, ChatClient |
| **Document Parsers** | Apache Tika & PDFBox | `1.0.0-M6` | `spring-ai-tika-document-reader`, `spring-ai-pdf-document-reader` |
| **Vector DB** | `SimpleVectorStore` | `1.0.0-M6` | In-memory cosine similarity store with JSON persistence |
| **Embedding Model** | `nomic-embed-text` | `latest` | 768-dimensional local vector embeddings via Ollama |
| **Chat / LLM** | `qwen2.5:1.5b` / `llama3.2` | `latest` | Fast, lightweight local LLM reasoning via Ollama |
| **HTTP Client** | JDK `HttpClient` | Built-in | `JdkClientHttpRequestFactory` configured with 5-minute timeout |
| **Frontend** | Vanilla HTML5 / CSS3 / ES6 | - | Dark-mode glassmorphism dashboard, zero node/npm build needed |
| **Testing** | JUnit 5 & Mockito | `5.x` | `spring-boot-starter-test` for chunker and pipeline tests |

---

## 📁 Project Structure

```
ragpipeline/
├── pom.xml                                     # Maven build definition (Spring Boot 3.4.0, Spring AI 1.0.0-M6)
├── run.sh                                      # Automated startup script (sets Java 25 environment)
├── docker-compose.yml                          # Optional pgvector PostgreSQL container
├── README.md                                   # Project documentation
│
├── data/
│   ├── uploads/                                # Uploaded source files (.pdf, .docx, .md, etc.)
│   ├── vectors/
│   │   └── vector_store.json                   # Serialized vector database index
│   └── sample-docs/
│       └── spring_ai_architecture.md           # Ready-to-use sample document
│
└── src/
    ├── main/
    │   ├── java/com/example/ragpipeline/
    │   │   ├── RagPipelineApplication.java     # Spring Boot application entrypoint
    │   │   ├── config/
    │   │   │   ├── RagProperties.java          # Type-safe @ConfigurationProperties record
    │   │   │   ├── VectorStoreConfig.java      # SimpleVectorStore bean with JSON auto-load
    │   │   │   └── ChatClientConfig.java       # ChatClient bean & JdkClientHttpRequestFactory
    │   │   ├── controller/
    │   │   │   ├── RagIngestionController.java # Endpoints for upload, raw text, preview & reset
    │   │   │   └── RagChatController.java      # Endpoints for RAG chat, search & status
    │   │   ├── dto/                            # Java 25 records for API requests & responses
    │   │   │   ├── ChatRequest.java
    │   │   │   ├── ChatResponse.java
    │   │   │   ├── ChunkInfo.java
    │   │   │   ├── ChunkPreviewRequest.java
    │   │   │   ├── ChunkPreviewResponse.java
    │   │   │   ├── IngestRequest.java
    │   │   │   ├── IngestResponse.java
    │   │   │   ├── RetrievedSource.java
    │   │   │   └── VectorStoreStats.java
    │   │   ├── service/
    │   │   │   ├── FileStorageService.java     # Upload persistence & Apache Tika extraction
    │   │   │   ├── DocumentChunkingService.java# Chunking & preview coordination
    │   │   │   ├── VectorStoreService.java     # Vector embedding, indexing, search & persistence
    │   │   │   └── RagChatService.java         # Vector retrieval, prompt synthesis & LLM chat
    │   │   └── splitter/
    │   │       └── OverlappingTextSplitter.java# Sliding-window sentence-boundary chunker
    │   └── resources/
    │       ├── application.yml                 # Ollama endpoints, chunk sizes, thresholds
    │       └── static/                         # Web Dashboard UI
    │           ├── index.html                  # Glassmorphic single-page UI
    │           ├── css/style.css               # Dark-mode styling, animations, responsiveness
    │           └── js/app.js                   # REST API interactions & UI rendering
    └── test/
        └── java/com/example/ragpipeline/
            └── OverlappingTextSplitterTest.java# Chunker boundary & overlap validation tests
```

---

## 🚀 Prerequisites & Setup

### 1. Install & Start Ollama
Download Ollama from [ollama.com](https://ollama.com) or install via Homebrew:
```bash
brew install ollama
```

Start the Ollama daemon:
```bash
ollama serve
```

Pull the required embedding and chat models:
```bash
# 1. Embedding Model (768-dim)
ollama pull nomic-embed-text

# 2. Chat LLM (Fast and lightweight)
ollama pull qwen2.5:1.5b

# Optional: Alternative models
ollama pull llama3.2:latest
```

Verify that the models are installed:
```bash
curl http://localhost:11434/api/tags
```

---

## 🏃 Running the Application

### Option A: Using the Startup Script (Recommended)
The [`run.sh`](file:///Users/rajesh/Desktop/ragpipeline/run.sh) script sets the `JAVA_HOME` environment variable and launches the Spring Boot app:
```bash
cd /Users/rajesh/Desktop/ragpipeline
./run.sh
```

### Option B: Using the Maven Wrapper Directly
```bash
export JAVA_HOME="/Users/rajesh/Library/Java/JavaVirtualMachines/loom-ea-25-loom+1-11/Contents/Home"
./mvnw spring-boot:run
```

Once started, the application will display:
```
=================================================================
🚀 Spring AI RAG Pipeline started successfully on Java 25-loom
📂 UI Dashboard: http://localhost:8080
📖 API Endpoints: http://localhost:8080/api/rag/status
=================================================================
```

---

## 🖥️ Web UI Dashboard

Navigate to **[http://localhost:8080](http://localhost:8080)** in your browser.

The dashboard includes 4 interactive sections:

1. **Document Ingestion Panel**:
   - Drag-and-drop or file selector supporting `.pdf`, `.docx`, `.txt`, `.md`, `.html`, etc.
   - Raw text ingestion mode for pasting text directly.
   - Real-time sliders for `Chunk Size` (100–2000 chars) and `Chunk Overlap` (0–500 chars).
   - "Preview Chunks" button to inspect chunk boundaries before storing.
2. **Vector Similarity Search Inspector**:
   - Query the vector store without invoking the LLM.
   - Live adjustments for `Top-K` and `Similarity Threshold`.
   - Inspect cosine similarity scores, character counts, and metadata for each retrieved chunk.
3. **Interactive RAG Chatbot**:
   - Ask natural language questions against the stored documents.
   - Grounded LLM answers synthesized using Ollama (`qwen2.5:1.5b`).
   - Collapsible source citations showing the document name, chunk index, and snippet.
4. **Pipeline Status & Controls**:
   - Shows active models, stored chunk count, and index file size.
   - One-click "Reset Store" button to clear vector memory and uploads.

---

## 💻 REST API Reference

### 1. Ingest File with Overlapping Chunks
Uploads a document, extracts text using Apache Tika, chunks it, generates embeddings, and saves to the vector database.

- **URL**: `POST /api/rag/upload`
- **Content-Type**: `multipart/form-data`
- **Parameters**:
  - `file` *(required)*: Binary file (`.pdf`, `.docx`, `.md`, `.txt`, etc.)
  - `chunkSize` *(optional, default: 500)*: Chunk length in characters.
  - `chunkOverlap` *(optional, default: 100)*: Character overlap between consecutive chunks.

```bash
curl -X POST http://localhost:8080/api/rag/upload \
  -F "file=@data/sample-docs/spring_ai_architecture.md" \
  -F "chunkSize=500" \
  -F "chunkOverlap=100"
```

**Response (`200 OK`)**:
```json
{
  "success": true,
  "documentName": "spring_ai_architecture.md",
  "totalChunks": 11,
  "totalCharacters": 4210,
  "chunkSize": 500,
  "chunkOverlap": 100,
  "message": "Successfully parsed, split into 11 chunks (overlap: 100), embedded and saved to vector store.",
  "elapsedTimeMs": 2842,
  "sampleChunks": [
    {
      "chunkIndex": 0,
      "text": "Spring AI is an application framework that provides a modular abstraction layer...",
      "characterCount": 492,
      "wordCount": 72,
      "overlapChars": 100,
      "metadata": { "source": "spring_ai_architecture.md", "chunk_index": 0 }
    }
  ]
}
```

---

### 2. Ingest Raw Text
Ingests raw text directly without file uploading.

- **URL**: `POST /api/rag/ingest-text`
- **Content-Type**: `application/json`

```bash
curl -X POST http://localhost:8080/api/rag/ingest-text \
  -H "Content-Type: application/json" \
  -d '{
    "title": "Spring AI Overview",
    "content": "Spring AI provides an abstraction across multiple AI providers including Ollama, OpenAI, and Anthropic...",
    "chunkSize": 400,
    "chunkOverlap": 80
  }'
```

---

### 3. Preview Chunks (Dry Run)
Simulates document chunking and returns boundary details without writing to the vector store.

- **URL**: `POST /api/rag/preview-chunks`
- **Content-Type**: `application/json`

```bash
curl -X POST http://localhost:8080/api/rag/preview-chunks \
  -H "Content-Type: application/json" \
  -d '{
    "text": "Retrieval-Augmented Generation bridges internal knowledge with Large Language Models...",
    "chunkSize": 300,
    "chunkOverlap": 50
  }'
```

---

### 4. Vector Similarity Search Only
Queries the vector store directly and returns the most relevant chunks with similarity scores.

- **URL**: `POST /api/rag/search`
- **Parameters**:
  - `query` *(required)*: Search text.
  - `topK` *(optional, default: 4)*: Maximum number of chunks to return.
  - `threshold` *(optional, default: 0.55)*: Minimum cosine similarity score (0.0 to 1.0).

```bash
curl -X POST "http://localhost:8080/api/rag/search?query=Why%20is%20chunk%20overlap%20important?&topK=3&threshold=0.5"
```

**Response (`200 OK`)**:
```json
[
  {
    "rank": 1,
    "sourceName": "spring_ai_architecture.md",
    "chunkIndex": 4,
    "similarityScore": 0.812,
    "contentSnippet": "Without overlap, critical sentences that happen to fall across chunk boundaries are severed...",
    "metadata": { "source": "spring_ai_architecture.md", "chunk_index": 4 }
  }
]
```

---

### 5. Full RAG Chat Query
Executes the full RAG pipeline: embeds the query, searches for matching chunks, injects context into the prompt, queries the LLM, and returns the grounded answer with citations.

- **URL**: `POST /api/rag/chat`
- **Content-Type**: `application/json`

```bash
curl -X POST http://localhost:8080/api/rag/chat \
  -H "Content-Type: application/json" \
  -d '{
    "query": "How does chunk overlap prevent context loss in RAG pipelines?",
    "topK": 4,
    "similarityThreshold": 0.50
  }'
```

**Response (`200 OK`)**:
```json
{
  "query": "How does chunk overlap prevent context loss in RAG pipelines?",
  "answer": "Chunk overlap prevents context loss by carrying forward a portion of the previous chunk into the next chunk. This ensures that thoughts, sentences, or concepts that span across boundaries remain complete in at least one chunk...",
  "sources": [
    {
      "rank": 1,
      "sourceName": "spring_ai_architecture.md",
      "chunkIndex": 4,
      "similarityScore": 0.812,
      "contentSnippet": "Without overlap, critical sentences or contextual links that happen to fall across chunk boundaries are severed..."
    }
  ],
  "retrievedSourcesCount": 3,
  "modelUsed": "qwen2.5:1.5b",
  "elapsedTimeMs": 3210
}
```

---

### 6. Pipeline Status
Returns current vector store statistics and active model configurations.

- **URL**: `GET /api/rag/status`

```bash
curl http://localhost:8080/api/rag/status
```

**Response (`200 OK`)**:
```json
{
  "totalChunks": 11,
  "ingestedDocuments": ["spring_ai_architecture.md"],
  "vectorStorePath": "data/vectors/vector_store.json",
  "fileSizeBytes": 123803,
  "embeddingModel": "nomic-embed-text:latest (Ollama)",
  "chatModel": "qwen2.5:1.5b / llama3.2:latest (Ollama)"
}
```

---

### 7. Reset Vector Store
Clears the vector store from memory, deletes the persisted `vector_store.json` file, and clears uploaded files.

- **URL**: `DELETE /api/rag/reset`

```bash
curl -X DELETE http://localhost:8080/api/rag/reset
```

---

## ⚙️ Configuration Reference

All settings can be customized in [`src/main/resources/application.yml`](file:///Users/rajesh/Desktop/ragpipeline/src/main/resources/application.yml):

```yaml
server:
  port: 8080

spring:
  application:
    name: ragpipeline
  servlet:
    multipart:
      max-file-size: 50MB
      max-request-size: 50MB
  ai:
    ollama:
      base-url: http://localhost:11434
      chat:
        enabled: true
        options:
          model: qwen2.5:1.5b          # LLM for synthesis (e.g., qwen2.5:1.5b, llama3.2)
          temperature: 0.3             # Lower temperature for grounded factual answers
      embedding:
        enabled: true
        options:
          model: nomic-embed-text:latest # 768-dim embedding model

rag:
  chunking:
    default-chunk-size: 500            # Default chunk character count
    default-chunk-overlap: 100         # Default character overlap
    min-chunk-size-chars: 50           # Minimum chunk threshold
  retrieval:
    default-top-k: 4                   # Top K nearest neighbor chunks
    default-similarity-threshold: 0.55  # Minimum cosine similarity cutoff
  storage:
    upload-dir: data/uploads           # Local disk upload destination
    vector-store-file: data/vectors/vector_store.json # Persisted vector database file
```

---

## 💡 Engineering & Troubleshooting Notes

### 1. Java 25 & ASM Repackaging
- **Issue**: Standard `spring-boot-maven-plugin:3.4.0` repackaging may throw `Unsupported class file major version 69` on Java 25 bytecode.
- **Solution**: The plugin is configured with `spring-core:6.2.10` in its plugin dependencies, enabling ASM support for bytecode version 69.

### 2. Spring MVC vs. WebFlux Netty Event Loops
- **Issue**: When using WebFlux with synchronous Spring AI `RestClient` calls, calling `embed()` on Netty's event loop throws `block()/blockFirst()/blockLast() are blocking, which is not supported in thread reactor-http-nio`.
- **Solution**: The application uses Spring MVC (`spring-boot-starter-web`) running on Tomcat worker threads, which naturally support blocking I/O and standard `MultipartFile` handling.

### 3. JdkClientHttpRequestFactory & Long LLM Timeouts
- **Issue**: Complex LLM completions or large embedding batches can take several seconds to minutes, triggering default HTTP socket read timeouts.
- **Solution**: Configured [`RestClientCustomizer`](file:///Users/rajesh/Desktop/ragpipeline/src/main/java/com/example/ragpipeline/config/ChatClientConfig.java) with `JdkClientHttpRequestFactory` and an explicit 5-minute read timeout.

### 4. Optional PgVector Support
To use PostgreSQL with PgVector instead of `SimpleVectorStore`, launch the bundled `docker-compose.yml`:
```bash
docker compose up -d
```
The database will be available at `localhost:5432` (`ragdb` / `raguser` / `ragpassword`).

---

## 🧪 Testing

Execute the test suite (validates the sliding-window text chunker and boundary preservation):

```bash
export JAVA_HOME="/Users/rajesh/Library/Java/JavaVirtualMachines/loom-ea-25-loom+1-11/Contents/Home"
./mvnw test
```

Expected result:
```
[INFO] Running com.example.ragpipeline.OverlappingTextSplitterTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```
