# ⚡ Enterprise Spring AI RAG Pipeline (Java 25)

An end-to-end, production-ready **Retrieval-Augmented Generation (RAG)** pipeline built with **Spring AI**, **Java 25**, **Apache Tika**, **Ollama**, and **Spring VectorStore**.

This application handles the entire lifecycle:
1. **Document Loading & Saving**: Ingests files (`.pdf`, `.docx`, `.txt`, `.md`, `.html`, `.csv`, `.json`) or raw text and securely persists them to disk (`data/uploads/`).
2. **Intelligent Chunking with Overlap**: Splits documents into configurable semantic chunks while preserving word and sentence boundaries to avoid severed context.
3. **Local Vector Embeddings**: Embeds chunks using high-performance local embeddings (`nomic-embed-text` via Ollama) with zero cloud dependencies.
4. **Persistent Vector Database**: Stores vectors in `SimpleVectorStore` with automated file persistence (`data/vectors/vector_store.json`), surviving application restarts.
5. **RAG Chatbot Workflow**: Takes user queries, embeds them, retrieves top-K relevant chunks via cosine similarity search, formats an augmented prompt, and queries the LLM (`qwen2.5:1.5b` / `llama3.2`) to return grounded answers with verifiable source citations.
6. **Modern Web UI Dashboard**: A sleek dark-mode web application served directly on `http://localhost:8080`.

---

## 🏛️ Architecture & Pipeline Flow

```
+-----------------------------------------------------------------------------------+
|                                 1. INGESTION PHASE                                |
|                                                                                   |
|  [User Upload / API]                                                              |
|        |                                                                          |
|        v                                                                          |
|  [FileStorageService] ──────> Saves to data/uploads/                              |
|        |                                                                          |
|        v                                                                          |
|  [Apache Tika Reader] ──────> Extracts structured text & metadata                 |
|        |                                                                          |
|        v                                                                          |
|  [OverlappingTextSplitter] ──> Chunks text (chunkSize=500, chunkOverlap=100)      |
|        |                                                                          |
|        v                                                                          |
|  [EmbeddingModel] ───────────> Generates dense vector representations (768-dim)   |
|        |                                                                          |
|        v                                                                          |
|  [VectorStoreService] ───────> Saves into SimpleVectorStore + vector_store.json   |
+-----------------------------------------------------------------------------------+
                                        │
                                        ▼
+-----------------------------------------------------------------------------------+
|                                2. RAG CHAT PHASE                                  |
|                                                                                   |
|  [User Query: "Why is chunk overlap important?"]                                 |
|        |                                                                          |
|        v                                                                          |
|  [Query Embedding] ──────────> nomic-embed-text generates query vector            |
|        |                                                                          |
|        v                                                                          |
|  [Vector Similarity Search] ─> Cosine similarity lookup (Top-K=4, Threshold=0.55) |
|        |                                                                          |
|        v                                                                          |
|  [Prompt Augmentation] ──────> Context injection:                                 |
|                                "Answer based on: [Source 1, Source 2]..."         |
|        |                                                                          |
|        v                                                                          |
|  [ChatModel / ChatClient] ───> qwen2.5:1.5b synthesizes grounded answer           |
|        |                                                                          |
|        v                                                                          |
|  [Response Delivery] ────────> Answer + Source Citations + Similarity Scores     |
+-----------------------------------------------------------------------------------+
```

---

## 🛠️ Tech Stack & Requirements

- **Language**: **Java 25** (`OpenJDK 25-loom`)
- **Framework**: **Spring Boot 3.4.0**
- **AI Library**: **Spring AI 1.0.0-M6** (`spring-ai-bom`)
- **Embeddings**: `nomic-embed-text:latest` via **Ollama**
- **LLM**: `qwen2.5:1.5b` or `llama3.2:latest` via **Ollama**
- **Parsers**: `spring-ai-tika-document-reader`, `spring-ai-pdf-document-reader`
- **Vector DB**: `SimpleVectorStore` with JSON persistence (`data/vectors/vector_store.json`) (Optional PgVector via `docker-compose.yml`)
- **Frontend**: Vanilla HTML5, Modern CSS Glassmorphism, Responsive JS

---

## 🚀 Quick Start

### 1. Prerequisites
Ensure Ollama is running locally with the embedding model and chat model:
```bash
# Check running models
curl http://localhost:11434/api/tags

# If needed, pull the models
ollama pull nomic-embed-text
ollama pull qwen2.5:1.5b
```

### 2. Run the Application
Launch the preconfigured startup script (sets Java 25 environment automatically):
```bash
cd /Users/rajesh/Desktop/ragpipeline
./run.sh
```

Or run via Maven wrapper:
```bash
export JAVA_HOME="/Users/rajesh/Library/Java/JavaVirtualMachines/loom-ea-25-loom+1-11/Contents/Home"
./mvnw spring-boot:run
```

