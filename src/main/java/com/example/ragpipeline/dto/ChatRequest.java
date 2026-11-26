package com.example.ragpipeline.dto;

import java.util.Map;

public record ChatRequest(
        String query,
        Integer topK,
        Double similarityThreshold,
        Double minConfidenceThreshold,
        String model,
        Double temperature,
        String systemPrompt,
        Boolean enableHybridSearch,
        Boolean enableReranking,
        Boolean strictGrounding,
        Boolean requireCitations,
        Map<String, Object> metadataFilter
) {
    public ChatRequest(String query, Integer topK, Double similarityThreshold, String model, Double temperature, String systemPrompt) {
        this(query, topK, similarityThreshold, null, model, temperature, systemPrompt, true, true, true, true, null);
    }
}
