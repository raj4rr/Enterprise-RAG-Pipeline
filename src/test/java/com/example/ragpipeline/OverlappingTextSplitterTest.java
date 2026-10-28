package com.example.ragpipeline;

import com.example.ragpipeline.splitter.OverlappingTextSplitter;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OverlappingTextSplitterTest {

    private final OverlappingTextSplitter splitter = new OverlappingTextSplitter();

    @Test
    void testSplitWithOverlap() {
        String sampleText = """
                Retrieval-Augmented Generation (RAG) is an architectural pattern that bridges external knowledge bases with Large Language Models.
                By extracting relevant data chunks from local documents, embedding them into high-dimensional vector space,
                and performing similarity searches, RAG empowers LLMs to produce accurate, context-rich answers without retraining.
                The first phase of the RAG pipeline is document ingestion using Apache Tika.
                """;

        List<String> chunks = splitter.splitText(sampleText, 150, 40);

        assertNotNull(chunks);
        assertTrue(chunks.size() >= 2, "Expected multiple chunks for this text length");

        // Verify all chunks have content
        for (String chunk : chunks) {
            assertFalse(chunk.isBlank());
            assertTrue(chunk.length() <= 200, "Chunk should be close to requested chunk size");
        }
    }

    @Test
    void testSplitDocumentPreservesMetadata() {
        Document doc = new Document("Spring AI provides excellent abstractions for RAG pipelines with Java 25.", Map.of("source", "test.txt"));
        List<Document> chunks = splitter.splitDocument(doc, 50, 15);

        assertFalse(chunks.isEmpty());
        for (Document chunk : chunks) {
            assertEquals("test.txt", chunk.getMetadata().get("source"));
            assertNotNull(chunk.getMetadata().get("chunk_index"));
            assertNotNull(chunk.getMetadata().get("total_chunks"));
            assertNotNull(chunk.getMetadata().get("chunk_overlap"));
        }
    }
}
