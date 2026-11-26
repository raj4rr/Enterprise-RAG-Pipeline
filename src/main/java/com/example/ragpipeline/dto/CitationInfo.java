package com.example.ragpipeline.dto;

public record CitationInfo(
        String citationTag,
        String sourceName,
        int chunkIndex,
        boolean verified,
        double groundingScore,
        String supportingSnippet,
        String verificationMessage
) {}
