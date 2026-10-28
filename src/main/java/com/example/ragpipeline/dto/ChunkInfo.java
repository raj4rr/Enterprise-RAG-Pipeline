package com.example.ragpipeline.dto;

import java.util.Map;

public record ChunkInfo(
        int index,
        String content,
        int charCount,
        int wordCount,
        int overlapWithPrevious,
        Map<String, Object> metadata
) {}
