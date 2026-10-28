package com.example.ragpipeline.dto;

import java.util.Map;

public record RetrievedSource(
        int rank,
        String sourceName,
        int chunkIndex,
        double similarityScore,
        String contentSnippet,
        Map<String, Object> metadata
) {}
