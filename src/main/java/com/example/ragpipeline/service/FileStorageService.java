package com.example.ragpipeline.service;

import com.example.ragpipeline.config.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Service
public class FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);
    private final Path uploadDir;

    public FileStorageService(RagProperties ragProperties) {
        this.uploadDir = Paths.get(ragProperties.storage().uploadDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.uploadDir);
            log.info("File storage initialized at: {}", this.uploadDir);
        } catch (IOException e) {
            throw new RuntimeException("Could not initialize upload directory: " + this.uploadDir, e);
        }
    }

    /**
     * Stores an uploaded multipart file to disk and extracts documents using Tika.
     */
    public StoredFileResult storeAndRead(MultipartFile file) throws IOException {
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            originalFilename = "uploaded_file_" + System.currentTimeMillis();
        }

        // Clean filename
        String safeName = originalFilename.replaceAll("[^a-zA-Z0-9._-]", "_");
        Path targetPath = this.uploadDir.resolve(safeName);

        // Save file to disk
        Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);
        log.info("Saved file {} ({} bytes) to {}", safeName, file.getSize(), targetPath);

        // Read content using TikaDocumentReader
        Resource resource = new FileSystemResource(targetPath);
        TikaDocumentReader reader = new TikaDocumentReader(resource);
        List<Document> documents = reader.get();

        // Enrich document metadata with file info
        for (Document doc : documents) {
            Map<String, Object> metadata = doc.getMetadata();
            metadata.put("source", originalFilename);
            metadata.put("file_path", targetPath.toString());
            metadata.put("file_size", file.getSize());
            metadata.put("content_type", file.getContentType());
            metadata.put("uploaded_at", Instant.now().toString());
        }

        return new StoredFileResult(targetPath, originalFilename, documents);
    }

    /**
     * Reads a file directly from a filesystem path using Tika.
     */
    public List<Document> readFromPath(Path path) {
        Resource resource = new FileSystemResource(path);
        TikaDocumentReader reader = new TikaDocumentReader(resource);
        return reader.get();
    }

    /**
     * Lists all uploaded files currently stored on disk.
     */
    public List<String> listStoredFiles() {
        if (!Files.exists(this.uploadDir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(this.uploadDir)) {
            return stream.filter(Files::isRegularFile)
                         .map(p -> p.getFileName().toString())
                         .sorted()
                         .toList();
        } catch (IOException e) {
            log.error("Failed to list stored files: {}", e.getMessage());
            return List.of();
        }
    }

    public record StoredFileResult(
            Path savedPath,
            String originalFilename,
            List<Document> documents
    ) {}
}
