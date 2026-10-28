package com.example.ragpipeline.dto;

public record ChatRequest(
        String query,
        Integer topK,
        Double similarityThreshold,
        String model,
        Double temperature,
        String systemPrompt
) {}
