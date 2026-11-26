package com.example.ragpipeline;

import com.example.ragpipeline.config.RagProperties;
import com.example.ragpipeline.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.SimpleVectorStore;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RagEvaluationServiceTest {

    private RagEvaluationService evaluationService;

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
                new RagProperties.Retrieval(4, 0.30),
                new RagProperties.Grounding(true, true, "No info found."),
                new RagProperties.Storage("data/uploads", "target/eval_store.json")
        );

        HybridSearchService hybridSearchService = new HybridSearchService(properties);
        EmbeddingModelService embeddingModelService = new EmbeddingModelService(properties);
        VectorStoreService vectorStoreService = new VectorStoreService(vectorStore, properties, hybridSearchService, embeddingModelService);
        CitationService citationService = new CitationService(properties);

        // Pre-index a sample chunk into vector store and BM25
        Document sampleDoc = new Document("doc1", "Spring AI provides high-level abstractions for RAG pipelines with Ollama and SimpleVectorStore.",
                Map.of("source", "spring_ai_architecture.md", "chunk_index", 0));
        vectorStoreService.addDocuments(List.of(sampleDoc));

        evaluationService = new RagEvaluationService(vectorStoreService, citationService);
    }

    @Test
    void testEvaluateRetrievalSeparately() {
        var retResult = evaluationService.evaluateRetrieval(
                "Spring AI RAG pipelines",
                List.of("Spring AI", "abstractions", "pipelines"),
                List.of("spring_ai_architecture.md"),
                4
        );

        assertNotNull(retResult);
        assertEquals(1.0, retResult.hitRate(), "Hit rate should be 1.0 when target document is retrieved");
        assertEquals(1.0, retResult.mrr(), "MRR should be 1.0 when target doc is at rank 1");
        assertTrue(retResult.contextRelevanceScore() > 0.0);
    }

    @Test
    void testEvaluateGenerationSeparately() {
        Document contextChunk = new Document("Spring AI uses Ollama for local LLM inference and embeddings.",
                Map.of("source", "spring_ai.md", "chunk_index", 0));

        String answer = "Spring AI utilizes Ollama for local LLM inference [Source: spring_ai.md, Chunk #0].";

        var genResult = evaluationService.evaluateGeneration(
                "How does Spring AI run local LLM inference?",
                answer,
                List.of(contextChunk)
        );

        assertNotNull(genResult);
        assertEquals(1.0, genResult.faithfulnessScore(), "Faithfulness should be 1.0 for grounded answer");
        assertEquals(0.0, genResult.hallucinationScore());
        assertEquals("HIGHLY_FAITHFUL", genResult.groundednessStatus());
        assertTrue(genResult.answerRelevanceScore() > 0.50);
    }

    @Test
    void testRunBenchmarkSuite() {
        var benchmark = evaluationService.runBenchmark();

        assertNotNull(benchmark);
        assertTrue(benchmark.totalTestsRun() >= 3);
        assertTrue(benchmark.overallPipelineHealthScore() > 0.0);
        assertFalse(benchmark.testSummaries().isEmpty());
    }
}
