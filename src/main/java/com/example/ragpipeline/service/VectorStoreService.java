package com.example.ragpipeline.service;

import com.example.ragpipeline.config.RagProperties;
import com.example.ragpipeline.dto.VectorStoreStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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

    public VectorStoreService(SimpleVectorStore vectorStore, RagProperties ragProperties) {
        this.vectorStore = vectorStore;
        this.ragProperties = ragProperties;
        this.vectorStoreFile = new File(ragProperties.storage().vectorStoreFile()).getAbsoluteFile();
        initStoreMetadata();
    }

    private void initStoreMetadata() {
        if (vectorStoreFile.exists() && vectorStoreFile.length() > 0) {
            log.info("Vector store file exists at {}. Size: {} bytes", vectorStoreFile.getPath(), vectorStoreFile.length());
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
            Object src = chunk.getMetadata().get("source");
            if (src != null) {
                ingestedSources.add(src.toString());
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
