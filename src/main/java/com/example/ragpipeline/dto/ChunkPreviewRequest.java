package com.example.ragpipeline.dto;

public record ChunkPreviewRequest(
        String text,
        Integer chunkSize,
        Integer chunkOverlap
) {}
