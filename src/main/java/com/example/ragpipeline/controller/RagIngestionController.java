package com.example.ragpipeline.controller;

import com.example.ragpipeline.dto.*;
import com.example.ragpipeline.service.DocumentChunkingService;
import com.example.ragpipeline.service.FileStorageService;
import com.example.ragpipeline.service.VectorStoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;

@RestController
@RequestMapping("/api/rag")
@CrossOrigin(origins = "*")
public class RagIngestionController {

    private static final Logger log = LoggerFactory.getLogger(RagIngestionController.class);

    private final FileStorageService fileStorageService;
    private final DocumentChunkingService chunkingService;
    private final VectorStoreService vectorStoreService;

    public RagIngestionController(FileStorageService fileStorageService,
                                  DocumentChunkingService chunkingService,
                                  VectorStoreService vectorStoreService) {
        this.fileStorageService = fileStorageService;
        this.chunkingService = chunkingService;
        this.vectorStoreService = vectorStoreService;
    }

    /**
     * Uploads a document (PDF, DOCX, TXT, MD, etc.), chunks it with specified overlap,
     * generates embeddings, and saves into the vector database.
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<IngestResponse> uploadAndIngest(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "chunkSize", required = false) Integer chunkSize,
            @RequestParam(value = "chunkOverlap", required = false) Integer chunkOverlap) {

        long startTime = System.currentTimeMillis();
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(new IngestResponse(
                    false, "", 0, 0, 0, 0, "Uploaded file is empty", 0, List.of()
            ));
        }

        try {
            // 1. Store file and read text with Apache Tika
            var storeResult = fileStorageService.storeAndRead(file);
            List<Document> rawDocuments = storeResult.documents();

            // 2. Split documents into chunks with configured overlap
            int actualChunkSize = chunkingService.resolveChunkSize(chunkSize);
            int actualOverlap = chunkingService.resolveChunkOverlap(chunkOverlap, actualChunkSize);
            List<Document> chunks = chunkingService.chunkDocuments(rawDocuments, actualChunkSize, actualOverlap);

            // 3. Generate embeddings and save into vector store
            vectorStoreService.addDocuments(chunks);

            long duration = System.currentTimeMillis() - startTime;
            int totalChars = chunks.stream().mapToInt(c -> c.getText().length()).sum();

            // Prepare sample chunks for the response
            List<ChunkInfo> sampleChunks = chunks.stream()
                    .limit(3)
                    .map(c -> new ChunkInfo(
                            (int) c.getMetadata().getOrDefault("chunk_index", 0),
                            c.getText(),
                            c.getText().length(),
                            c.getText().split("\\s+").length,
                            actualOverlap,
                            c.getMetadata()
                    ))
                    .toList();

            return ResponseEntity.ok(new IngestResponse(
                    true,
                    storeResult.originalFilename(),
                    chunks.size(),
                    totalChars,
                    actualChunkSize,
                    actualOverlap,
                    String.format("Successfully parsed, split into %d chunks (overlap: %d), embedded and saved to vector store.", chunks.size(), actualOverlap),
                    duration,
                    sampleChunks
            ));

        } catch (IOException e) {
            log.error("Failed to process uploaded file: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(new IngestResponse(
                    false, file.getOriginalFilename(), 0, 0, 0, 0,
                    "Failed to process file: " + e.getMessage(),
                    System.currentTimeMillis() - startTime,
                    List.of()
            ));
        }
    }

    /**
     * Ingests plain text or notes directly into the vector database.
     */
    @PostMapping("/ingest-text")
    public ResponseEntity<IngestResponse> ingestRawText(@RequestBody IngestRequest request) {
        long startTime = System.currentTimeMillis();
        if (request.content() == null || request.content().isBlank()) {
            return ResponseEntity.badRequest().body(new IngestResponse(
                    false, "", 0, 0, 0, 0, "Text content cannot be empty", 0, List.of()
            ));
        }

        String title = (request.title() != null && !request.title().isBlank())
                ? request.title()
                : "Note_" + System.currentTimeMillis();

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("source", title);
        metadata.put("content_type", "text/plain");

        Document doc = new Document(request.content(), metadata);

        int actualChunkSize = chunkingService.resolveChunkSize(request.chunkSize());
        int actualOverlap = chunkingService.resolveChunkOverlap(request.chunkOverlap(), actualChunkSize);

        List<Document> chunks = chunkingService.chunkDocuments(List.of(doc), actualChunkSize, actualOverlap);
        vectorStoreService.addDocuments(chunks);

        long duration = System.currentTimeMillis() - startTime;
        return ResponseEntity.ok(new IngestResponse(
                true,
                title,
                chunks.size(),
                request.content().length(),
                actualChunkSize,
                actualOverlap,
                String.format("Successfully embedded and saved %d chunks from text.", chunks.size()),
                duration,
                List.of()
        ));
    }

    /**
     * Previews chunks and overlap without saving into the database.
     */
    @PostMapping("/preview-chunks")
    public ResponseEntity<ChunkPreviewResponse> previewChunks(@RequestBody ChunkPreviewRequest request) {
        ChunkPreviewResponse response = chunkingService.previewChunks(
                request.text(),
                request.chunkSize(),
                request.chunkOverlap()
        );
        return ResponseEntity.ok(response);
    }

    /**
     * Returns a list of all stored files.
     */
    @GetMapping("/documents")
    public ResponseEntity<Map<String, Object>> getDocuments() {
        List<String> files = fileStorageService.listStoredFiles();
        return ResponseEntity.ok(Map.of(
                "totalFiles", files.size(),
                "files", files
        ));
    }

    /**
     * Clears the vector store.
     */
    @DeleteMapping("/reset")
    public ResponseEntity<Map<String, String>> resetVectorStore() {
        vectorStoreService.reset();
        return ResponseEntity.ok(Map.of("message", "Vector store cleared successfully."));
    }
}
