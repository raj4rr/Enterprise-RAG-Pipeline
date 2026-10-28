package com.example.ragpipeline.dto;

import java.util.List;

public record ChatResponse(
        String query,
        String answer,
        List<RetrievedSource> sources,
        int retrievedSourcesCount,
        String modelUsed,
        long elapsedTimeMs
) {}
