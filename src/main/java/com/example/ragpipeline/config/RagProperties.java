package com.example.ragpipeline.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rag")
public record RagProperties(
        Chunking chunking,
        Retrieval retrieval,
        Storage storage
) {
    public record Chunking(
            int defaultChunkSize,
            int defaultChunkOverlap,
            int minChunkSizeChars
    ) {
        public Chunking {
            if (defaultChunkSize <= 0) defaultChunkSize = 500;
            if (defaultChunkOverlap < 0) defaultChunkOverlap = 100;
            if (minChunkSizeChars <= 0) minChunkSizeChars = 50;
        }
    }

    public record Retrieval(
            int defaultTopK,
            double defaultSimilarityThreshold
    ) {
        public Retrieval {
            if (defaultTopK <= 0) defaultTopK = 4;
            if (defaultSimilarityThreshold <= 0.0) defaultSimilarityThreshold = 0.55;
        }
    }

    public record Storage(
            String uploadDir,
            String vectorStoreFile
    ) {
        public Storage {
            if (uploadDir == null || uploadDir.isBlank()) uploadDir = "data/uploads";
            if (vectorStoreFile == null || vectorStoreFile.isBlank()) vectorStoreFile = "data/vectors/vector_store.json";
        }
    }
}
