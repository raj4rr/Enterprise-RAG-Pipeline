package com.example.ragpipeline.dto;

import java.util.List;

public record DocumentQualityReport(
        boolean accepted,
        double qualityScore,
        String status,
        int characterCount,
        int wordCount,
        int uniqueWordCount,
        double lexicalDiversity,
        double noiseRatio,
        List<String> issues,
        String cleanedText,
        String extractedTitle
) {}
