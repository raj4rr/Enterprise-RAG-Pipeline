package com.example.ragpipeline.dto;

public record ChunkPreviewRequest(
        String text,
        Integer chunkSize,
        Integer chunkOverlap,
        String strategy
) {
    public ChunkPreviewRequest(String text, Integer chunkSize, Integer chunkOverlap) {
        this(text, chunkSize, chunkOverlap, null);
    }
}
