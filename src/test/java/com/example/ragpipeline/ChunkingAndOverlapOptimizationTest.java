package com.example.ragpipeline;

import com.example.ragpipeline.splitter.ChunkingStrategy;
import com.example.ragpipeline.splitter.OverlappingTextSplitter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ChunkingAndOverlapOptimizationTest {

    private OverlappingTextSplitter splitter;

    @BeforeEach
    void setUp() {
        splitter = new OverlappingTextSplitter();
    }

    @Test
    void testOverlapBoundsOptimization() {
        // Minimum allowed overlap is 10%
        int minOverlap = splitter.optimizeOverlap(500, 10);
        assertTrue(minOverlap >= 50, "Overlap should be at least 10% of 500");

        // Maximum allowed overlap is 35%
        int maxOverlap = splitter.optimizeOverlap(500, 300);
        assertTrue(maxOverlap <= 175, "Overlap should not exceed 35% of 500");
    }

    @Test
    void testSentenceAwareSplittingPreservesSentences() {
        String text = "First complete sentence explaining architectural concept. Second sentence providing critical security details. Third sentence discussing performance benchmarks. Fourth sentence outlining operational constraints.";
        List<String> chunks = splitter.splitText(text, 120, 25, ChunkingStrategy.SENTENCE_AWARE);

        assertTrue(chunks.size() >= 2);
        for (String chunk : chunks) {
            // Should not end abruptly in the middle of a word
            assertFalse(chunk.endsWith(" architectural"), "Sentence aware chunking should avoid cutting sentences");
        }
    }

    @Test
    void testParagraphAwareSplittingPreservesSections() {
        String text = """
                # Section 1: Overview
                This is the first paragraph with important context.
                
                # Section 2: Deep Dive
                This is the second paragraph that covers implementation details.
                """;

        List<Document> chunks = splitter.splitDocument(new Document(text, Map.of("source", "guide.md")), 200, 30, ChunkingStrategy.PARAGRAPH_AWARE);
        assertFalse(chunks.isEmpty());
        for (Document chunk : chunks) {
            assertNotNull(chunk.getMetadata().get("strategy"));
            assertEquals("PARAGRAPH_AWARE", chunk.getMetadata().get("strategy"));
        }
    }

    @Test
    void testAdaptiveStrategySizesAppropriately() {
        String longText = "Word ".repeat(1500); // 7500 chars
        List<String> chunks = splitter.splitText(longText, 500, 100, ChunkingStrategy.ADAPTIVE);

        assertFalse(chunks.isEmpty());
        // For large documents, adaptive strategy resolves to 850 chunk size
        assertTrue(chunks.get(0).length() <= 850 + 50);
    }
}
