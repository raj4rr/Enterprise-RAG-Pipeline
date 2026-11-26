package com.example.ragpipeline;

import com.example.ragpipeline.config.RagProperties;
import com.example.ragpipeline.service.HybridSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HybridSearchAndFilteringTest {

    private HybridSearchService hybridSearchService;

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
        hybridSearchService = new HybridSearchService(properties);
    }

    @Test
    void testBM25SparseExactKeywordMatching() {
        Document doc1 = new Document("c1", "Spring Boot simplifies microservices with auto-configuration and embedded Tomcat.",
                Map.of("source", "springboot.txt"));
        Document doc2 = new Document("c2", "PostgreSQL pgvector provides high dimensional vector search for AI embeddings.",
                Map.of("source", "postgres.txt"));
        Document doc3 = new Document("c3", "Apache Tika extracts metadata and structured text from PDF, DOCX, and PPTX files.",
                Map.of("source", "tika.txt"));

        hybridSearchService.indexChunks(List.of(doc1, doc2, doc3));

        // Exact technical keyword search
        List<HybridSearchService.ScoredDocument> results = hybridSearchService.searchBM25("pgvector", 2, null);
        assertFalse(results.isEmpty());
        assertEquals("postgres.txt", results.get(0).document().getMetadata().get("source"));
        assertTrue(results.get(0).score() > 0.50);
    }

    @Test
    void testMetadataFilteringBySource() {
        Document doc1 = new Document("c1", "Kubernetes pods manage containerized workloads in cloud environments.",
                Map.of("source", "k8s_manual.pdf", "quality_score", 0.90));
        Document doc2 = new Document("c2", "Docker containers package application code and dependencies together.",
                Map.of("source", "docker_guide.pdf", "quality_score", 0.85));

        hybridSearchService.indexChunks(List.of(doc1, doc2));

        // Filter only for k8s_manual.pdf
        List<HybridSearchService.ScoredDocument> filtered = hybridSearchService.searchBM25(
                "workloads", 5, Map.of("source", "k8s_manual.pdf")
        );

        assertEquals(1, filtered.size());
        assertEquals("k8s_manual.pdf", filtered.get(0).document().getMetadata().get("source"));
    }

    @Test
    void testReciprocalRankFusionCombinesDenseAndSparse() {
        Document docA = new Document("cA", "Ollama runs open-source LLMs locally on consumer hardware.",
                Map.of("source", "ollama.txt", "similarity", 0.88));
        Document docB = new Document("cB", "LangChain provides orchestrations for generative agents.",
                Map.of("source", "langchain.txt", "similarity", 0.65));

        hybridSearchService.indexChunks(List.of(docA, docB));

        List<HybridSearchService.ScoredDocument> fused = hybridSearchService.fuseResults(
                List.of(docA, docB), "Ollama open-source", 2, 0.40, null
        );

        assertFalse(fused.isEmpty());
        assertEquals("ollama.txt", fused.get(0).document().getMetadata().get("source"));
        assertTrue(fused.get(0).document().getMetadata().containsKey("hybrid_score"));
    }
}
