package com.example.ragpipeline.dto;

/**
 * Details of an ingested document tracked in the RAG pipeline.
 */
public record DocumentInfo(
        String filename,
        int chunkCount,
        long fileSizeBytes,
        String uploadedAt,
        String contentType
) {}

