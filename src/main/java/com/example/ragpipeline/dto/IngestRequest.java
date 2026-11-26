package com.example.ragpipeline.dto;

public record IngestRequest(
        String title,
        String content,
        Integer chunkSize,
        Integer chunkOverlap,
        String strategy
) {
    public IngestRequest(String title, String content, Integer chunkSize, Integer chunkOverlap) {
        this(title, content, chunkSize, chunkOverlap, null);
    }
}
