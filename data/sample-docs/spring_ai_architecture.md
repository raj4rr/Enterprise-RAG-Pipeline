# Enterprise RAG Architecture with Spring AI and Java 25

## 1. Overview
Retrieval-Augmented Generation (RAG) is an architectural pattern that bridges external knowledge bases with Large Language Models (LLMs). By extracting relevant data chunks from local documents, embedding them into a high-dimensional vector space, and performing similarity searches, RAG empowers LLMs to produce accurate, context-rich answers without retraining or fine-tuning the model.

## 2. Document Ingestion and Apache Tika
The first phase of the RAG pipeline is document ingestion. Rather than writing custom parsers for every format, Spring AI leverages Apache Tika. Tika automatically detects MIME types and extracts structured text and metadata from diverse formats, including:
- Portable Document Format (.pdf)
- Microsoft Office Documents (.docx, .pptx, .xlsx)
- Plain Text and Markdown (.txt, .md)
- Web documents and structured feeds (.html, .xml, .json, .csv)

## 3. Intelligent Chunking with Sliding Window Overlap
Embedding entire documents at once degrades retrieval precision because vector embeddings lose specificity when averaged over thousands of words. Therefore, documents must be split into smaller, coherent chunks.
Key chunking parameters include:
- **Chunk Size**: The maximum number of characters or tokens per chunk (typically 500 to 1000 characters).
- **Chunk Overlap**: The number of characters repeated between consecutive chunks (typically 100 to 200 characters).

### Why Overlap Matters
Without overlap, critical sentences or contextual links that happen to fall across chunk boundaries are severed. Overlapping ensures that context is continuous across chunk transitions, ensuring the embedding model captures the complete semantics of border thoughts.

## 4. Vector Embeddings and Ollama
Text chunks are passed to an embedding model that converts natural language into numeric vector arrays (dense mathematical representations). In this pipeline, the local model `nomic-embed-text` is utilized via Ollama:
- Produces 768-dimensional normalized vector embeddings.
- Operates entirely locally with zero cloud latency and total privacy.
- Captures semantic intent rather than simple keyword matching.

## 5. Vector Database and Persistence
Spring AI provides the `VectorStore` abstraction. The default implementation in this pipeline is `SimpleVectorStore`:
- Maintains an in-memory vector index for sub-millisecond similarity lookups.
- Employs cosine distance / cosine similarity calculations.
- Supports automatic serialization to a persistent JSON file (`data/vectors/vector_store.json`), allowing vectors to persist across application restarts.

## 6. The RAG Query Cycle
When a user asks a question in the chatbot:
1. **Query Embedding**: The user query is converted into an embedding vector using `nomic-embed-text`.
2. **Similarity Search**: The vector store computes cosine similarity between the query vector and all stored chunk vectors, retrieving the top `K` most relevant chunks above a threshold.
3. **Context Augmentation**: The retrieved chunks and their source citations are formatted into an augmented system prompt.
4. **LLM Generation**: The prompt is submitted to the local LLM (`qwen2.5:1.5b` or `llama3.2`), which synthesizes the answer strictly grounded in the retrieved facts.
5. **Response Delivery**: The chatbot responds with the answer and clickable references to source chunks.
