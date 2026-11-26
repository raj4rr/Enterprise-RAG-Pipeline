package com.example.ragpipeline.service;

import com.example.ragpipeline.config.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Manages embedding model configurations, dimensions, and task-specific instruction prefixes.
 * Supports asymmetric instruction prefixes (e.g. 'search_query: ' and 'search_document: ' for nomic-embed-text)
 * which drastically enhances retrieval recall and semantic alignment.
 */
@Service
public class EmbeddingModelService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingModelService.class);

    private final RagProperties ragProperties;

    public record ModelProfile(
            String modelName,
            int dimensions,
            boolean requiresAsymmetricPrefixes,
            String queryPrefix,
            String documentPrefix,
            String description
    ) {}

    private final Map<String, ModelProfile> supportedProfiles = new LinkedHashMap<>();

    public EmbeddingModelService(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
        initProfiles();
    }

    private void initProfiles() {
        supportedProfiles.put("nomic-embed-text", new ModelProfile(
                "nomic-embed-text:latest",
                768,
                true,
                "search_query: ",
                "search_document: ",
                "High-performance local embedding model with 8192 context window and asymmetric prefixes"
        ));
        supportedProfiles.put("mxbai-embed-large", new ModelProfile(
                "mxbai-embed-large:latest",
                1024,
                true,
                "Represent this sentence for searching relevant passages: ",
                "",
                "State-of-the-art 1024-dim model from Mixedbread AI"
        ));
        supportedProfiles.put("bge-m3", new ModelProfile(
                "bge-m3:latest",
                1024,
                true,
                "Represent this sentence for searching relevant passages: ",
                "",
                "Multi-lingual, multi-granularity embedding model"
        ));
        supportedProfiles.put("all-minilm", new ModelProfile(
                "all-minilm:latest",
                384,
                false,
                "",
                "",
                "Ultra-lightweight and fast 384-dim symmetric embedding model"
        ));
    }

    /**
     * Resolves the active model profile based on configuration.
     */
    public ModelProfile getActiveProfile() {
        String configuredModel = ragProperties.embedding().model().toLowerCase(Locale.ROOT);
        for (Map.Entry<String, ModelProfile> entry : supportedProfiles.entrySet()) {
            if (configuredModel.contains(entry.getKey())) {
                return entry.getValue();
            }
        }

        // Default custom profile
        return new ModelProfile(
                ragProperties.embedding().model(),
                768,
                ragProperties.embedding().useAsymmetricPrefixes(),
                ragProperties.embedding().queryPrefix(),
                ragProperties.embedding().documentPrefix(),
                "Custom configured embedding model"
        );
    }

    /**
     * Prepares query string for vector embedding with instruction prefix if enabled.
     */
    public String prepareQueryForEmbedding(String query) {
        if (query == null || query.isBlank()) {
            return query;
        }
        ModelProfile profile = getActiveProfile();
        if (ragProperties.embedding().useAsymmetricPrefixes() && profile.requiresAsymmetricPrefixes()) {
            String prefix = profile.queryPrefix();
            if (!query.startsWith(prefix)) {
                return prefix + query.trim();
            }
        }
        return query.trim();
    }

    /**
     * Prepares document text for vector embedding with document prefix if enabled.
     */
    public String prepareDocumentForEmbedding(String docText) {
        if (docText == null || docText.isBlank()) {
            return docText;
        }
        ModelProfile profile = getActiveProfile();
        if (ragProperties.embedding().useAsymmetricPrefixes() && profile.requiresAsymmetricPrefixes()) {
            String prefix = profile.documentPrefix();
            if (!docText.startsWith(prefix)) {
                return prefix + docText.trim();
            }
        }
        return docText.trim();
    }

    /**
     * Prepares a list of document chunks for vector store ingestion with prefixing,
     * while preserving the clean, unprefixed text in metadata for prompt augmentation.
     */
    public List<Document> prepareChunksForIngestion(List<Document> chunks) {
        ModelProfile profile = getActiveProfile();
        boolean usePrefixes = ragProperties.embedding().useAsymmetricPrefixes() && profile.requiresAsymmetricPrefixes();

        List<Document> prepared = new ArrayList<>(chunks.size());
        for (Document chunk : chunks) {
            String cleanText = chunk.getText();
            Map<String, Object> metadata = new HashMap<>(chunk.getMetadata());
            metadata.put("clean_text", cleanText);
            metadata.put("embedding_model", profile.modelName());

            String textForEmbedding = usePrefixes ? prepareDocumentForEmbedding(cleanText) : cleanText;
            Document preparedDoc = new Document(chunk.getId(), textForEmbedding, metadata);
            prepared.add(preparedDoc);
        }

        return prepared;
    }

    /**
     * Strips any asymmetric instruction prefix from document text for prompt augmentation and display.
     */
    public String extractCleanText(Document doc) {
        if (doc.getMetadata().containsKey("clean_text")) {
            return (String) doc.getMetadata().get("clean_text");
        }

        String text = doc.getText();
        ModelProfile profile = getActiveProfile();
        if (profile.requiresAsymmetricPrefixes() && !profile.documentPrefix().isEmpty() && text.startsWith(profile.documentPrefix())) {
            return text.substring(profile.documentPrefix().length()).trim();
        }
        return text;
    }

    public List<ModelProfile> getAvailableProfiles() {
        return new ArrayList<>(supportedProfiles.values());
    }
}
