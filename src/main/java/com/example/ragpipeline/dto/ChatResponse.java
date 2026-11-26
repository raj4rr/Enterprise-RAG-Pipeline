package com.example.ragpipeline.dto;

import java.util.List;
import java.util.Map;

public record ChatResponse(
        String query,
        String answer,
        List<RetrievedSource> sources,
        int retrievedSourcesCount,
        String modelUsed,
        long elapsedTimeMs,
        double confidenceScore,
        String confidenceLevel,
        List<CitationInfo> citations,
        boolean isRefusal,
        Map<String, Object> pipelineMetrics
) {
    public ChatResponse(String query, String answer, List<RetrievedSource> sources, int retrievedSourcesCount, String modelUsed, long elapsedTimeMs) {
        this(query, answer, sources, retrievedSourcesCount, modelUsed, elapsedTimeMs, 0.85, "HIGH", List.of(), false, Map.of());
    }
}
