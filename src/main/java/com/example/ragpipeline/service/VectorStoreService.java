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
    private final File vectorStoreFile;
    private final Set<String> ingestedSources = ConcurrentHashMap.newKeySet();
    private final AtomicInteger storedChunkCount = new AtomicInteger(0);

    // Track chunk IDs and metadata per document source
    private final Map<String, Set<String>> sourceToChunkIds = new ConcurrentHashMap<>();
    private final Map<String, String> sourceUploadedAt = new ConcurrentHashMap<>();
    private final Map<String, String> sourceContentType = new ConcurrentHashMap<>();

    public VectorStoreService(SimpleVectorStore vectorStore, RagProperties ragProperties) {
        this.vectorStore = vectorStore;
        this.ragProperties = ragProperties;
        this.vectorStoreFile = new File(ragProperties.storage().vectorStoreFile()).getAbsoluteFile();
        initStoreMetadata();
    }

    /**
     * Initializes chunk statistics and source mappings from the persisted JSON index.
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
            Iterator<Map.Entry<String, JsonNode>> fields = root.fields();

            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String chunkId = entry.getKey();
                JsonNode docNode = entry.getValue();
                JsonNode meta = docNode.get("metadata");

                if (meta != null && meta.has("source")) {
                    String source = meta.get("source").asText();
                    sourceToChunkIds.computeIfAbsent(source, k -> ConcurrentHashMap.newKeySet()).add(chunkId);
                    ingestedSources.add(source);

                    if (meta.has("uploaded_at")) {
                        sourceUploadedAt.putIfAbsent(source, meta.get("uploaded_at").asText());
                    }
                    if (meta.has("content_type")) {
                        sourceContentType.putIfAbsent(source, meta.get("content_type").asText());
                    }
                }
                totalChunks++;
            }

            storedChunkCount.set(totalChunks);
            log.info("Restored vector store metadata from file: {} chunks across {} sources (File size: {} bytes)",
                    totalChunks, ingestedSources.size(), vectorStoreFile.length());

        } catch (Exception e) {
            log.warn("Could not parse metadata from vector store file {}: {}. Starting with empty tracking.",
                    vectorStoreFile.getPath(), e.getMessage());
        }
    }

    /**
     * Embeds and persists document chunks into the vector store.
     */
    public synchronized void addDocuments(List<Document> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }

        log.info("Embedding and storing {} chunks into VectorStore...", chunks.size());
        long startTime = System.currentTimeMillis();

        // Add to vector store (Spring AI automatically embeds using EmbeddingModel)
        vectorStore.accept(chunks);

        // Update statistics and tracked sources
        storedChunkCount.addAndGet(chunks.size());
        for (Document chunk : chunks) {
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
        log.info("Successfully embedded {} chunks in {} ms", chunks.size(), duration);

        // Persist vector store to file
        saveToFile();
    }

    /**
     * Executes similarity search with top-K and similarity threshold.
     */
    public List<Document> similaritySearch(String query, int topK, double threshold) {
        int safeTopK = (topK > 0) ? topK : ragProperties.retrieval().defaultTopK();
        double safeThreshold = (threshold >= 0.0) ? threshold : ragProperties.retrieval().defaultSimilarityThreshold();

        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(safeTopK)
                .similarityThreshold(safeThreshold)
                .build();

        log.info("Performing vector search for query: '{}', topK: {}, threshold: {}", query, safeTopK, safeThreshold);
        return vectorStore.similaritySearch(request);
    }

    /**
     * Deletes all vector chunks associated with the specified document source name.
     *
     * @param sourceName Document source name or filename
     * @return Number of chunks removed
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

                log.info("Deleted {} chunks for document '{}' from vector store", deletedCount, sourceName);
                saveToFile();
            } catch (Exception e) {
                log.error("Failed to delete chunks for source '{}': {}", sourceName, e.getMessage(), e);
            }
        } else {
            // In case chunk IDs were not cached in memory, also remove from source set
            ingestedSources.remove(sourceName);
            sourceUploadedAt.remove(sourceName);
            sourceContentType.remove(sourceName);
        }

        return deletedCount;
    }

    /**
     * Lists detailed information about all ingested documents.
     */
    public List<DocumentInfo> listDocuments(FileStorageService fileStorageService) {
        List<DocumentInfo> docs = new ArrayList<>();
        Set<String> allSources = new TreeSet<>(ingestedSources);

        // Also check if any stored files on disk haven't been tracked yet
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
        log.warn("Resetting vector store and removing persisted storage file.");
        ingestedSources.clear();
        sourceToChunkIds.clear();
        sourceUploadedAt.clear();
        sourceContentType.clear();
        storedChunkCount.set(0);

        if (vectorStoreFile.exists()) {
            boolean deleted = vectorStoreFile.delete();
            log.info("Deleted vector store file: {}", deleted);
        }
    }

    /**
     * Returns stats about the vector store.
     */
    public VectorStoreStats getStats(String embeddingModel, String chatModel) {
        return new VectorStoreStats(
                storedChunkCount.get(),
                new ArrayList<>(ingestedSources),
                vectorStoreFile.getAbsolutePath(),
                embeddingModel,
                chatModel,
                vectorStoreFile.exists() && vectorStoreFile.length() > 0
        );
    }
}
