package com.example.ragpipeline.controller;

import com.example.ragpipeline.dto.*;
import com.example.ragpipeline.service.DocumentChunkingService;
import com.example.ragpipeline.service.DocumentQualityService;
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
    private final DocumentQualityService qualityService;
    private final VectorStoreService vectorStoreService;

    public RagIngestionController(FileStorageService fileStorageService,
                                  DocumentChunkingService chunkingService,
                                  DocumentQualityService qualityService,
                                  VectorStoreService vectorStoreService) {
        this.fileStorageService = fileStorageService;
        this.chunkingService = chunkingService;
        this.qualityService = qualityService;
        this.vectorStoreService = vectorStoreService;
    }

    /**
     * Uploads a document (PDF, DOCX, TXT, MD, etc.), cleans text, checks quality,
     * chunks it with specified strategy and overlap, generates embeddings, and saves into vector store.
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<IngestResponse> uploadAndIngest(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "chunkSize", required = false) Integer chunkSize,
            @RequestParam(value = "chunkOverlap", required = false) Integer chunkOverlap,
            @RequestParam(value = "strategy", required = false) String strategy) {

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

            // 2. Validate and clean document quality
            StringBuilder fullRawText = new StringBuilder();
            for (Document d : rawDocuments) {
                fullRawText.append(d.getText()).append("\n");
            }
            DocumentQualityReport qualityReport = qualityService.inspectAndClean(fullRawText.toString(), storeResult.originalFilename());

            if (!qualityReport.accepted()) {
                return ResponseEntity.badRequest().body(new IngestResponse(
                        false,
                        storeResult.originalFilename(),
                        0,
                        qualityReport.characterCount(),
                        0,
                        0,
                        "Document rejected due to poor quality: " + String.join(", ", qualityReport.issues()),
                        System.currentTimeMillis() - startTime,
                        List.of(),
                        qualityReport
                ));
            }

            // 3. Split documents into chunks with configured overlap and strategy
            int actualChunkSize = chunkingService.resolveChunkSize(chunkSize);
            int actualOverlap = chunkingService.resolveChunkOverlap(chunkOverlap, actualChunkSize);
            List<Document> chunks = chunkingService.chunkDocuments(rawDocuments, actualChunkSize, actualOverlap, strategy);

            // 4. Generate embeddings and save into vector store & BM25 hybrid index
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
                    String.format("Successfully parsed, cleaned (Quality: %s, Score: %.2f), split into %d chunks (overlap: %d), and indexed.",
                            qualityReport.status(), qualityReport.qualityScore(), chunks.size(), actualOverlap),
                    duration,
                    sampleChunks,
                    qualityReport
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
     * Ingests plain text or notes directly with quality cleaning and chunking.
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

        // Check document quality
        DocumentQualityReport qualityReport = qualityService.inspectAndClean(request.content(), title);
        if (!qualityReport.accepted()) {
            return ResponseEntity.badRequest().body(new IngestResponse(
                    false,
                    title,
                    0,
                    qualityReport.characterCount(),
                    0,
                    0,
                    "Text rejected due to low quality: " + String.join(", ", qualityReport.issues()),
                    System.currentTimeMillis() - startTime,
                    List.of(),
                    qualityReport
            ));
        }

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("source", title);
        metadata.put("content_type", "text/plain");
        metadata.put("uploaded_at", new Date().toString());
        metadata.put("quality_score", qualityReport.qualityScore());
        metadata.put("quality_status", qualityReport.status());

        Document doc = new Document(qualityReport.cleanedText(), metadata);

        int actualChunkSize = chunkingService.resolveChunkSize(request.chunkSize());
        int actualOverlap = chunkingService.resolveChunkOverlap(request.chunkOverlap(), actualChunkSize);

        List<Document> chunks = chunkingService.chunkDocuments(List.of(doc), actualChunkSize, actualOverlap, request.strategy());
        vectorStoreService.addDocuments(chunks);

        long duration = System.currentTimeMillis() - startTime;
        return ResponseEntity.ok(new IngestResponse(
                true,
                title,
                chunks.size(),
                qualityReport.cleanedText().length(),
                actualChunkSize,
                actualOverlap,
                String.format("Successfully cleaned, embedded and saved %d chunks from text (Quality: %s, Score: %.2f).",
                        chunks.size(), qualityReport.status(), qualityReport.qualityScore()),
                duration,
                List.of(),
                qualityReport
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
                request.chunkOverlap(),
                request.strategy()
        );
        return ResponseEntity.ok(response);
    }

    /**
     * Lists all tracked documents and chunk counts.
     */
    @GetMapping("/documents")
    public ResponseEntity<List<DocumentInfo>> listDocuments() {
        return ResponseEntity.ok(vectorStoreService.listDocuments(fileStorageService));
    }

    /**
     * Deletes a document and all its chunks from the vector store and BM25 index.
     */
    @DeleteMapping("/documents/{filename}")
    public ResponseEntity<Map<String, Object>> deleteDocument(@PathVariable("filename") String filename) {
        int deletedChunks = vectorStoreService.deleteDocument(filename);
        boolean fileDeleted = fileStorageService.deleteStoredFile(filename);

        Map<String, Object> resp = new HashMap<>();
        resp.put("success", true);
        resp.put("filename", filename);
        resp.put("chunksDeleted", deletedChunks);
        resp.put("fileDeleted", fileDeleted);
        resp.put("message", String.format("Deleted document '%s' (%d chunks removed from vector store)", filename, deletedChunks));

        return ResponseEntity.ok(resp);
    }

    /**
     * Resets the vector store and clears all indexes and uploaded files.
     */
    @PostMapping("/reset")
    public ResponseEntity<Map<String, String>> resetVectorStore() {
        vectorStoreService.reset();
        return ResponseEntity.ok(Map.of("message", "Vector store and hybrid search indexes have been completely reset."));
    }
}
