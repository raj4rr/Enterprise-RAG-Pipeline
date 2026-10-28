package com.example.ragpipeline.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Configuration
public class VectorStoreConfig {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreConfig.class);

    @Bean
    public SimpleVectorStore simpleVectorStore(EmbeddingModel embeddingModel, RagProperties ragProperties) {
        SimpleVectorStore vectorStore = SimpleVectorStore.builder(embeddingModel).build();
        String storePath = ragProperties.storage().vectorStoreFile();
        File storeFile = new File(storePath);

        // Ensure parent directory exists
        if (storeFile.getParentFile() != null && !storeFile.getParentFile().exists()) {
            storeFile.getParentFile().mkdirs();
        }

        // Load persisted vectors if present
        if (storeFile.exists() && storeFile.length() > 0) {
            try {
                log.info("Loading existing vector store from: {}", storePath);
                vectorStore.load(storeFile);
                log.info("Successfully loaded existing vector store.");
            } catch (Exception e) {
                log.warn("Could not load existing vector store from {}: {}. Starting with a fresh store.", storePath, e.getMessage());
            }
        } else {
            log.info("No existing vector store found at {}. Starting empty.", storePath);
        }

        return vectorStore;
    }
}
