package com.example.ragpipeline.service;

import com.example.ragpipeline.config.RagProperties;
import com.example.ragpipeline.dto.ChunkInfo;
import com.example.ragpipeline.dto.ChunkPreviewResponse;
import com.example.ragpipeline.splitter.ChunkingStrategy;
import com.example.ragpipeline.splitter.OverlappingTextSplitter;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class DocumentChunkingService {

    private final OverlappingTextSplitter textSplitter;
    private final DocumentQualityService documentQualityService;
    private final RagProperties ragProperties;

    public DocumentChunkingService(OverlappingTextSplitter textSplitter,
                                  DocumentQualityService documentQualityService,
                                  RagProperties ragProperties) {
        this.textSplitter = textSplitter;
        this.documentQualityService = documentQualityService;
        this.ragProperties = ragProperties;
    }

    /**
     * Splits a list of documents into optimized overlapping chunks with quality cleansing.
     */
    public List<Document> chunkDocuments(List<Document> documents, Integer requestedChunkSize, Integer requestedChunkOverlap) {
        return chunkDocuments(documents, requestedChunkSize, requestedChunkOverlap, null);
    }

    /**
     * Splits a list of documents with specified strategy, size, overlap, and cleans document text.
     */
    public List<Document> chunkDocuments(List<Document> documents, Integer requestedChunkSize, Integer requestedChunkOverlap, String requestedStrategy) {
        int chunkSize = resolveChunkSize(requestedChunkSize);
        int chunkOverlap = resolveChunkOverlap(requestedChunkOverlap, chunkSize);
        ChunkingStrategy strategy = (requestedStrategy != null && !requestedStrategy.isBlank())
                ? ChunkingStrategy.fromString(requestedStrategy)
                : ChunkingStrategy.fromString(ragProperties.chunking().strategy());

        List<Document> allChunks = new ArrayList<>();
        int runningIndex = 0;

        for (Document doc : documents) {
            // 1. Clean and enrich document quality first
            Document cleanDoc = documentQualityService.cleanAndEnrichDocument(doc);

            // 2. Split with optimized strategy
            List<Document> docChunks = textSplitter.splitDocument(cleanDoc, chunkSize, chunkOverlap, strategy);
            for (Document chunk : docChunks) {
                chunk.getMetadata().put("global_chunk_index", runningIndex++);
                allChunks.add(chunk);
            }
        }

        return allChunks;
    }

    /**
     * Generates a preview of chunks without persisting to the vector database.
     */
    public ChunkPreviewResponse previewChunks(String text, Integer requestedChunkSize, Integer requestedChunkOverlap) {
        return previewChunks(text, requestedChunkSize, requestedChunkOverlap, null);
    }

    public ChunkPreviewResponse previewChunks(String text, Integer requestedChunkSize, Integer requestedChunkOverlap, String requestedStrategy) {
        if (text == null || text.isBlank()) {
            return new ChunkPreviewResponse(0, 0, 0, 0, List.of());
        }

        int chunkSize = resolveChunkSize(requestedChunkSize);
        int chunkOverlap = resolveChunkOverlap(requestedChunkOverlap, chunkSize);
        ChunkingStrategy strategy = (requestedStrategy != null && !requestedStrategy.isBlank())
                ? ChunkingStrategy.fromString(requestedStrategy)
                : ChunkingStrategy.fromString(ragProperties.chunking().strategy());

        var qualityReport = documentQualityService.inspectAndClean(text, "preview");
        String textToChunk = qualityReport.cleanedText();

        List<String> rawChunks = textSplitter.splitText(textToChunk, chunkSize, chunkOverlap, strategy);
        List<ChunkInfo> chunkInfos = new ArrayList<>();

        for (int i = 0; i < rawChunks.size(); i++) {
            String content = rawChunks.get(i);
            int overlap = (i == 0) ? 0 : chunkOverlap;
            int words = content.split("\\s+").length;

            chunkInfos.add(new ChunkInfo(
                    i,
                    content,
                    content.length(),
                    words,
                    overlap,
                    Map.of(
                            "chunkSize", chunkSize,
                            "chunkOverlap", chunkOverlap,
                            "strategy", strategy.name(),
                            "qualityScore", qualityReport.qualityScore(),
                            "qualityStatus", qualityReport.status()
                    )
            ));
        }

        return new ChunkPreviewResponse(
                chunkInfos.size(),
                textToChunk.length(),
                chunkSize,
                chunkOverlap,
                chunkInfos
        );
    }

    public int resolveChunkSize(Integer requested) {
        if (requested != null && requested >= 100) {
            return requested;
        }
        return ragProperties.chunking().defaultChunkSize();
    }

    public int resolveChunkOverlap(Integer requested, int chunkSize) {
        if (requested != null && requested >= 0) {
            return textSplitter.optimizeOverlap(chunkSize, requested);
        }
        return textSplitter.optimizeOverlap(chunkSize, ragProperties.chunking().defaultChunkOverlap());
    }
}