### 3. Open the Web Dashboard
Navigate to:
```
http://localhost:8080
```

---

## 💻 REST API Endpoints

### 1. Ingest File with Overlapping Chunks
```bash
curl -X POST http://localhost:8080/api/rag/upload \
  -F "file=@data/sample-docs/spring_ai_architecture.md" \
  -F "chunkSize=500" \
  -F "chunkOverlap=100"
```

**Response:**
```json
{
  "success": true,
  "documentName": "spring_ai_architecture.md",
  "totalChunks": 11,
  "totalCharacters": 4210,
  "chunkSize": 500,
  "chunkOverlap": 100,
  "message": "Successfully parsed, split into 11 chunks (overlap: 100), embedded and saved to vector store.",
  "elapsedTimeMs": 2842
}
```

### 2. Ingest Raw Text
```bash
curl -X POST http://localhost:8080/api/rag/ingest-text \
  -H "Content-Type: application/json" \
  -d '{
    "title": "Company Policy 2026",
    "content": "All employees are entitled to 25 days of annual leave...",
    "chunkSize": 400,
    "chunkOverlap": 80
  }'
```

### 3. Preview Chunks (Without Persisting)
```bash
curl -X POST http://localhost:8080/api/rag/preview-chunks \
  -H "Content-Type: application/json" \
  -d '{
    "text": "Your long text to preview chunk boundaries...",
    "chunkSize": 300,
    "chunkOverlap": 50
  }'
```

### 4. Vector Similarity Search Only
```bash
curl -X POST "http://localhost:8080/api/rag/search?query=Why%20does%20chunk%20overlap%20matter?&topK=3&threshold=0.5"
```

### 5. Full RAG Chat Query
```bash
curl -X POST http://localhost:8080/api/rag/chat \
  -H "Content-Type: application/json" \
  -d '{
    "query": "Why is chunk overlap important when creating chunks for RAG?",
    "topK": 4,
    "similarityThreshold": 0.45
  }'
```

**Response:**
```json
{
  "query": "Why is chunk overlap important when creating chunks for RAG?",
  "answer": "Chunk overlap is important when creating chunks for RAG because it helps maintain continuity and context across chunk boundaries...",
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
  "elapsedTimeMs": 4874
}
```

### 6. Pipeline Status
```bash
curl http://localhost:8080/api/rag/status
```

### 7. Reset Vector Store
```bash
curl -X DELETE http://localhost:8080/api/rag/reset
```

---

## 📁 Project Structure

```
ragpipeline/
├── pom.xml                                   # Spring Boot 3.4.0, Spring AI 1.0.0-M6, Java 25
├── run.sh                                    # Quick start script with Java 25 environment
├── docker-compose.yml                        # Optional PostgreSQL with PgVector
├── data/
│   ├── uploads/                              # Persisted uploaded documents
│   ├── vectors/
│   │   └── vector_store.json                 # Persisted vector database index
│   └── sample-docs/
│       └── spring_ai_architecture.md         # Ready-to-use sample document
└── src/
    ├── main/
    │   ├── java/com/example/ragpipeline/
    │   │   ├── RagPipelineApplication.java   # Spring Boot Entry point
    │   │   ├── config/
    │   │   │   ├── RagProperties.java        # @ConfigurationProperties record
    │   │   │   ├── VectorStoreConfig.java    # SimpleVectorStore bean with disk loading
    │   │   │   └── ChatClientConfig.java     # ChatClient bean for LLM invocation
    │   │   ├── controller/
    │   │   │   ├── RagIngestionController.java # File upload, chunking, preview, reset
    │   │   │   └── RagChatController.java      # Chat & similarity search APIs
    │   │   ├── dto/                          # Clean Java 25 records (ChatRequest, IngestResponse, etc.)
    │   │   ├── service/
    │   │   │   ├── FileStorageService.java   # Apache Tika parser & upload disk storage
    │   │   │   ├── DocumentChunkingService.java # Chunking coordinator
    │   │   │   ├── VectorStoreService.java   # Vector store & similarity search manager
    │   │   │   └── RagChatService.java       # Full RAG query -> search -> LLM prompt cycle
    │   │   └── splitter/
    │   │       └── OverlappingTextSplitter.java # Intelligent sliding-window chunker
    │   └── resources/
    │       ├── application.yml               # Ollama models, thresholds, storage paths
    │       └── static/                       # Modern Glassmorphic Web UI
    │           ├── index.html
    │           ├── css/style.css
    │           └── js/app.js
    └── test/
        └── java/com/example/ragpipeline/
            └── OverlappingTextSplitterTest.java # Unit tests for chunk size & overlap
```

---

## 🧪 Testing

Run all unit tests:
```bash
export JAVA_HOME="/Users/rajesh/Library/Java/JavaVirtualMachines/loom-ea-25-loom+1-11/Contents/Home"
./mvnw test
```
