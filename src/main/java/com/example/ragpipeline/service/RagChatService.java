package com.example.ragpipeline.service;

import com.example.ragpipeline.config.RagProperties;
import com.example.ragpipeline.dto.ChatRequest;
import com.example.ragpipeline.dto.ChatResponse;
import com.example.ragpipeline.dto.CitationInfo;
import com.example.ragpipeline.dto.RetrievedSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class RagChatService {

    private static final Logger log = LoggerFactory.getLogger(RagChatService.class);

    private final VectorStoreService vectorStoreService;
    private final RerankingService rerankingService;
    private final CitationService citationService;
    private final ChatClient chatClient;
    private final RagProperties ragProperties;

    public RagChatService(VectorStoreService vectorStoreService,
                          RerankingService rerankingService,
                          CitationService citationService,
                          ChatClient chatClient,
                          RagProperties ragProperties) {
        this.vectorStoreService = vectorStoreService;
        this.rerankingService = rerankingService;
        this.citationService = citationService;
        this.chatClient = chatClient;
        this.ragProperties = ragProperties;
    }

    /**
     * Executes the complete production-grade RAG pipeline:
     * Query -> Hybrid Search (Dense + BM25) with Metadata Filtering -> Cross-Encoder Reranking
     * -> Confidence Gate -> Strict Grounding Prompt -> LLM Execution -> Citation Verification
     * -> Confidence Tier Calculation
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

        double similarityThreshold = (request.similarityThreshold() != null && request.similarityThreshold() >= 0.0)
                ? request.similarityThreshold()
                : ragProperties.retrieval().defaultSimilarityThreshold();

        double minConfidenceThreshold = (request.minConfidenceThreshold() != null && request.minConfidenceThreshold() >= 0.0)
                ? request.minConfidenceThreshold()
                : ragProperties.retrieval().minConfidenceThreshold();

        boolean enableReranking = (request.enableReranking() != null)
                ? request.enableReranking()
                : ragProperties.retrieval().reranking().enabled();

        boolean strictGrounding = (request.strictGrounding() != null)
                ? request.strictGrounding()
                : ragProperties.grounding().strictMode();

        Map<String, Object> metadataFilter = request.metadataFilter();

        // 1. HYBRID SEARCH (Dense Vector + BM25 Sparse Search + Metadata Filtering)
        int candidatePoolSize = enableReranking
                ? Math.max(topK * 2, ragProperties.retrieval().reranking().topNCandidates())
                : topK;

        log.info("Executing Hybrid Search: query='{}', candidatePool={}, threshold={}, metadataFilter={}",
                query, candidatePoolSize, similarityThreshold, metadataFilter);

        List<HybridSearchService.ScoredDocument> candidateDocs = vectorStoreService.hybridSearch(
                query, candidatePoolSize, similarityThreshold, metadataFilter
        );
        log.info("Hybrid Search returned {} candidate chunks", candidateDocs.size());

        // 2. CROSS-ENCODER RERANKING
        List<RerankingService.RerankedDocument> rerankedDocs = rerankingService.rerank(query, candidateDocs, topK);
        log.info("Reranking completed: {} chunks retained for context augmentation", rerankedDocs.size());

        // 3. CONFIDENCE THRESHOLD & SUFFICIENCY GATE
        double topRetrievalScore = candidateDocs.isEmpty() ? 0.0 : candidateDocs.get(0).score();
        double topRerankScore = rerankedDocs.isEmpty() ? 0.0 : rerankedDocs.get(0).rerankScore();
        double highestConfidenceSignal = Math.max(topRetrievalScore, topRerankScore);

        if (rerankedDocs.isEmpty() || highestConfidenceSignal < minConfidenceThreshold) {
            log.warn("Confidence gate triggered: highest score ({}) < minConfidenceThreshold ({}). Returning safe refusal.",
                    highestConfidenceSignal, minConfidenceThreshold);

            String refusalMessage = ragProperties.grounding().insufficientContextMessage();
            long duration = System.currentTimeMillis() - startTime;
            String model = (request.model() != null && !request.model().isBlank()) ? request.model() : "qwen2.5:1.5b";

            Map<String, Object> metrics = new HashMap<>();
            metrics.put("gateTriggered", true);
            metrics.put("highestScore", highestConfidenceSignal);
            metrics.put("threshold", minConfidenceThreshold);
            metrics.put("candidateCount", candidateDocs.size());

            return new ChatResponse(
                    query,
                    refusalMessage,
                    List.of(),
                    0,
                    model,
                    duration,
                    Math.round(highestConfidenceSignal * 1000.0) / 1000.0,
                    "INSUFFICIENT_CONTEXT",
                    List.of(),
                    true,
                    metrics
            );
        }

        // 4. FORMAT RETRIEVED SOURCES AND CONTEXT
        List<RetrievedSource> sources = new ArrayList<>();
        List<Document> finalContextDocs = new ArrayList<>();
        StringBuilder contextBuilder = new StringBuilder();

        for (int i = 0; i < rerankedDocs.size(); i++) {
            RerankingService.RerankedDocument rd = rerankedDocs.get(i);
            Document doc = rd.document();
            finalContextDocs.add(doc);

            Map<String, Object> meta = doc.getMetadata();
            String sourceName = meta.getOrDefault("source", "Document").toString();
            int chunkIndex = meta.containsKey("chunk_index") ? ((Number) meta.get("chunk_index")).intValue() : i;

            sources.add(new RetrievedSource(
                    i + 1,
                    sourceName,
                    chunkIndex,
                    rd.initialScore(),
                    rd.rerankScore(),
                    meta.getOrDefault("match_type", "HYBRID").toString(),
                    doc.getText(),
                    meta
            ));

            contextBuilder.append(String.format("--- Document Chunk %d [Source: %s, Chunk #%d] ---\n", i + 1, sourceName, chunkIndex));
            if (meta.containsKey("section_title")) {
                contextBuilder.append("Section: ").append(meta.get("section_title")).append("\n");
            }
            contextBuilder.append(doc.getText()).append("\n\n");
        }

        // 5. FORMULATE STRICT GROUNDING PROMPT WITH CITATION REQUIREMENTS
        String systemPrompt = strictGrounding
                ? citationService.buildStrictSystemPrompt(request.systemPrompt())
                : (request.systemPrompt() != null && !request.systemPrompt().isBlank() ? request.systemPrompt() : "Answer the question accurately.");

        String augmentedSystemPrompt = systemPrompt + "\n=== RETRIEVED CONTEXT DOCUMENTS ===\n" + contextBuilder;

        // 6. INVOKE LLM
        log.info("Sending strictly grounded prompt to LLM...");
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

        // 7. VERIFY CITATIONS & COMPUTE CONFIDENCE
        var citationReport = citationService.verifyCitations(answer, finalContextDocs);
        List<CitationInfo> citations = citationReport.citations();

        // Calculate composite confidence score:
        // 40% top retrieval + 35% top rerank + 25% citation precision
        double citationFactor = citations.isEmpty() ? 0.70 : citationReport.citationPrecision();
        double compositeConfidence = (0.40 * topRetrievalScore) + (0.35 * topRerankScore) + (0.25 * citationFactor);
        compositeConfidence = Math.min(1.0, Math.max(0.0, Math.round(compositeConfidence * 1000.0) / 1000.0));

        String confidenceLevel;
        if (compositeConfidence >= 0.75) {
            confidenceLevel = "HIGH";
        } else if (compositeConfidence >= 0.55) {
            confidenceLevel = "MEDIUM";
        } else {
            confidenceLevel = "LOW";
        }

        long duration = System.currentTimeMillis() - startTime;
        String modelUsed = (request.model() != null && !request.model().isBlank()) ? request.model() : "qwen2.5:1.5b";

        Map<String, Object> pipelineMetrics = new HashMap<>();
        pipelineMetrics.put("topRetrievalScore", topRetrievalScore);
        pipelineMetrics.put("topRerankScore", topRerankScore);
        pipelineMetrics.put("citationPrecision", citationReport.citationPrecision());
        pipelineMetrics.put("citationStatus", citationReport.verificationStatus());
        pipelineMetrics.put("rerankingApplied", enableReranking);
        pipelineMetrics.put("hybridSearchApplied", true);
        pipelineMetrics.put("strictGroundingEnforced", strictGrounding);

        return new ChatResponse(
                query,
                answer,
                sources,
                sources.size(),
                modelUsed,
                duration,
                compositeConfidence,
                confidenceLevel,
                citations,
                false,
                pipelineMetrics
        );
    }
}
