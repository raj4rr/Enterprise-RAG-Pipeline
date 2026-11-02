package com.example.ragpipeline;

import com.example.ragpipeline.config.RagProperties;
import com.example.ragpipeline.dto.DocumentInfo;
import com.example.ragpipeline.service.FileStorageService;
import com.example.ragpipeline.service.VectorStoreService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.SimpleVectorStore;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VectorStoreServiceTest {

    private SimpleVectorStore vectorStore;
    private RagProperties ragProperties;
    private FileStorageService fileStorageService;
    private VectorStoreService vectorStoreService;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        // Simple stub for EmbeddingModel to avoid ByteBuddy class modification issues on Java 25
        EmbeddingModel stubEmbeddingModel = new EmbeddingModel() {
            @Override
            public EmbeddingResponse call(EmbeddingRequest request) {
                return new EmbeddingResponse(List.of());
            }

            @Override
            public float[] embed(Document document) {
                return new float[]{0.1f, 0.2f, 0.3f};
            }

            @Override
            public float[] embed(String text) {
                return new float[]{0.1f, 0.2f, 0.3f};
            }

            @Override
            public int dimensions() {
                return 3;
            }
        };

        vectorStore = SimpleVectorStore.builder(stubEmbeddingModel).build();

        Path storeFile = tempDir.resolve("vector_store.json");
        Path uploadDir = tempDir.resolve("uploads");

        ragProperties = new RagProperties(
                new RagProperties.Chunking(500, 100, 50),
                new RagProperties.Retrieval(4, 0.55),
                new RagProperties.Storage(uploadDir.toString(), storeFile.toString())
        );

        fileStorageService = new FileStorageService(ragProperties);
        vectorStoreService = new VectorStoreService(vectorStore, ragProperties);
    }

    @Test
    void testAddAndListDocuments() {
        Document chunk1 = new Document("First chunk content", Map.of("source", "doc1.txt"));
        Document chunk2 = new Document("Second chunk content", Map.of("source", "doc1.txt"));
        Document chunk3 = new Document("Third chunk content", Map.of("source", "doc2.pdf"));

        vectorStoreService.addDocuments(List.of(chunk1, chunk2, chunk3));

        List<DocumentInfo> docs = vectorStoreService.listDocuments(fileStorageService);
        assertEquals(2, docs.size());

        DocumentInfo doc1 = docs.stream().filter(d -> d.filename().equals("doc1.txt")).findFirst().orElseThrow();
        assertEquals(2, doc1.chunkCount());

        DocumentInfo doc2 = docs.stream().filter(d -> d.filename().equals("doc2.pdf")).findFirst().orElseThrow();
        assertEquals(1, doc2.chunkCount());
    }

    @Test
    void testDeleteDocument() {
        Document chunk1 = new Document("Text", Map.of("source", "manual.pdf"));
        vectorStoreService.addDocuments(List.of(chunk1));

        int deleted = vectorStoreService.deleteDocument("manual.pdf");
        assertEquals(1, deleted);

        List<DocumentInfo> docs = vectorStoreService.listDocuments(fileStorageService);
        assertTrue(docs.isEmpty());
    }
}
