package com.example.ragpipeline;

import com.example.ragpipeline.config.RagProperties;
import com.example.ragpipeline.service.HybridSearchService;
import com.example.ragpipeline.service.RerankingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RerankingServiceTest {

    private RerankingService rerankingService;

    @BeforeEach
    void setUp() {
        RagProperties properties = new RagProperties(
                new RagProperties.Quality(true, 0.40, true),
                new RagProperties.Chunking(500, 100, 50, "SENTENCE_AWARE"),
                new RagProperties.Embedding("nomic-embed-text:latest", true, "search_query: ", "search_document: "),
                new RagProperties.Retrieval(4, 0.55),
                new RagProperties.Grounding(true, true, "No info found."),
                new RagProperties.Storage("data/uploads", "data/vectors/store.json")
        );
        rerankingService = new RerankingService(properties);
    }

    @Test
    void testCrossEncoderRerankingPromotesMostRelevantChunk() {
        // Candidate 1: Generic mention with moderate overlap
        Document doc1 = new Document("d1", "General system architecture involves boundary memory management and computing.",
                Map.of("source", "hardware.txt"));

        // Candidate 2: Exact answer with high token coverage and proximity
        Document doc2 = new Document("d2", "Sliding-window chunk overlap solves context boundary loss by duplicating boundary tokens.",
                Map.of("source", "rag.txt"));

        List<HybridSearchService.ScoredDocument> candidates = List.of(
                new HybridSearchService.ScoredDocument(doc1, 0.70, "HYBRID"),
                new HybridSearchService.ScoredDocument(doc2, 0.65, "HYBRID")
        );

        List<RerankingService.RerankedDocument> reranked = rerankingService.rerank(
                "Why does sliding-window chunk overlap solve context boundary loss?",
                candidates,
                2
        );

        assertFalse(reranked.isEmpty());
        assertEquals("rag.txt", reranked.get(0).document().getMetadata().get("source"),
                "Reranker should promote chunk with high exact overlap and query token coverage to top rank");
    }
}
