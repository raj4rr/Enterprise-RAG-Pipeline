package com.example.ragpipeline.splitter;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Intelligent text splitter that chunks documents with configurable sliding-window overlap
 * while respecting word and sentence boundaries for optimal semantic embedding quality.
 */
@Component
public class OverlappingTextSplitter {

    /**
     * Splits plain text into chunks with specified chunk size and overlap.
     *
     * @param text         Source text
     * @param chunkSize    Target chunk size in characters (e.g. 500)
     * @param chunkOverlap Overlap between consecutive chunks in characters (e.g. 100)
     * @return List of text chunks
     */
    public List<String> splitText(String text, int chunkSize, int chunkOverlap) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }

        // Normalize line breaks
        String normalized = text.replace("\r\n", "\n").trim();
        if (normalized.length() <= chunkSize) {
            chunks.add(normalized);
            return chunks;
        }

        // Sanity check: overlap must be strictly smaller than chunkSize
        int safeOverlap = Math.max(0, Math.min(chunkOverlap, chunkSize - 20));
        int step = chunkSize - safeOverlap;

        int start = 0;
        int textLength = normalized.length();

        while (start < textLength) {
            int end = Math.min(start + chunkSize, textLength);

            // If not at the end of text, find a natural boundary near end (sentence or space)
            if (end < textLength) {
                int naturalBoundary = findNaturalBoundary(normalized, end, start);
                if (naturalBoundary > start) {
                    end = naturalBoundary;
                }
            }

            String chunk = normalized.substring(start, end).trim();
            if (!chunk.isBlank()) {
                chunks.add(chunk);
            }

            if (end >= textLength) {
                break;
            }

            // Move forward by step, but adjust to previous boundary to maintain overlap
            int nextStart = end - safeOverlap;
            if (nextStart <= start) {
                nextStart = start + step;
            }
            start = nextStart;
        }

        return chunks;
    }

    /**
     * Splits a Spring AI Document into multiple Document chunks with metadata.
     */
    public List<Document> splitDocument(Document document, int chunkSize, int chunkOverlap) {
        String content = document.getText();
        List<String> textChunks = splitText(content, chunkSize, chunkOverlap);
        List<Document> chunkDocuments = new ArrayList<>();

        int totalChunks = textChunks.size();
        for (int i = 0; i < totalChunks; i++) {
            String chunkContent = textChunks.get(i);
            Map<String, Object> metadata = new HashMap<>(document.getMetadata());
            metadata.put("chunk_index", i);
            metadata.put("total_chunks", totalChunks);
            metadata.put("chunk_size", chunkSize);
            metadata.put("chunk_overlap", chunkOverlap);
            metadata.put("char_count", chunkContent.length());
            metadata.put("word_count", chunkContent.split("\\s+").length);

            Document chunkDoc = new Document(chunkContent, metadata);
            chunkDocuments.add(chunkDoc);
        }

        return chunkDocuments;
    }

    /**
     * Attempts to find a natural break (period, newline, or whitespace) near the cut-off index.
     */
    private int findNaturalBoundary(String text, int targetIndex, int startIndex) {
        int searchRange = Math.min(60, (targetIndex - startIndex) / 2);

        // Check for sentence endings (. ? !) followed by space or newline
        for (int i = targetIndex; i >= targetIndex - searchRange && i > startIndex; i--) {
            char c = text.charAt(i - 1);
            if ((c == '.' || c == '?' || c == '!') && (i == text.length() || Character.isWhitespace(text.charAt(i)))) {
                return i;
            }
        }

        // Check for newlines
        for (int i = targetIndex; i >= targetIndex - searchRange && i > startIndex; i--) {
            if (text.charAt(i - 1) == '\n') {
                return i;
            }
        }

        // Check for word break (space)
        for (int i = targetIndex; i >= targetIndex - searchRange && i > startIndex; i--) {
            if (Character.isWhitespace(text.charAt(i - 1))) {
                return i;
            }
        }

        return targetIndex;
    }
}
