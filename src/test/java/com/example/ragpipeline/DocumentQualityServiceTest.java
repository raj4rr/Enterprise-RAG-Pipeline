package com.example.ragpipeline;

import com.example.ragpipeline.config.RagProperties;
import com.example.ragpipeline.dto.DocumentQualityReport;
import com.example.ragpipeline.service.DocumentQualityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DocumentQualityServiceTest {

    private DocumentQualityService qualityService;

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
        qualityService = new DocumentQualityService(properties);
    }

    @Test
    void testCleanNoiseAndNormalizeTypography() {
        String dirtyText = "Hello\u0000 World!   <script>alert('xss');</script>  Here is a para-\ngraph with \t unnecessary    spaces.\n\n\n\nNew section.";
        DocumentQualityReport report = qualityService.inspectAndClean(dirtyText, "test.txt");

        assertNotNull(report);
        assertTrue(report.accepted());
        assertFalse(report.cleanedText().contains("\u0000"), "Control chars must be stripped");
        assertFalse(report.cleanedText().contains("<script>"), "Scripts must be stripped");
        assertTrue(report.cleanedText().contains("paragraph"), "Hyphenated word break should be fixed");
        assertFalse(report.cleanedText().contains("\n\n\n"), "Consecutive newlines collapsed");
    }

    @Test
    void testHighQualityDocumentScoring() {
        String wellFormedDoc = """
                # Microservices Architecture Overview
                
                Microservices architecture structures an application as a collection of loosely coupled services.
                Each service is independently deployable, highly maintainable, and organized around business capabilities.
                Inter-service communication is typically achieved via lightweight protocols such as RESTful APIs or gRPC.
                This approach facilitates continuous delivery and deployment for large, complex systems.
                """;

        DocumentQualityReport report = qualityService.inspectAndClean(wellFormedDoc, "architecture.md");

        assertTrue(report.accepted());
        assertTrue(report.qualityScore() >= 0.70, "Well-formed document should have score >= 0.70");
        assertEquals("Microservices Architecture Overview", report.extractedTitle());
        assertTrue(report.wordCount() > 30);
    }

    @Test
    void testCorruptedRepetitiveGarbageRejection() {
        String garbage = "aaaa aaaa aaaa aaaa aaaa aaaa aaaa aaaa aaaa aaaa aaaa aaaa aaaa aaaa aaaa";
        DocumentQualityReport report = qualityService.inspectAndClean(garbage, "corrupt.txt");

        assertFalse(report.accepted(), "Highly repetitive document should fail quality gate");
        assertTrue(report.qualityScore() < 0.40);
        assertTrue(report.issues().stream().anyMatch(i -> i.toLowerCase().contains("diversity")));
    }

    @Test
    void testCleanAndEnrichDocument() {
        Document raw = new Document("Reliable enterprise cloud storage infrastructure with multi-region failover and automatic replication.", Map.of("source", "cloud.txt"));
        Document enriched = qualityService.cleanAndEnrichDocument(raw);

        assertNotNull(enriched);
        assertTrue(enriched.getMetadata().containsKey("quality_score"));
        assertTrue(enriched.getMetadata().containsKey("quality_status"));
        assertTrue(enriched.getMetadata().containsKey("word_count"));
    }
}
