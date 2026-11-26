package com.example.ragpipeline;

import com.example.ragpipeline.config.RagProperties;
import com.example.ragpipeline.dto.ChatRequest;
import com.example.ragpipeline.dto.ChatResponse;
import com.example.ragpipeline.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.SimpleVectorStore;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConfidenceThresholdAndGateTest {

    private VectorStoreService vectorStoreService;

    @BeforeEach
    void setUp() {
        EmbeddingModel stubModel = new EmbeddingModel() {
            @Override
            public EmbeddingResponse call(EmbeddingRequest request) { return new EmbeddingResponse(List.of()); }
            @Override
            public float[] embed(Document document) { return new float[]{0.1f, 0.2f, 0.3f}; }
            @Override
            public float[] embed(String text) { return new float[]{0.1f, 0.2f, 0.3f}; }
            @Override
            public int dimensions() { return 3; }
        };

        SimpleVectorStore vectorStore = SimpleVectorStore.builder(stubModel).build();
        RagProperties properties = new RagProperties(
                new RagProperties.Quality(true, 0.40, true),
                new RagProperties.Chunking(500, 100, 50, "SENTENCE_AWARE"),
                new RagProperties.Embedding("nomic-embed-text:latest", false, "", ""),
                new RagProperties.Retrieval(4, 0.55, 0.50,
                        new RagProperties.HybridSearch(true, 0.65, 0.35, 60),
                        new RagProperties.Reranking(true, 8, 0.30)),
                new RagProperties.Grounding(true, true, "I am unable to answer based on the provided documents because the information is not present in the retrieved context."),
                new RagProperties.Storage("data/uploads", "target/test_store.json")
        );

        HybridSearchService hybridSearchService = new HybridSearchService(properties);
        EmbeddingModelService embeddingModelService = new EmbeddingModelService(properties);
        vectorStoreService = new VectorStoreService(vectorStore, properties, hybridSearchService, embeddingModelService);
    }

    @Test
    void testConfidenceGateSafelyRefusesWhenNoContextMatchesThreshold() {
        // ChatClient is passed as null to guarantee that if LLM was called, it would throw NPE.
        RagChatService gateChatService = new RagChatService(
                vectorStoreService,
                new RerankingService(RagProperties.defaults()),
                new CitationService(RagProperties.defaults()),
                null,
                RagProperties.defaults()
        );

        // No documents in store, or retrieval score is below 0.50
        ChatRequest request = new ChatRequest("What is the warp drive speed?", 4, 0.60, "model", 0.2, "system");
        ChatResponse response = gateChatService.chat(request);

        assertTrue(response.isRefusal(), "Should trigger confidence gate refusal");
        assertEquals("INSUFFICIENT_CONTEXT", response.confidenceLevel());
        assertTrue(response.answer().contains("unable to answer based on the provided documents"));
    }
}
