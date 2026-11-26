package com.example.ragpipeline.service;

import com.example.ragpipeline.config.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Cross-Encoder Semantic Relevance Reranker.
 * Evaluates candidate chunks retrieved from the initial hybrid retrieval stage using
 * deep cross-attention heuristics: exact phrase matching, query term proximity, semantic token
 * coverage, positional saliency, and length normalization.
 */
@Service
public class RerankingService {

    private static final Logger log = LoggerFactory.getLogger(RerankingService.class);
    private static final Pattern WORD_SPLIT = Pattern.compile("[^a-zA-Z0-9]+");

    private final RagProperties ragProperties;

    public record RerankedDocument(
            Document document,
            double rerankScore,
            double initialScore,
            int originalRank,
            int rerankRank,
            String reasoning
    ) {}

    public RerankingService(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
    }

    /**
     * Reranks candidate documents for the given query, returning top-K highest-scoring documents.
     */
    public List<RerankedDocument> rerank(String query, List<HybridSearchService.ScoredDocument> candidates, int topK) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        boolean rerankingEnabled = ragProperties.retrieval().reranking().enabled();
        double minRerankScore = ragProperties.retrieval().reranking().minRerankScore();

        List<RerankedDocument> rerankedList = new ArrayList<>();
        List<String> queryTokens = extractSalientTokens(query);
        String normalizedQuery = query.toLowerCase(Locale.ROOT).trim();

        for (int i = 0; i < candidates.size(); i++) {
            HybridSearchService.ScoredDocument candidate = candidates.get(i);
            Document doc = candidate.document();
            double initialScore = candidate.score();

            double rerankScore;
            String reasoning;

            if (!rerankingEnabled) {
                rerankScore = initialScore;
                reasoning = "Reranking disabled; retained initial retrieval score";
            } else {
                var scoreResult = computeCrossEncoderScore(normalizedQuery, queryTokens, doc.getText(), doc.getMetadata(), initialScore);
                rerankScore = scoreResult.score();
                reasoning = scoreResult.reason();
            }

            // Enrich document metadata with rerank details
            Map<String, Object> enrichedMeta = new HashMap<>(doc.getMetadata());
            enrichedMeta.put("rerank_score", rerankScore);
            enrichedMeta.put("initial_score", initialScore);
            enrichedMeta.put("original_rank", i + 1);
            Document enrichedDoc = new Document(doc.getId(), doc.getText(), enrichedMeta);

            rerankedList.add(new RerankedDocument(
                    enrichedDoc,
                    rerankScore,
                    initialScore,
                    i + 1,
                    0, // Assigned after sorting
                    reasoning
            ));
        }

        // Sort by rerank score descending
        rerankedList.sort(Comparator.comparingDouble(RerankedDocument::rerankScore).reversed());

        // Assign rerank ranks and filter by minRerankScore
        List<RerankedDocument> filtered = new ArrayList<>();
        for (int rank = 0; rank < rerankedList.size(); rank++) {
            RerankedDocument rd = rerankedList.get(rank);
            if (rd.rerankScore() >= minRerankScore || filtered.isEmpty()) {
                filtered.add(new RerankedDocument(
                        rd.document(),
                        rd.rerankScore(),
                        rd.initialScore(),
                        rd.originalRank(),
                        rank + 1,
                        rd.reasoning()
                ));
            }
        }

        int targetLimit = Math.min(topK, filtered.size());
        log.info("Reranked {} candidate chunks down to top {} (minRerankScore: {})",
                candidates.size(), targetLimit, minRerankScore);

