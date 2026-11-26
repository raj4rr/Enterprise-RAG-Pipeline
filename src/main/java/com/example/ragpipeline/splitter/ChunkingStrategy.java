package com.example.ragpipeline.splitter;

public enum ChunkingStrategy {
    SENTENCE_AWARE,
    PARAGRAPH_AWARE,
    ADAPTIVE,
    FIXED;

    public static ChunkingStrategy fromString(String val) {
        if (val == null || val.isBlank()) {
            return SENTENCE_AWARE;
        }
        try {
            return ChunkingStrategy.valueOf(val.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return SENTENCE_AWARE;
        }
    }
}
