package com.example.ragpipeline.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rag")
public record RagProperties(
        Quality quality,
        Chunking chunking,
        Embedding embedding,
        Retrieval retrieval,
        Grounding grounding,
        Storage storage
) {
    public RagProperties {
        if (quality == null) quality = new Quality(true, 0.40, true);
        if (chunking == null) chunking = new Chunking(500, 100, 50, "SENTENCE_AWARE");
        if (embedding == null) embedding = new Embedding("nomic-embed-text:latest", true, "search_query: ", "search_document: ");
        if (retrieval == null) retrieval = new Retrieval(4, 0.55, 0.50,
                new HybridSearch(true, 0.65, 0.35, 60),
                new Reranking(true, 8, 0.30));
        if (grounding == null) grounding = new Grounding(true, true,
                "I am unable to answer based on the provided documents because the information is not present in the retrieved context.");
        if (storage == null) storage = new Storage("data/uploads", "data/vectors/vector_store.json");
    }

    public record Quality(
            boolean cleanNoise,
            double minQualityScore,
            boolean stripBoilerplate
    ) {
        public Quality {
            if (minQualityScore <= 0.0) minQualityScore = 0.40;
        }
    }

    public record Chunking(
            int defaultChunkSize,
            int defaultChunkOverlap,
            int minChunkSizeChars,
            String strategy
    ) {
        public Chunking {
            if (defaultChunkSize <= 0) defaultChunkSize = 500;
            if (defaultChunkOverlap < 0) defaultChunkOverlap = 100;
            if (minChunkSizeChars <= 0) minChunkSizeChars = 50;
            if (strategy == null || strategy.isBlank()) strategy = "SENTENCE_AWARE";
        }

        public Chunking(int defaultChunkSize, int defaultChunkOverlap, int minChunkSizeChars) {
            this(defaultChunkSize, defaultChunkOverlap, minChunkSizeChars, "SENTENCE_AWARE");
        }
    }

    public record Embedding(
            String model,
            boolean useAsymmetricPrefixes,
            String queryPrefix,
            String documentPrefix
    ) {
        public Embedding {
            if (model == null || model.isBlank()) model = "nomic-embed-text:latest";
            if (queryPrefix == null) queryPrefix = "search_query: ";
            if (documentPrefix == null) documentPrefix = "search_document: ";
        }
    }

    public record HybridSearch(
            boolean enabled,
            double denseWeight,
            double sparseWeight,
            int rrfK
    ) {
        public HybridSearch {
            if (denseWeight <= 0.0) denseWeight = 0.65;
            if (sparseWeight <= 0.0) sparseWeight = 0.35;
            if (rrfK <= 0) rrfK = 60;
        }
    }

    public record Reranking(
            boolean enabled,
            int topNCandidates,
            double minRerankScore
    ) {
        public Reranking {
            if (topNCandidates <= 0) topNCandidates = 8;
            if (minRerankScore < 0.0) minRerankScore = 0.30;
        }
    }

    public record Retrieval(
            int defaultTopK,
            double defaultSimilarityThreshold,
            double minConfidenceThreshold,
            HybridSearch hybridSearch,
            Reranking reranking
    ) {
        public Retrieval {
            if (defaultTopK <= 0) defaultTopK = 4;
            if (defaultSimilarityThreshold <= 0.0) defaultSimilarityThreshold = 0.55;
            if (minConfidenceThreshold <= 0.0) minConfidenceThreshold = 0.50;
            if (hybridSearch == null) hybridSearch = new HybridSearch(true, 0.65, 0.35, 60);
            if (reranking == null) reranking = new Reranking(true, 8, 0.30);
        }

        // Backward compatibility constructor
        public Retrieval(int defaultTopK, double defaultSimilarityThreshold) {
            this(defaultTopK, defaultSimilarityThreshold, 0.50,
                    new HybridSearch(true, 0.65, 0.35, 60),
                    new Reranking(true, 8, 0.30));
        }
    }

    public record Grounding(
            boolean strictMode,
            boolean requireCitations,
            String insufficientContextMessage
    ) {
        public Grounding {
            if (insufficientContextMessage == null || insufficientContextMessage.isBlank()) {
                insufficientContextMessage = "I am unable to answer based on the provided documents because the information is not present in the retrieved context.";
            }
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

    // Backward-compatible constructor for 3-argument usage in tests
    public RagProperties(Chunking chunking, Retrieval retrieval, Storage storage) {
        this(
                new Quality(true, 0.40, true),
                chunking,
                new Embedding("nomic-embed-text:latest", true, "search_query: ", "search_document: "),
                retrieval,
                new Grounding(true, true, "I am unable to answer based on the provided documents because the information is not present in the retrieved context."),
                storage
        );
    }
}
