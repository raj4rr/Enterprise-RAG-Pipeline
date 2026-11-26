package com.example.ragpipeline.service;

import com.example.ragpipeline.config.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * High-performance In-Memory BM25 Sparse Search Engine with Reciprocal Rank Fusion (RRF)
 * and metadata filtering capabilities, running alongside dense vector search.
 */
@Service
public class HybridSearchService {

    private static final Logger log = LoggerFactory.getLogger(HybridSearchService.class);
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[^a-zA-Z0-9]+");

    private final RagProperties ragProperties;

    // BM25 parameters
    private static final double K1 = 1.2;
    private static final double B = 0.75;

    // Inverted index: term -> Map<chunkId, termFrequency>
    private final Map<String, Map<String, Integer>> invertedIndex = new ConcurrentHashMap<>();
    // Chunk storage: chunkId -> Document
    private final Map<String, Document> chunkStore = new ConcurrentHashMap<>();
    // Document lengths: chunkId -> wordCount
    private final Map<String, Integer> chunkDocLengths = new ConcurrentHashMap<>();
    // Source mapping: source -> Set<chunkId>
    private final Map<String, Set<String>> sourceToChunkIds = new ConcurrentHashMap<>();

    public HybridSearchService(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
    }

    /**
     * Indexes a chunk into the BM25 inverted index.
     */
    public synchronized void indexChunk(Document chunk) {
        if (chunk == null || chunk.getText() == null) return;
        String chunkId = chunk.getId();
        if (chunkId == null || chunkId.isBlank()) {
            chunkId = UUID.randomUUID().toString();
        }

        chunkStore.put(chunkId, chunk);

        String source = String.valueOf(chunk.getMetadata().getOrDefault("source", "unknown"));
        sourceToChunkIds.computeIfAbsent(source, k -> ConcurrentHashMap.newKeySet()).add(chunkId);

        List<String> tokens = tokenize(chunk.getText());
        chunkDocLengths.put(chunkId, tokens.size());

        Map<String, Integer> termFrequencies = new HashMap<>();
        for (String token : tokens) {
            termFrequencies.merge(token, 1, Integer::sum);
        }

        for (Map.Entry<String, Integer> entry : termFrequencies.entrySet()) {
            invertedIndex.computeIfAbsent(entry.getKey(), k -> new ConcurrentHashMap<>())
                    .put(chunkId, entry.getValue());
        }
    }

    /**
     * Indexes a batch of document chunks.
     */
    public synchronized void indexChunks(List<Document> chunks) {
        for (Document chunk : chunks) {
            indexChunk(chunk);
        }
        log.info("BM25 index updated: {} total chunks, {} unique terms", chunkStore.size(), invertedIndex.size());
    }

    /**
     * Removes all chunks associated with a source document.
     */
    public synchronized int removeSource(String sourceName) {
        Set<String> chunkIds = sourceToChunkIds.remove(sourceName);
        if (chunkIds == null || chunkIds.isEmpty()) return 0;

        for (String chunkId : chunkIds) {
            chunkStore.remove(chunkId);
            chunkDocLengths.remove(chunkId);
            for (Map<String, Integer> postings : invertedIndex.values()) {
                postings.remove(chunkId);
            }
        }
        return chunkIds.size();
    }

    /**
     * Clears the BM25 index completely.
     */
    public synchronized void clear() {
        invertedIndex.clear();
        chunkStore.clear();
        chunkDocLengths.clear();
        sourceToChunkIds.clear();
        log.info("BM25 index cleared.");
    }