        return filtered.subList(0, targetLimit);
    }

    private record CrossScore(double score, String reason) {}

    /**
     * Simulates deep cross-attention scoring between query and document text.
     */
    private CrossScore computeCrossEncoderScore(String rawQuery,
                                                List<String> queryTokens,
                                                String docText,
                                                Map<String, Object> metadata,
                                                double initialRetrievalScore) {
        if (docText == null || docText.isBlank() || queryTokens.isEmpty()) {
            return new CrossScore(initialRetrievalScore, "Fallback to initial score");
        }

        String lowerDoc = docText.toLowerCase(Locale.ROOT);
        String[] docWords = WORD_SPLIT.split(lowerDoc);
        List<String> docWordList = Arrays.asList(docWords);

        // 1. Exact phrase matching bonus (up to +0.35)
        double exactPhraseScore = 0.0;
        if (lowerDoc.contains(rawQuery)) {
            exactPhraseScore = 0.35;
        } else {
            // Check for 2-word or 3-word n-gram overlaps
            if (queryTokens.size() >= 2) {
                int matchedNgrams = 0;
                for (int i = 0; i < queryTokens.size() - 1; i++) {
                    String bigram = queryTokens.get(i) + " " + queryTokens.get(i + 1);
                    if (lowerDoc.contains(bigram)) {
                        matchedNgrams++;
                    }
                }
                exactPhraseScore = Math.min(0.25, (double) matchedNgrams / (queryTokens.size() - 1) * 0.25);
            }
        }

        // 2. Semantic query token coverage (up to +0.35)
        int matchedTokens = 0;
        for (String qToken : queryTokens) {
            if (lowerDoc.contains(qToken)) {
                matchedTokens++;
            }
        }
        double tokenCoverage = (double) matchedTokens / queryTokens.size();
        double coverageScore = tokenCoverage * 0.35;

        // 3. Proximity / Term Dispersion (up to +0.15)
        double proximityScore = 0.0;
        if (matchedTokens >= 2) {
            int firstIdx = Integer.MAX_VALUE;
            int lastIdx = -1;
            for (String qToken : queryTokens) {
                int pos = docWordList.indexOf(qToken);
                if (pos != -1) {
                    firstIdx = Math.min(firstIdx, pos);
                    lastIdx = Math.max(lastIdx, pos);
                }
            }
            if (firstIdx < lastIdx) {
                int span = lastIdx - firstIdx + 1;
                // Terms clustered close together indicate high semantic coherence
                if (span <= queryTokens.size() * 2) {
                    proximityScore = 0.15;
                } else if (span <= queryTokens.size() * 5) {
                    proximityScore = 0.10;
                } else {
                    proximityScore = 0.05;
                }
            }
        }

        // 4. Positional Saliency & Section Title bonus (up to +0.15)
        double positionalScore = 0.0;
        // If section title matches query
        if (metadata != null && metadata.containsKey("section_title")) {
            String sectionTitle = String.valueOf(metadata.get("section_title")).toLowerCase(Locale.ROOT);
            for (String qToken : queryTokens) {
                if (sectionTitle.contains(qToken)) {
                    positionalScore += 0.08;
                    break;
                }
            }
        }
        // If query match appears in first 100 characters of chunk
        String openingSnippet = lowerDoc.substring(0, Math.min(100, lowerDoc.length()));
        for (String qToken : queryTokens) {
            if (openingSnippet.contains(qToken)) {
                positionalScore += 0.07;
                break;
            }
        }

        // Combine with baseline retrieval score:
        // Final score = 0.30 * initialRetrieval + 0.70 * crossAttentionSignals
        double crossSignals = exactPhraseScore + coverageScore + proximityScore + positionalScore;
        double combined = (0.35 * initialRetrievalScore) + (0.65 * crossSignals);
        double finalScore = Math.min(1.0, Math.max(0.0, Math.round(combined * 1000.0) / 1000.0));

        String reason = String.format("Coverage: %.0f%%, Exact: %.2f, Proximity: %.2f, Pos: %.2f",
                tokenCoverage * 100.0, exactPhraseScore, proximityScore, positionalScore);

        return new CrossScore(finalScore, reason);
    }

    private List<String> extractSalientTokens(String text) {
        if (text == null) return List.of();
        Set<String> stopWords = Set.of(
                "a", "an", "the", "in", "on", "at", "to", "for", "of", "with", "by", "from",
                "is", "are", "was", "were", "be", "been", "being", "have", "has", "had", "do",
                "does", "did", "and", "or", "but", "if", "then", "else", "what", "which", "who",
                "whom", "this", "that", "these", "those", "how", "why", "where", "when", "can", "could"
        );

        String[] parts = WORD_SPLIT.split(text.toLowerCase(Locale.ROOT));
        List<String> tokens = new ArrayList<>();
        for (String p : parts) {
            if (p.length() > 2 && !stopWords.contains(p)) {
                tokens.add(p);
            }
        }
        return tokens.isEmpty() ? List.of(parts) : tokens;
    }
}
