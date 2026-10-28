package com.example.ragpipeline.service;

import com.example.ragpipeline.config.RagProperties;
import com.example.ragpipeline.dto.ChatRequest;
import com.example.ragpipeline.dto.ChatResponse;
import com.example.ragpipeline.dto.RetrievedSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class RagChatService {

    private static final Logger log = LoggerFactory.getLogger(RagChatService.class);

    private final VectorStoreService vectorStoreService;
    private final ChatClient chatClient;
    private final RagProperties ragProperties;

    public RagChatService(VectorStoreService vectorStoreService,
                          ChatClient chatClient,
                          RagProperties ragProperties) {
        this.vectorStoreService = vectorStoreService;
        this.chatClient = chatClient;
        this.ragProperties = ragProperties;
    }

    /**
     * Executes the complete RAG cycle:
     * query -> embedding -> vector search -> prompt augmentation -> LLM completion -> response
     */
    public ChatResponse chat(ChatRequest request) {
        long startTime = System.currentTimeMillis();
        String query = request.query();
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Chat query cannot be empty");
        }

        int topK = (request.topK() != null && request.topK() > 0)
                ? request.topK()
                : ragProperties.retrieval().defaultTopK();

        double threshold = (request.similarityThreshold() != null && request.similarityThreshold() >= 0.0)
                ? request.similarityThreshold()
                : ragProperties.retrieval().defaultSimilarityThreshold();

        // 1. Vector Search (Spring AI automatically embeds query using EmbeddingModel)
        log.info("Searching vector store for query: '{}' (topK: {}, threshold: {})", query, topK, threshold);
        List<Document> retrievedDocs = vectorStoreService.similaritySearch(query, topK, threshold);
        log.info("Retrieved {} relevant document chunks", retrievedDocs.size());

        // 2. Format Retrieved Sources for response
        List<RetrievedSource> sources = new ArrayList<>();
        StringBuilder contextBuilder = new StringBuilder();

        for (int i = 0; i < retrievedDocs.size(); i++) {
            Document doc = retrievedDocs.get(i);
            Map<String, Object> meta = doc.getMetadata();

            String sourceName = meta.getOrDefault("source", "Unknown Document").toString();
            int chunkIndex = meta.containsKey("chunk_index")
                    ? ((Number) meta.get("chunk_index")).intValue()
                    : i;

            // Extract similarity score if available in metadata
            double score = 1.0;
            if (meta.containsKey("distance")) {
                score = 1.0 - ((Number) meta.get("distance")).doubleValue();
            } else if (meta.containsKey("similarity")) {
                score = ((Number) meta.get("similarity")).doubleValue();
            }

            sources.add(new RetrievedSource(
                    i + 1,
                    sourceName,
                    chunkIndex,
                    Math.round(score * 1000.0) / 1000.0,
                    doc.getText(),
                    meta
            ));

            // Format context entry for LLM
            contextBuilder.append(String.format("--- Document Chunk %d [Source: %s | Chunk #%d] ---\n", i + 1, sourceName, chunkIndex));
            contextBuilder.append(doc.getText()).append("\n\n");
        }

        // 3. Formulate RAG Prompt
        String defaultSystemPrompt = """
                You are a highly capable and accurate AI assistant specializing in Question Answering based on retrieved documents.
                
                Instructions:
                1. Answer the user's question directly, clearly, and concisely based on the retrieved context documents provided below.
                2. When referencing facts, you may cite the source document name (e.g., "[Source: filename]").
                3. If the retrieved context does not contain enough information to answer the question, state: "Based on the provided documents, I could not find information regarding [topic].", and then provide whatever related helpful context is present.
                4. Maintain strict factual accuracy without hallucinations.
                """;

        String systemPrompt = (request.systemPrompt() != null && !request.systemPrompt().isBlank())
                ? request.systemPrompt()
                : defaultSystemPrompt;

        String augmentedSystemPrompt;
        if (retrievedDocs.isEmpty()) {
            augmentedSystemPrompt = systemPrompt + "\n\nNote: No matching documents were found in the vector database for this query.";
        } else {
            augmentedSystemPrompt = systemPrompt + "\n\n=== RETRIEVED CONTEXT DOCUMENTS ===\n" + contextBuilder;
        }

        // 4. Pass Augmented Prompt to LLM
        log.info("Sending augmented prompt to LLM...");
        String answer;
        try {
            answer = chatClient.prompt()
                    .system(augmentedSystemPrompt)
                    .user(query)
                    .call()
                    .content();
        } catch (Exception e) {
            log.error("Error invoking LLM: {}", e.getMessage(), e);
            answer = "Error generating response from LLM: " + e.getMessage();
        }

        long duration = System.currentTimeMillis() - startTime;
        String modelUsed = (request.model() != null && !request.model().isBlank())
                ? request.model()
                : "qwen2.5:1.5b";

        return new ChatResponse(
                query,
                answer,
                sources,
                sources.size(),
                modelUsed,
                duration
        );
    }
}
