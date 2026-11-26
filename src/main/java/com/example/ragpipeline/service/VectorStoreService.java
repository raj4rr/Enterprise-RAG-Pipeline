package com.example.ragpipeline.service;

import com.example.ragpipeline.config.RagProperties;
import com.example.ragpipeline.dto.DocumentInfo;
import com.example.ragpipeline.dto.VectorStoreStats;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class VectorStoreService {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreService.class);

    private final SimpleVectorStore vectorStore;
    private final RagProperties ragProperties;
    private final HybridSearchService hybridSearchService;
    private final EmbeddingModelService embeddingModelService;
    private final File vectorStoreFile;

    private final Set<String> ingestedSources = ConcurrentHashMap.newKeySet();
    private final AtomicInteger storedChunkCount = new AtomicInteger(0);

    // Track chunk IDs and metadata per document source
    private final Map<String, Set<String>> sourceToChunkIds = new ConcurrentHashMap<>();
    private final Map<String, String> sourceUploadedAt = new ConcurrentHashMap<>();
    private final Map<String, String> sourceContentType = new ConcurrentHashMap<>();

    public VectorStoreService(SimpleVectorStore vectorStore,
                              RagProperties ragProperties,
                              HybridSearchService hybridSearchService,
                              EmbeddingModelService embeddingModelService) {
        this.vectorStore = vectorStore;
        this.ragProperties = ragProperties;
        this.hybridSearchService = hybridSearchService;
        this.embeddingModelService = embeddingModelService;
        this.vectorStoreFile = new File(ragProperties.storage().vectorStoreFile()).getAbsoluteFile();
        initStoreMetadata();
    }

    // Backward-compatible constructor for tests
    public VectorStoreService(SimpleVectorStore vectorStore, RagProperties ragProperties) {
        this(vectorStore, ragProperties, new HybridSearchService(ragProperties), new EmbeddingModelService(ragProperties));
    }

    /**
     * Initializes chunk statistics, source mappings, and BM25 index from persisted JSON index.
     */
    private void initStoreMetadata() {
        if (!vectorStoreFile.exists() || vectorStoreFile.length() == 0) {
            log.info("No existing vector store index found at {}", vectorStoreFile.getPath());
            return;
        }

        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(vectorStoreFile);
            int totalChunks = 0;
            List<Document> restoredDocs = new ArrayList<>();
            Iterator<Map.Entry<String, JsonNode>> fields = root.fields();

            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String chunkId = entry.getKey();
                JsonNode docNode = entry.getValue();

                String text = "";
                if (docNode.has("text")) {
                    text = docNode.get("text").asText();
                } else if (docNode.has("content")) {
                    text = docNode.get("content").asText();
                }

                JsonNode metaNode = docNode.get("metadata");
                Map<String, Object> metadata = new HashMap<>();

                if (metaNode != null && metaNode.isObject()) {
                    Iterator<Map.Entry<String, JsonNode>> metaFields = metaNode.fields();
                    while (metaFields.hasNext()) {
                        Map.Entry<String, JsonNode> mEntry = metaFields.next();
                        JsonNode val = mEntry.getValue();
                        if (val.isNumber()) {
                            metadata.put(mEntry.getKey(), val.numberValue());
                        } else if (val.isBoolean()) {
                            metadata.put(mEntry.getKey(), val.booleanValue());
                        } else {
                            metadata.put(mEntry.getKey(), val.asText());
                        }
                    }

                    if (metadata.containsKey("source")) {
                        String source = String.valueOf(metadata.get("source"));
                        sourceToChunkIds.computeIfAbsent(source, k -> ConcurrentHashMap.newKeySet()).add(chunkId);
                        ingestedSources.add(source);

                        if (metadata.containsKey("uploaded_at")) {
                            sourceUploadedAt.putIfAbsent(source, String.valueOf(metadata.get("uploaded_at")));
                        }
                        if (metadata.containsKey("content_type")) {
                            sourceContentType.putIfAbsent(source, String.valueOf(metadata.get("content_type")));
                        }
                    }
                }

                Document restoredDoc = new Document(chunkId, text, metadata);
                restoredDocs.add(restoredDoc);
                totalChunks++;
            }

            storedChunkCount.set(totalChunks);

            // Re-populate BM25 sparse index
            if (hybridSearchService != null && !restoredDocs.isEmpty()) {
                hybridSearchService.indexChunks(restoredDocs);
            }

            log.info("Restored vector store metadata & BM25 index: {} chunks across {} sources (File size: {} bytes)",
                    totalChunks, ingestedSources.size(), vectorStoreFile.length());

        } catch (Exception e) {
            log.warn("Could not parse metadata from vector store file {}: {}. Starting with empty tracking.",
                    vectorStoreFile.getPath(), e.getMessage());
        }
    }

    /**
     * Embeds and persists document chunks into the vector store and BM25 hybrid index.
     */
    public synchronized void addDocuments(List<Document> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }

        // Prepare chunks with asymmetric instruction prefixes and clean text preservation
        List<Document> preparedChunks = (embeddingModelService != null)
                ? embeddingModelService.prepareChunksForIngestion(chunks)
                : chunks;

        log.info("Embedding and storing {} chunks into VectorStore and BM25 index...", preparedChunks.size());
        long startTime = System.currentTimeMillis();

        // 1. Ingest into Vector Store
        vectorStore.accept(preparedChunks);

        // 2. Ingest into BM25 Sparse Index
        if (hybridSearchService != null) {
            hybridSearchService.indexChunks(preparedChunks);
        }

        // Update statistics and tracked sources
        storedChunkCount.addAndGet(preparedChunks.size());
        for (Document chunk : preparedChunks) {
            String chunkId = chunk.getId();
            Map<String, Object> meta = chunk.getMetadata();
            Object src = meta.get("source");

            if (src != null) {
                String sourceName = src.toString();
                ingestedSources.add(sourceName);

                if (chunkId != null) {
                    sourceToChunkIds.computeIfAbsent(sourceName, k -> ConcurrentHashMap.newKeySet()).add(chunkId);
                }
                if (meta.containsKey("uploaded_at")) {
                    sourceUploadedAt.put(sourceName, meta.get("uploaded_at").toString());
                }
                if (meta.containsKey("content_type")) {
                    sourceContentType.put(sourceName, meta.get("content_type").toString());
                }
            }
        }

        long duration = System.currentTimeMillis() - startTime;
        log.info("Successfully embedded & indexed {} chunks in {} ms", preparedChunks.size(), duration);

        // Persist vector store to file
        saveToFile();
    }

    /**
     * Executes hybrid search combining dense similarity search and BM25 sparse search with metadata filtering.
     */
    public List<HybridSearchService.ScoredDocument> hybridSearch(String query, int topK, double threshold, Map<String, Object> metadataFilter) {
        int safeTopK = (topK > 0) ? topK : ragProperties.retrieval().defaultTopK();
        double safeThreshold = (threshold >= 0.0) ? threshold : ragProperties.retrieval().defaultSimilarityThreshold();

        // Format query with asymmetric instruction prefix for dense search if appropriate
        String queryForDense = (embeddingModelService != null)
                ? embeddingModelService.prepareQueryForEmbedding(query)
                : query;

        SearchRequest request = SearchRequest.builder()
                .query(queryForDense)
                .topK(Math.max(safeTopK * 2, 8))
                .similarityThreshold(Math.max(0.20, safeThreshold - 0.20)) // Fetch candidate pool for fusion
                .build();

        log.info("Performing dense vector search (candidate pool size: {}) for query: '{}'", request.getTopK(), query);
        List<Document> denseDocs;
        try {
            denseDocs = vectorStore.similaritySearch(request);
        } catch (Exception e) {
            log.warn("Dense similarity search encountered an error: {}. Falling back to empty dense list.", e.getMessage());
            denseDocs = List.of();
        }

        // Clean document text (strip prefixes if any) before fusion
        List<Document> cleanDenseDocs = new ArrayList<>();
        for (Document doc : denseDocs) {
            String cleanText = (embeddingModelService != null) ? embeddingModelService.extractCleanText(doc) : doc.getText();
            cleanDenseDocs.add(new Document(doc.getId(), cleanText, doc.getMetadata()));
        }

        // Fuse Dense + Sparse (BM25) with Metadata Filtering
        if (hybridSearchService != null) {
            return hybridSearchService.fuseResults(cleanDenseDocs, query, safeTopK, safeThreshold, metadataFilter);
        }

        // Fallback to dense only
        return cleanDenseDocs.stream()
                .map(d -> new HybridSearchService.ScoredDocument(d, safeThreshold, "DENSE"))
                .limit(safeTopK)
                .toList();
    }

    /**
     * Standard vector similarity search for backward compatibility.
     */
    public List<Document> similaritySearch(String query, int topK, double threshold) {
        var scored = hybridSearch(query, topK, threshold, null);
        return scored.stream().map(HybridSearchService.ScoredDocument::document).toList();
    }

    /**
     * Deletes all vector chunks associated with the specified document source name.
     */
    public synchronized int deleteDocument(String sourceName) {
        if (sourceName == null || sourceName.isBlank()) {
            return 0;
        }

        Set<String> chunkIds = sourceToChunkIds.remove(sourceName);
        int deletedCount = 0;

        if (chunkIds != null && !chunkIds.isEmpty()) {
            try {
                vectorStore.delete(new ArrayList<>(chunkIds));
                deletedCount = chunkIds.size();
                storedChunkCount.addAndGet(-deletedCount);
                if (storedChunkCount.get() < 0) {
                    storedChunkCount.set(0);
                }
                ingestedSources.remove(sourceName);
                sourceUploadedAt.remove(sourceName);
                sourceContentType.remove(sourceName);

                if (hybridSearchService != null) {
                    hybridSearchService.removeSource(sourceName);
                }

                log.info("Deleted {} chunks for document '{}' from vector store & BM25 index", deletedCount, sourceName);
                saveToFile();
            } catch (Exception e) {
                log.error("Failed to delete chunks for source '{}': {}", sourceName, e.getMessage(), e);
            }
        } else {
            ingestedSources.remove(sourceName);
            sourceUploadedAt.remove(sourceName);
            sourceContentType.remove(sourceName);
            if (hybridSearchService != null) {
                hybridSearchService.removeSource(sourceName);
            }
        }

        return deletedCount;
    }

    /**
     * Lists detailed information about all ingested documents.
     */
    public List<DocumentInfo> listDocuments(FileStorageService fileStorageService) {
        List<DocumentInfo> docs = new ArrayList<>();
        Set<String> allSources = new TreeSet<>(ingestedSources);

        if (fileStorageService != null) {
            allSources.addAll(fileStorageService.listStoredFiles());
        }

        for (String source : allSources) {
            int chunkCount = sourceToChunkIds.getOrDefault(source, Collections.emptySet()).size();
            long fileSize = (fileStorageService != null) ? fileStorageService.getFileSize(source) : 0L;
            String uploadedAt = sourceUploadedAt.getOrDefault(source, "Previously Ingested");
            String contentType = sourceContentType.getOrDefault(source, "application/octet-stream");

            docs.add(new DocumentInfo(source, chunkCount, fileSize, uploadedAt, contentType));
        }

        docs.sort(Comparator.comparing(DocumentInfo::filename, String.CASE_INSENSITIVE_ORDER));
        return docs;
    }

    /**
     * Persists the current state of the vector store to disk.
     */
    public synchronized void saveToFile() {
        try {
            if (vectorStoreFile.getParentFile() != null && !vectorStoreFile.getParentFile().exists()) {
                vectorStoreFile.getParentFile().mkdirs();
            }
            vectorStore.save(vectorStoreFile);
            log.info("Vector store persisted successfully to {}", vectorStoreFile.getAbsolutePath());
        } catch (Exception e) {
            log.error("Failed to persist vector store to {}: {}", vectorStoreFile.getAbsolutePath(), e.getMessage(), e);
        }
    }

    /**
     * Clears all entries from the vector store and deletes the disk storage file.
     */
    public synchronized void reset() {
        log.warn("Resetting vector store and BM25 index and removing persisted storage file.");
        ingestedSources.clear();
        sourceToChunkIds.clear();
        sourceUploadedAt.clear();
        sourceContentType.clear();
        storedChunkCount.set(0);

        if (hybridSearchService != null) {
            hybridSearchService.clear();
        }

        if (vectorStoreFile.exists()) {
            boolean deleted = vectorStoreFile.delete();
            log.info("Deleted vector store file: {}", deleted);
        }
    }

    /**
     * Returns stats about the vector store.
     */
    public VectorStoreStats getStats(String embeddingModel, String chatModel) {
        String activeModel = (embeddingModelService != null)
                ? embeddingModelService.getActiveProfile().modelName()
                : embeddingModel;

        return new VectorStoreStats(
                storedChunkCount.get(),
                new ArrayList<>(ingestedSources),
                vectorStoreFile.getAbsolutePath(),
                activeModel,
                chatModel,
                vectorStoreFile.exists() && vectorStoreFile.length() > 0
        );
    }
}
