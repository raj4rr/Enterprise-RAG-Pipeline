package com.example.ragpipeline.controller;

import com.example.ragpipeline.config.RagProperties;
import com.example.ragpipeline.dto.ChatRequest;
import com.example.ragpipeline.dto.ChatResponse;
import com.example.ragpipeline.dto.RetrievedSource;
import com.example.ragpipeline.dto.VectorStoreStats;
import com.example.ragpipeline.service.RagChatService;
import com.example.ragpipeline.service.RerankingService;
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
    private final RerankingService rerankingService;
    private final RagProperties ragProperties;

    public RagChatController(RagChatService ragChatService,
                             VectorStoreService vectorStoreService,
                             RerankingService rerankingService,
                             RagProperties ragProperties) {
        this.ragChatService = ragChatService;
        this.vectorStoreService = vectorStoreService;
        this.rerankingService = rerankingService;
        this.ragProperties = ragProperties;
    }

    /**
     * Executes the complete production RAG query flow:
     * Hybrid Search -> Reranking -> Confidence Gate -> Strict Grounding Prompt -> Citations
     */
    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest request) {
        ChatResponse response = ragChatService.chat(request);
        return ResponseEntity.ok(response);
    }

    /**
     * Hybrid Search + Reranking search endpoint returning scored and reranked chunks.
     */
    @PostMapping("/search")
    public ResponseEntity<List<RetrievedSource>> search(
            @RequestParam("query") String query,
            @RequestParam(value = "topK", required = false) Integer topK,
            @RequestParam(value = "threshold", required = false) Double threshold,
            @RequestParam(value = "source", required = false) String sourceFilter,
            @RequestParam(value = "rerank", required = false, defaultValue = "true") Boolean rerank) {

        int safeTopK = (topK != null && topK > 0) ? topK : ragProperties.retrieval().defaultTopK();
        double safeThreshold = (threshold != null && threshold >= 0.0) ? threshold : ragProperties.retrieval().defaultSimilarityThreshold();

        Map<String, Object> metadataFilter = (sourceFilter != null && !sourceFilter.isBlank())
                ? Map.of("source", sourceFilter)
                : null;

        var candidates = vectorStoreService.hybridSearch(query, safeTopK * 2, safeThreshold, metadataFilter);

        List<RetrievedSource> sources = new ArrayList<>();
        if (rerank != null && rerank) {
            var reranked = rerankingService.rerank(query, candidates, safeTopK);
            for (int i = 0; i < reranked.size(); i++) {
                var rd = reranked.get(i);
                Document d = rd.document();
                Map<String, Object> meta = d.getMetadata();
                sources.add(new RetrievedSource(
                        i + 1,
                        meta.getOrDefault("source", "Unknown").toString(),
                        meta.containsKey("chunk_index") ? ((Number) meta.get("chunk_index")).intValue() : i,
                        rd.initialScore(),
                        rd.rerankScore(),
                        meta.getOrDefault("match_type", "HYBRID").toString(),
                        d.getText(),
                        meta
                ));
            }
        } else {
            for (int i = 0; i < Math.min(safeTopK, candidates.size()); i++) {
                var cd = candidates.get(i);
                Document d = cd.document();
                Map<String, Object> meta = d.getMetadata();
                sources.add(new RetrievedSource(
                        i + 1,
                        meta.getOrDefault("source", "Unknown").toString(),
                        meta.containsKey("chunk_index") ? ((Number) meta.get("chunk_index")).intValue() : i,
                        cd.score(),
                        cd.score(),
                        cd.matchType(),
                        d.getText(),
                        meta
                ));
            }
        }

        return ResponseEntity.ok(sources);
    }

    /**
     * Returns pipeline status, vector store chunk count, and model metadata.
     */
    @GetMapping("/status")
    public ResponseEntity<VectorStoreStats> getStatus() {
        VectorStoreStats stats = vectorStoreService.getStats(
                ragProperties.embedding().model() + " (Ollama)",
                "qwen2.5:1.5b / llama3.2:latest (Ollama)"
        );
        return ResponseEntity.ok(stats);
    }
}
