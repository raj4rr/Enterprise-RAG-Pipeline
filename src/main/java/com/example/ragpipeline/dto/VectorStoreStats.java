package com.example.ragpipeline.dto;

import java.util.List;

public record VectorStoreStats(
        int totalStoredChunks,
        List<String> ingestedDocuments,
        String vectorStoreLocation,
        String embeddingModel,
        String chatModel,
        boolean isVectorStoreLoaded
) {}