    /**
     * Executes BM25 keyword search with optional metadata filters.
     */
    public List<ScoredDocument> searchBM25(String query, int topK, Map<String, Object> metadataFilter) {
        List<String> queryTokens = tokenize(query);
        if (queryTokens.isEmpty() || chunkStore.isEmpty()) {
            return List.of();
        }

        int totalDocs = chunkStore.size();
        double avgDocLength = chunkDocLengths.values().stream()
                .mapToInt(Integer::intValue)
                .average()
                .orElse(1.0);

        Map<String, Double> docScores = new HashMap<>();

        for (String term : queryTokens) {
            Map<String, Integer> postings = invertedIndex.get(term);
            if (postings == null || postings.isEmpty()) continue;

            int docFreq = postings.size();
            // IDF formula with add-1 smoothing
            double idf = Math.log(1.0 + (totalDocs - docFreq + 0.5) / (docFreq + 0.5));

            for (Map.Entry<String, Integer> entry : postings.entrySet()) {
                String chunkId = entry.getKey();
                int tf = entry.getValue();
                int docLen = chunkDocLengths.getOrDefault(chunkId, 1);

                double tfWeight = (tf * (K1 + 1.0)) / (tf + K1 * (1.0 - B + B * (docLen / avgDocLength)));
                double termScore = idf * tfWeight;

                docScores.merge(chunkId, termScore, Double::sum);
            }
        }

        if (docScores.isEmpty()) return List.of();

        // Max score normalization
        double maxScore = docScores.values().stream().mapToDouble(Double::doubleValue).max().orElse(1.0);
        if (maxScore <= 0.0) maxScore = 1.0;

        List<ScoredDocument> results = new ArrayList<>();
        for (Map.Entry<String, Double> entry : docScores.entrySet()) {
            String chunkId = entry.getKey();
            Document doc = chunkStore.get(chunkId);
            if (doc == null) continue;

            // Apply metadata filtering
            if (!matchesFilter(doc, metadataFilter)) {
                continue;
            }

            double normalizedScore = Math.min(1.0, entry.getValue() / maxScore);
            results.add(new ScoredDocument(doc, normalizedScore, "BM25"));
        }

        results.sort(Comparator.comparingDouble(ScoredDocument::score).reversed());
        return results.subList(0, Math.min(topK, results.size()));
    }

    /**
     * Merges Dense Vector Search results and Sparse BM25 results using Reciprocal Rank Fusion (RRF)
     * and weighted score fusion, respecting metadata filters.
     */
    public List<ScoredDocument> fuseResults(List<Document> denseDocs,
                                           String query,
                                           int topK,
                                           double minThreshold,
                                           Map<String, Object> metadataFilter) {
        boolean hybridEnabled = ragProperties.retrieval().hybridSearch().enabled();

        if (!hybridEnabled || chunkStore.isEmpty()) {
            // Dense-only fallback with metadata filter
            return denseDocs.stream()
                    .filter(doc -> matchesFilter(doc, metadataFilter))
                    .map(doc -> {
                        double score = extractScore(doc);
                        return new ScoredDocument(doc, score, "DENSE");
                    })
                    .filter(sd -> sd.score() >= minThreshold)
                    .limit(topK)
                    .toList();
        }

        double denseWeight = ragProperties.retrieval().hybridSearch().denseWeight();
        double sparseWeight = ragProperties.retrieval().hybridSearch().sparseWeight();
        int rrfK = ragProperties.retrieval().hybridSearch().rrfK();

        // Retrieve BM25 candidates
        List<ScoredDocument> sparseResults = searchBM25(query, topK * 2, metadataFilter);

        // Map candidate chunk ID -> candidate Document & scores
        Map<String, Document> candidateDocs = new HashMap<>();
        Map<String, Double> denseScoreMap = new HashMap<>();
        Map<String, Double> sparseScoreMap = new HashMap<>();
        Map<String, Integer> denseRankMap = new HashMap<>();
        Map<String, Integer> sparseRankMap = new HashMap<>();

        // Process dense results
        for (int rank = 0; rank < denseDocs.size(); rank++) {
            Document doc = denseDocs.get(rank);
            if (!matchesFilter(doc, metadataFilter)) continue;
            String key = getDocKey(doc);
            candidateDocs.put(key, doc);
            denseScoreMap.put(key, extractScore(doc));
            denseRankMap.put(key, rank + 1);
        }

        // Process sparse results
        for (int rank = 0; rank < sparseResults.size(); rank++) {
            ScoredDocument sd = sparseResults.get(rank);
            String key = getDocKey(sd.document());
            candidateDocs.put(key, sd.document());
            sparseScoreMap.put(key, sd.score());
            sparseRankMap.put(key, rank + 1);
        }

        // Combine using Reciprocal Rank Fusion + Weighted Score
        List<ScoredDocument> fused = new ArrayList<>();
        for (Map.Entry<String, Document> entry : candidateDocs.entrySet()) {
            String key = entry.getKey();
            Document doc = entry.getValue();

            double denseScore = denseScoreMap.getOrDefault(key, 0.0);
            double sparseScore = sparseScoreMap.getOrDefault(key, 0.0);

            // RRF component
            double rrfDense = denseRankMap.containsKey(key) ? 1.0 / (rrfK + denseRankMap.get(key)) : 0.0;
            double rrfSparse = sparseRankMap.containsKey(key) ? 1.0 / (rrfK + sparseRankMap.get(key)) : 0.0;
            double rrfScore = (denseWeight * rrfDense) + (sparseWeight * rrfSparse);

            // Weighted normalized score
            double compositeScore = (denseWeight * denseScore) + (sparseWeight * sparseScore);

            // Blend composite score with RRF boost
            double finalScore = Math.min(1.0, Math.max(compositeScore, rrfScore * 50.0));
            finalScore = Math.round(finalScore * 1000.0) / 1000.0;

            if (finalScore >= minThreshold || denseScore >= minThreshold) {
                Map<String, Object> enrichedMeta = new HashMap<>(doc.getMetadata());
                enrichedMeta.put("dense_score", denseScore);
                enrichedMeta.put("sparse_score", sparseScore);
                enrichedMeta.put("hybrid_score", finalScore);
                Document enrichedDoc = new Document(doc.getId(), doc.getText(), enrichedMeta);

                fused.add(new ScoredDocument(enrichedDoc, finalScore, "HYBRID"));
            }
        }

        fused.sort(Comparator.comparingDouble(ScoredDocument::score).reversed());
        return fused.subList(0, Math.min(topK, fused.size()));
    }

