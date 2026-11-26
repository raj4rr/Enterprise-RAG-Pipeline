package com.example.ragpipeline.dto;

import java.util.Map;

public record RetrievedSource(
        int rank,
        String sourceName,
        int chunkIndex,
        double similarityScore,
        double rerankScore,
        String matchType,
        String contentSnippet,
        Map<String, Object> metadata
) {
    public RetrievedSource(int rank, String sourceName, int chunkIndex, double similarityScore, String contentSnippet, Map<String, Object> metadata) {
        this(rank, sourceName, chunkIndex, similarityScore, similarityScore, "HYBRID", contentSnippet, metadata);
    }
}
