package com.example.ragpipeline;

import com.example.ragpipeline.config.RagProperties;
import com.example.ragpipeline.service.CitationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StrictGroundingAndCitationTest {

    private CitationService citationService;

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
        citationService = new CitationService(properties);
    }

    @Test
    void testStrictSystemPromptIncludesMandatoryGrounding() {
        String prompt = citationService.buildStrictSystemPrompt("Answer concisely.");
        assertTrue(prompt.contains("MANDATORY GROUNDING & CITATION INSTRUCTIONS"));
        assertTrue(prompt.contains("[Source: <filename>, Chunk #<chunkIndex>]"));
        assertTrue(prompt.contains("STRICT FACTUAL GROUNDING"));
    }

    @Test
    void testVerifyValidCitationsAgainstContext() {
        Document chunk0 = new Document("c0", "SimpleVectorStore auto-saves state into vector_store.json on disk.",
                Map.of("source", "architecture.md", "chunk_index", 0));
        Document chunk1 = new Document("c1", "Qwen 2.5 1.5B is a compact and efficient local language model.",
                Map.of("source", "models.md", "chunk_index", 1));

        String answer = "SimpleVectorStore persists state into vector_store.json [Source: architecture.md, Chunk #0]. " +
                "Qwen 2.5 1.5B is a lightweight local LLM [Source: models.md, Chunk #1].";

        var report = citationService.verifyCitations(answer, List.of(chunk0, chunk1));

        assertEquals(2, report.totalCitations());
        assertEquals(2, report.verifiedCitations());
        assertEquals(1.0, report.citationPrecision());
        assertEquals("FULLY_VERIFIED", report.verificationStatus());
    }

    @Test
    void testDetectHallucinatedCitation() {
        Document chunk0 = new Document("c0", "Spring AI supports vector databases.",
                Map.of("source", "spring.md", "chunk_index", 0));

        String hallucinatedAnswer = "Quantum teleportation is achieved at room temperature [Source: physics_quantum.pdf, Chunk #99].";

        var report = citationService.verifyCitations(hallucinatedAnswer, List.of(chunk0));

        assertEquals(1, report.totalCitations());
        assertEquals(0, report.verifiedCitations());
        assertEquals("UNVERIFIED_HALLUCINATED", report.verificationStatus());
    }
}
