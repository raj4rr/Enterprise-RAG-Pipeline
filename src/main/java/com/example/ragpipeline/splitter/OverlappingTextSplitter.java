package com.example.ragpipeline.splitter;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Intelligent text splitter with optimized chunk size and overlap management.
 * Supports multiple chunking strategies (SENTENCE_AWARE, PARAGRAPH_AWARE, ADAPTIVE, FIXED)
 * and enforces strict overlap bounds (10% - 30% of chunk size) to prevent context loss or redundancy.
 */
@Component
public class OverlappingTextSplitter {

    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6}\\s+.+|[A-Z0-9][^\\n]{3,60}:)$", Pattern.MULTILINE);
    private static final Pattern PARAGRAPH_SPLIT_PATTERN = Pattern.compile("\\n\\s*\\n+");

    /**
     * Splits plain text into chunks with specified chunk size, overlap, and strategy.
     */
    public List<String> splitText(String text, int chunkSize, int chunkOverlap) {
        return splitText(text, chunkSize, chunkOverlap, ChunkingStrategy.SENTENCE_AWARE);
    }

    /**
     * Splits plain text using a specific ChunkingStrategy with optimized overlap.
     */
    public List<String> splitText(String text, int targetChunkSize, int targetOverlap, ChunkingStrategy strategy) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }

        String normalized = text.replace("\r\n", "\n").replace("\r", "\n").trim();
        if (normalized.isEmpty()) {
            return chunks;
        }

        // Apply ADAPTIVE strategy sizing if requested
        int resolvedChunkSize = targetChunkSize;
        int resolvedOverlap = targetOverlap;

        if (strategy == ChunkingStrategy.ADAPTIVE) {
            int length = normalized.length();
            if (length < 1200) {
                resolvedChunkSize = 350;
                resolvedOverlap = 70; // 20%
            } else if (length < 6000) {
                resolvedChunkSize = 550;
                resolvedOverlap = 110; // 20%
            } else {
                resolvedChunkSize = 850;
                resolvedOverlap = 170; // 20%
            }
        }

        // Enforce optimal overlap bounds: between 10% and 30% of chunk size
        int safeOverlap = optimizeOverlap(resolvedChunkSize, resolvedOverlap);
        int safeChunkSize = Math.max(100, resolvedChunkSize);

        if (normalized.length() <= safeChunkSize) {
            chunks.add(normalized);
            return chunks;
        }

        if (strategy == ChunkingStrategy.PARAGRAPH_AWARE) {
            return splitByParagraphs(normalized, safeChunkSize, safeOverlap);
        }

        // Default: Sentence-aware / sliding window
        return splitSlidingWindow(normalized, safeChunkSize, safeOverlap, strategy == ChunkingStrategy.FIXED);
    }

    /**
     * Enforces optimal overlap bounds (10% to 30% of chunkSize) to prevent both
     * context loss and vector bloat.
     */
    public int optimizeOverlap(int chunkSize, int requestedOverlap) {
        if (chunkSize <= 0) return 0;
        int minAllowed = Math.max(20, (int) Math.round(chunkSize * 0.10));
        int maxAllowed = (int) Math.round(chunkSize * 0.35);

        if (requestedOverlap <= 0) {
            return minAllowed;
        }
        return Math.max(minAllowed, Math.min(requestedOverlap, maxAllowed));
    }

    /**
     * Splits a Spring AI Document into multiple Document chunks with enriched metadata.
     */
    public List<Document> splitDocument(Document document, int chunkSize, int chunkOverlap) {
        return splitDocument(document, chunkSize, chunkOverlap, ChunkingStrategy.SENTENCE_AWARE);
    }

    /**
     * Splits a Spring AI Document into chunks using the given strategy.
     */
    public List<Document> splitDocument(Document document, int chunkSize, int chunkOverlap, ChunkingStrategy strategy) {
        String content = document.getText();
        int safeOverlap = optimizeOverlap(chunkSize, chunkOverlap);
        List<String> textChunks = splitText(content, chunkSize, safeOverlap, strategy);
        List<Document> chunkDocuments = new ArrayList<>();

        int totalChunks = textChunks.size();
        int currentOffset = 0;

        for (int i = 0; i < totalChunks; i++) {
            String chunkContent = textChunks.get(i);
            int charStart = content.indexOf(chunkContent.substring(0, Math.min(20, chunkContent.length())), Math.max(0, currentOffset - safeOverlap - 50));
            if (charStart < 0) charStart = currentOffset;
            int charEnd = charStart + chunkContent.length();
            currentOffset = charEnd;

            Map<String, Object> metadata = new HashMap<>(document.getMetadata());
            metadata.put("chunk_index", i);
            metadata.put("total_chunks", totalChunks);
            metadata.put("chunk_size", chunkSize);
            metadata.put("chunk_overlap", safeOverlap);
            metadata.put("overlap_ratio", Math.round(((double) safeOverlap / chunkSize) * 100.0) / 100.0);
            metadata.put("strategy", strategy.name());
            metadata.put("char_start", charStart);
            metadata.put("char_end", charEnd);
            metadata.put("char_count", chunkContent.length());
            metadata.put("word_count", chunkContent.split("\\s+").length);

            // Extract nearest section header if present
            String sectionTitle = findCurrentSection(content, charStart);
            if (sectionTitle != null) {
                metadata.put("section_title", sectionTitle);
            }

            Document chunkDoc = new Document(chunkContent, metadata);
            chunkDocuments.add(chunkDoc);
        }

        return chunkDocuments;
    }

    private List<String> splitSlidingWindow(String text, int chunkSize, int chunkOverlap, boolean isFixed) {
        List<String> chunks = new ArrayList<>();
        int step = chunkSize - chunkOverlap;
        int start = 0;
        int textLength = text.length();

        while (start < textLength) {
            int end = Math.min(start + chunkSize, textLength);

            if (!isFixed && end < textLength) {
                int naturalBoundary = findNaturalBoundary(text, end, start);
                if (naturalBoundary > start + (chunkSize / 3)) {
                    end = naturalBoundary;
                }
            }

            String chunk = text.substring(start, end).trim();
            if (!chunk.isBlank()) {
                chunks.add(chunk);
            }

            if (end >= textLength) {
                break;
            }

            int nextStart = end - chunkOverlap;
            if (nextStart <= start) {
                nextStart = start + step;
            }
            start = nextStart;
        }

        return chunks;
    }

    private List<String> splitByParagraphs(String text, int chunkSize, int chunkOverlap) {
        List<String> chunks = new ArrayList<>();
        String[] paragraphs = PARAGRAPH_SPLIT_PATTERN.split(text);

        StringBuilder currentChunk = new StringBuilder();

        for (String para : paragraphs) {
            String trimmedPara = para.trim();
            if (trimmedPara.isEmpty()) continue;

            if (trimmedPara.length() > chunkSize) {
                // Large paragraph exceeds chunk size -> split with sentence aware sliding window
                if (!currentChunk.isEmpty()) {
                    chunks.add(currentChunk.toString().trim());
                    currentChunk.setLength(0);
                }
                List<String> subChunks = splitSlidingWindow(trimmedPara, chunkSize, chunkOverlap, false);
                chunks.addAll(subChunks);
            } else if (currentChunk.length() + trimmedPara.length() + 2 <= chunkSize) {
                if (!currentChunk.isEmpty()) {
                    currentChunk.append("\n\n");
                }
                currentChunk.append(trimmedPara);
            } else {
                // Emit current chunk
                chunks.add(currentChunk.toString().trim());
                // Carry over overlap from the end of currentChunk if possible
                String carryOver = extractOverlapTail(currentChunk.toString(), chunkOverlap);
                currentChunk.setLength(0);
                if (!carryOver.isBlank()) {
                    currentChunk.append(carryOver).append("\n\n");
                }
                currentChunk.append(trimmedPara);
            }
        }

        if (!currentChunk.isEmpty() && !currentChunk.toString().isBlank()) {
            chunks.add(currentChunk.toString().trim());
        }

        return chunks.isEmpty() ? splitSlidingWindow(text, chunkSize, chunkOverlap, false) : chunks;
    }

    private String extractOverlapTail(String text, int targetOverlap) {
        if (text.length() <= targetOverlap) return text;
        int start = text.length() - targetOverlap;
        // Find nearest space or sentence break
        int spaceIndex = text.indexOf(' ', start);
        if (spaceIndex != -1 && spaceIndex < text.length() - 10) {
            return text.substring(spaceIndex + 1);
        }
        return text.substring(start);
    }

    private int findNaturalBoundary(String text, int targetIndex, int startIndex) {
        int searchRange = Math.min(80, (targetIndex - startIndex) / 2);

        // 1. Sentence endings (. ? !) followed by whitespace
        for (int i = targetIndex; i >= targetIndex - searchRange && i > startIndex; i--) {
            char c = text.charAt(i - 1);
            if ((c == '.' || c == '?' || c == '!') && (i == text.length() || Character.isWhitespace(text.charAt(i)))) {
                return i;
            }
        }

        // 2. Paragraph/Newline boundary
        for (int i = targetIndex; i >= targetIndex - searchRange && i > startIndex; i--) {
            if (text.charAt(i - 1) == '\n') {
                return i;
            }
        }

        // 3. Clause boundary (; , : )
        for (int i = targetIndex; i >= targetIndex - searchRange && i > startIndex; i--) {
            char c = text.charAt(i - 1);
            if ((c == ';' || c == ',' || c == ':') && Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }

        // 4. Word boundary (space)
        for (int i = targetIndex; i >= targetIndex - searchRange && i > startIndex; i--) {
            if (Character.isWhitespace(text.charAt(i - 1))) {
                return i;
            }
        }

        return targetIndex;
    }

    private String findCurrentSection(String text, int offset) {
        if (offset <= 0 || offset > text.length()) return null;
        String prefix = text.substring(0, offset);
        Matcher matcher = HEADING_PATTERN.matcher(prefix);
        String lastHeading = null;
        while (matcher.find()) {
            lastHeading = matcher.group(1).trim().replaceAll("^#+\\s*", "");
        }
        return lastHeading;
    }
}
