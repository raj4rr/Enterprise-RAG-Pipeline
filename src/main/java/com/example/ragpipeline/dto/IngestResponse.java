package com.example.ragpipeline.dto;

import java.util.List;

public record IngestResponse(
        boolean success,
        String documentName,
        int totalChunks,
        int totalCharacters,
        int chunkSize,
        int chunkOverlap,
        String message,
        long elapsedTimeMs,
        List<ChunkInfo> sampleChunks,
        DocumentQualityReport qualityReport
) {
    public IngestResponse(boolean success, String documentName, int totalChunks, int totalCharacters, int chunkSize, int chunkOverlap, String message, long elapsedTimeMs, List<ChunkInfo> sampleChunks) {
        this(success, documentName, totalChunks, totalCharacters, chunkSize, chunkOverlap, message, elapsedTimeMs, sampleChunks, null);
    }
}
