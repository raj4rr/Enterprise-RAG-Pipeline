package com.example.ragpipeline;

import com.example.ragpipeline.config.RagProperties;
import com.example.ragpipeline.service.EmbeddingModelService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EmbeddingModelServiceTest {

    private EmbeddingModelService embeddingModelService;

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
        embeddingModelService = new EmbeddingModelService(properties);
    }

    @Test
    void testAsymmetricPrefixesForNomicEmbedText() {
        String query = "What is RAG?";
        String preparedQuery = embeddingModelService.prepareQueryForEmbedding(query);
        assertEquals("search_query: What is RAG?", preparedQuery);

        String doc = "Retrieval Augmented Generation bridges LLMs with external data.";
        String preparedDoc = embeddingModelService.prepareDocumentForEmbedding(doc);
        assertEquals("search_document: Retrieval Augmented Generation bridges LLMs with external data.", preparedDoc);
    }

    @Test
    void testCleanTextPreservationAndExtraction() {
        Document originalDoc = new Document("Important factual statement.", Map.of("source", "doc.txt"));
        List<Document> prepared = embeddingModelService.prepareChunksForIngestion(List.of(originalDoc));

        assertEquals(1, prepared.size());
        assertTrue(prepared.get(0).getText().startsWith("search_document: "));
        assertEquals("Important factual statement.", prepared.get(0).getMetadata().get("clean_text"));

        String extracted = embeddingModelService.extractCleanText(prepared.get(0));
        assertEquals("Important factual statement.", extracted);
    }
}
