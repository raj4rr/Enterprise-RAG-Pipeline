package com.example.ragpipeline.dto;

import java.util.List;

public record ChunkPreviewResponse(
        int totalChunks,
        int totalCharacters,
        int chunkSize,
        int chunkOverlap,
        List<ChunkInfo> chunks
) {}
