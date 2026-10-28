package com.example.ragpipeline.controller;

import com.example.ragpipeline.config.RagProperties;
import com.example.ragpipeline.dto.ChatRequest;
import com.example.ragpipeline.dto.ChatResponse;
import com.example.ragpipeline.dto.RetrievedSource;
import com.example.ragpipeline.dto.VectorStoreStats;
import com.example.ragpipeline.service.RagChatService;
import com.example.ragpipeline.service.VectorStoreService;
import org.springframework.ai.document.Document;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/rag")
@CrossOrigin(origins = "*")
public class RagChatController {

    private final RagChatService ragChatService;
    private final VectorStoreService vectorStoreService;
    private final RagProperties ragProperties;

    public RagChatController(RagChatService ragChatService,
                             VectorStoreService vectorStoreService,
                             RagProperties ragProperties) {
        this.ragChatService = ragChatService;
        this.vectorStoreService = vectorStoreService;
        this.ragProperties = ragProperties;
    }

    /**
     * Executes the complete RAG query flow:
     * query -> embedding -> vector search -> prompt augmentation -> LLM response
     */
    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest request) {
        ChatResponse response = ragChatService.chat(request);
        return ResponseEntity.ok(response);
    }

    /**
     * Vector similarity search only (returns top-K matching chunks and similarity scores).
     */
    @PostMapping("/search")
    public ResponseEntity<List<RetrievedSource>> search(
            @RequestParam("query") String query,
            @RequestParam(value = "topK", required = false) Integer topK,
            @RequestParam(value = "threshold", required = false) Double threshold) {

        int safeTopK = (topK != null && topK > 0) ? topK : ragProperties.retrieval().defaultTopK();
        double safeThreshold = (threshold != null && threshold >= 0.0) ? threshold : ragProperties.retrieval().defaultSimilarityThreshold();

        List<Document> docs = vectorStoreService.similaritySearch(query, safeTopK, safeThreshold);
        List<RetrievedSource> sources = new ArrayList<>();

        for (int i = 0; i < docs.size(); i++) {
            Document d = docs.get(i);
            Map<String, Object> meta = d.getMetadata();

            double score = 1.0;
            if (meta.containsKey("distance")) {
                score = 1.0 - ((Number) meta.get("distance")).doubleValue();
            } else if (meta.containsKey("similarity")) {
                score = ((Number) meta.get("similarity")).doubleValue();
            }

            sources.add(new RetrievedSource(
                    i + 1,
                    meta.getOrDefault("source", "Unknown").toString(),
                    meta.containsKey("chunk_index") ? ((Number) meta.get("chunk_index")).intValue() : i,
                    Math.round(score * 1000.0) / 1000.0,
                    d.getText(),
                    meta
            ));
        }

        return ResponseEntity.ok(sources);
    }

    /**
     * Returns pipeline status, vector store chunk count, and model metadata.
     */
    @GetMapping("/status")
    public ResponseEntity<VectorStoreStats> getStatus() {
        VectorStoreStats stats = vectorStoreService.getStats(
                "nomic-embed-text:latest (Ollama)",
                "qwen2.5:1.5b / llama3.2:latest (Ollama)"
        );
        return ResponseEntity.ok(stats);
    }
}
