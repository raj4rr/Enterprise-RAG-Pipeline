package com.example.ragpipeline.controller;

import com.example.ragpipeline.dto.RetrievedSource;
import com.example.ragpipeline.service.RagEvaluationService;
import com.example.ragpipeline.service.VectorStoreService;
import org.springframework.ai.document.Document;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/rag/evaluate")
@CrossOrigin(origins = "*")
public class RagEvaluationController {

    private final RagEvaluationService evaluationService;
    private final VectorStoreService vectorStoreService;

    public RagEvaluationController(RagEvaluationService evaluationService, VectorStoreService vectorStoreService) {
        this.evaluationService = evaluationService;
        this.vectorStoreService = vectorStoreService;
    }

    public record RetrievalEvalRequest(
            String query,
            List<String> keywords,
            List<String> expectedSources,
            Integer topK
    ) {}

    public record GenerationEvalRequest(
            String query,
            String answer,
            List<String> contextSnippets
    ) {}

    /**
     * Evaluates Retrieval performance in isolation (Hit Rate, Recall@K, Precision@K, MRR, Context Relevance).
     */
    @PostMapping("/retrieval")
    public ResponseEntity<RagEvaluationService.RetrievalEvaluationResult> evaluateRetrieval(@RequestBody RetrievalEvalRequest request) {
        int topK = (request.topK() != null && request.topK() > 0) ? request.topK() : 4;
        var result = evaluationService.evaluateRetrieval(request.query(), request.keywords(), request.expectedSources(), topK);
        return ResponseEntity.ok(result);
    }

    /**
     * Evaluates Generation quality in isolation (Faithfulness / Groundedness, Answer Relevance, Citation Precision).
     */
    @PostMapping("/generation")
    public ResponseEntity<RagEvaluationService.GenerationEvaluationResult> evaluateGeneration(@RequestBody GenerationEvalRequest request) {
        List<Document> contextDocs;
        if (request.contextSnippets() != null && !request.contextSnippets().isEmpty()) {
            contextDocs = request.contextSnippets().stream()
                    .map(snippet -> new Document(snippet, Map.of("source", "ContextChunk")))
                    .toList();
        } else {
            // Retrieve current vector store context for query
            contextDocs = vectorStoreService.similaritySearch(request.query(), 4, 0.30);
        }

        var result = evaluationService.evaluateGeneration(request.query(), request.answer(), contextDocs);
        return ResponseEntity.ok(result);
    }

    /**
     * Runs an end-to-end Benchmark Suite evaluating both Retrieval and Generation across test scenarios.
     */
    @GetMapping("/benchmark")
    public ResponseEntity<RagEvaluationService.BenchmarkReport> runBenchmark() {
        return ResponseEntity.ok(evaluationService.runBenchmark());
    }
}
