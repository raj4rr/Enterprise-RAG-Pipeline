package com.example.ragpipeline.dto;

public record IngestRequest(
        String title,
        String content,
        Integer chunkSize,
        Integer chunkOverlap
) {}
