package com.example.ragpipeline.service;

import com.example.ragpipeline.config.RagProperties;
import com.example.ragpipeline.dto.ChunkInfo;
import com.example.ragpipeline.dto.ChunkPreviewResponse;
import com.example.ragpipeline.splitter.OverlappingTextSplitter;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class DocumentChunkingService {

    private final OverlappingTextSplitter textSplitter;
    private final RagProperties ragProperties;

    public DocumentChunkingService(OverlappingTextSplitter textSplitter, RagProperties ragProperties) {
        this.textSplitter = textSplitter;
        this.ragProperties = ragProperties;
    }

    /**
     * Splits a list of documents into overlapping chunks.
     */
    public List<Document> chunkDocuments(List<Document> documents, Integer requestedChunkSize, Integer requestedChunkOverlap) {
        int chunkSize = resolveChunkSize(requestedChunkSize);
        int chunkOverlap = resolveChunkOverlap(requestedChunkOverlap, chunkSize);

        List<Document> allChunks = new ArrayList<>();
        int runningIndex = 0;

        for (Document doc : documents) {
            List<Document> docChunks = textSplitter.splitDocument(doc, chunkSize, chunkOverlap);
            for (Document chunk : docChunks) {
                // Re-index across multiple docs if needed
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
        if (text == null || text.isBlank()) {
            return new ChunkPreviewResponse(0, 0, 0, 0, List.of());
        }

        int chunkSize = resolveChunkSize(requestedChunkSize);
        int chunkOverlap = resolveChunkOverlap(requestedChunkOverlap, chunkSize);

        List<String> rawChunks = textSplitter.splitText(text, chunkSize, chunkOverlap);
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
                    Map.of("chunkSize", chunkSize, "chunkOverlap", chunkOverlap)
            ));
        }

        return new ChunkPreviewResponse(
                chunkInfos.size(),
                text.length(),
                chunkSize,
                chunkOverlap,
                chunkInfos
        );
    }

    public int resolveChunkSize(Integer requested) {
        if (requested != null && requested > 20) {
            return requested;
        }
        return ragProperties.chunking().defaultChunkSize();
    }

    public int resolveChunkOverlap(Integer requested, int chunkSize) {
        if (requested != null && requested >= 0) {
            return Math.min(requested, chunkSize - 10);
        }
        return Math.min(ragProperties.chunking().defaultChunkOverlap(), chunkSize - 10);
    }
}