    /**
     * Checks if a document satisfies user-specified metadata filters.
     */
    public boolean matchesFilter(Document doc, Map<String, Object> filter) {
        if (filter == null || filter.isEmpty()) {
            return true;
        }

        Map<String, Object> meta = doc.getMetadata();
        for (Map.Entry<String, Object> entry : filter.entrySet()) {
            String key = entry.getKey();
            Object expected = entry.getValue();
            if (expected == null) continue;

            // Handle special filter cases
            if ("minQualityScore".equalsIgnoreCase(key) || "min_quality".equalsIgnoreCase(key)) {
                double minQ = ((Number) expected).doubleValue();
                double docQ = meta.containsKey("quality_score") ? ((Number) meta.get("quality_score")).doubleValue() : 1.0;
                if (docQ < minQ) return false;
                continue;
            }

            if ("source".equalsIgnoreCase(key) || "filename".equalsIgnoreCase(key)) {
                String actualSource = String.valueOf(meta.getOrDefault("source", ""));
                if (expected instanceof Collection<?> coll) {
                    if (!coll.contains(actualSource)) return false;
                } else if (!actualSource.equalsIgnoreCase(expected.toString())) {
                    return false;
                }
                continue;
            }

            // General metadata key match
            Object actual = meta.get(key);
            if (actual == null || !actual.toString().equalsIgnoreCase(expected.toString())) {
                return false;
            }
        }

        return true;
    }

    private String getDocKey(Document doc) {
        if (doc.getId() != null && !doc.getId().isBlank()) {
            return doc.getId();
        }
        Object src = doc.getMetadata().get("source");
        Object idx = doc.getMetadata().get("chunk_index");
        return src + "_" + idx;
    }

    private double extractScore(Document doc) {
        Map<String, Object> meta = doc.getMetadata();
        if (meta.containsKey("distance")) {
            return Math.max(0.0, 1.0 - ((Number) meta.get("distance")).doubleValue());
        }
        if (meta.containsKey("similarity")) {
            return ((Number) meta.get("similarity")).doubleValue();
        }
        return 0.80; // default assumed similarity if not populated by vector store
    }

    private List<String> tokenize(String text) {
        if (text == null) return List.of();
        String[] parts = TOKEN_PATTERN.split(text.toLowerCase(Locale.ROOT));
        List<String> tokens = new ArrayList<>(parts.length);
        for (String p : parts) {
            if (p.length() > 1) { // Skip 1-character tokens
                tokens.add(p);
            }
        }
        return tokens;
    }

    public record ScoredDocument(Document document, double score, String matchType) {}
}
