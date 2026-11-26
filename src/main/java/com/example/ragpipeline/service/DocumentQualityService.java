package com.example.ragpipeline.service;

import com.example.ragpipeline.config.RagProperties;
import com.example.ragpipeline.dto.DocumentQualityReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Evaluates, cleans, and improves document quality prior to chunking and embedding.
 * Prevents garbage ingestion, strips noise and boilerplate, normalizes typography,
 * and attaches quality metrics to document metadata.
 */
@Service
public class DocumentQualityService {

    private static final Logger log = LoggerFactory.getLogger(DocumentQualityService.class);

    private final RagProperties ragProperties;

    private static final Pattern HTML_SCRIPT_PATTERN = Pattern.compile("<(script|style)[^>]*?>.*?</\\1>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern HTML_TAG_PATTERN = Pattern.compile("<[^>]+>");
    private static final Pattern REPEATED_SYMBOLS_PATTERN = Pattern.compile("[-=_*~#]{4,}");
    private static final Pattern MULTI_NEWLINES_PATTERN = Pattern.compile("\\n{3,}");
    private static final Pattern MULTI_SPACES_PATTERN = Pattern.compile("[ \\t]{2,}");
    private static final Pattern HYPHENATED_BREAK_PATTERN = Pattern.compile("(\\b\\w+)-\\n(\\w+\\b)");
    private static final Pattern CONTROL_CHARS_PATTERN = Pattern.compile("[\\p{Cntrl}&&[^\r\n\t]]");
    private static final Pattern TITLE_PATTERN = Pattern.compile("^(?:#+\\s*|Title:\\s*)([^\n]+)", Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    public DocumentQualityService(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
    }

    /**
     * Inspects, cleans, and generates a DocumentQualityReport for raw text.
     */
    public DocumentQualityReport inspectAndClean(String rawText, String sourceName) {
        if (rawText == null || rawText.isBlank()) {
            return new DocumentQualityReport(
                    false,
                    0.0,
                    "EMPTY",
                    0,
                    0,
                    0,
                    0.0,
                    1.0,
                    List.of("Document text is empty or blank"),
                    "",
                    sourceName
            );
        }

        List<String> detectedIssues = new ArrayList<>();
        int originalLength = rawText.length();

        // 1. Text Normalization & Cleaning
        String cleaned = rawText;

        // Unicode normalization (NFKC)
        cleaned = Normalizer.normalize(cleaned, Normalizer.Form.NFKC);

        // Remove non-printable control characters
        int beforeControlClean = cleaned.length();
        cleaned = CONTROL_CHARS_PATTERN.matcher(cleaned).replaceAll("");
        if (cleaned.length() < beforeControlClean) {
            detectedIssues.add("Non-printable control characters stripped");
        }

        if (ragProperties.quality().stripBoilerplate()) {
            // Strip HTML scripts & styles
            cleaned = HTML_SCRIPT_PATTERN.matcher(cleaned).replaceAll(" ");
            // Strip orphan HTML tags
            cleaned = HTML_TAG_PATTERN.matcher(cleaned).replaceAll(" ");
            // Strip excessive decorative separator dividers
            cleaned = REPEATED_SYMBOLS_PATTERN.matcher(cleaned).replaceAll("\n---\n");
        }

        // Fix broken hyphenations across line breaks
        cleaned = HYPHENATED_BREAK_PATTERN.matcher(cleaned).replaceAll("$1$2");

        // Normalize whitespace and newlines
        cleaned = cleaned.replace("\r\n", "\n").replace("\r", "\n");
        cleaned = MULTI_SPACES_PATTERN.matcher(cleaned).replaceAll(" ");
        cleaned = MULTI_NEWLINES_PATTERN.matcher(cleaned).replaceAll("\n\n");
        cleaned = cleaned.trim();

        // 2. Metrics calculation
        int charCount = cleaned.length();
        String[] words = cleaned.isEmpty() ? new String[0] : cleaned.split("\\s+");
        int wordCount = words.length;

        Set<String> uniqueWords = new HashSet<>();
        for (String w : words) {
            String lower = w.toLowerCase(Locale.ROOT).replaceAll("^[^a-z0-9]+|[^a-z0-9]+$", "");
            if (!lower.isEmpty()) {
                uniqueWords.add(lower);
            }
        }
        int uniqueWordCount = uniqueWords.size();
        double lexicalDiversity = wordCount > 0 ? (double) uniqueWordCount / wordCount : 0.0;

        // Calculate noise ratio: ratio of characters removed or non-alphanumeric noise
        double lengthReductionRatio = originalLength > 0 ? (double) Math.abs(originalLength - charCount) / originalLength : 0.0;
        int nonAlphaChars = 0;
        for (int i = 0; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);
            if (!Character.isLetterOrDigit(c) && !Character.isWhitespace(c)) {
                nonAlphaChars++;
            }
        }
        double symbolDensity = charCount > 0 ? (double) nonAlphaChars / charCount : 0.0;
        double noiseRatio = Math.min(1.0, (lengthReductionRatio * 0.4) + (symbolDensity * 0.6));

        // 3. Document Quality Score (0.0 to 1.0)
        double score = 1.0;

        if (wordCount < 10) {
            score -= 0.50;
            detectedIssues.add("Document is extremely short (< 10 words)");
        } else if (wordCount < 30) {
            score -= 0.20;
            detectedIssues.add("Document is short (< 30 words)");
        }

        if (lexicalDiversity < 0.20 && wordCount >= 10) {
            score -= 0.65;
            detectedIssues.add("Severely low lexical diversity (repetitive text or boilerplate detected)");
        } else if (lexicalDiversity < 0.30 && wordCount >= 20) {
            score -= 0.35;
            detectedIssues.add("Low lexical diversity (repetitive text or boilerplate detected)");
        } else if (lexicalDiversity < 0.40 && wordCount > 30) {
            score -= 0.15;
            detectedIssues.add("Moderate lexical diversity");
        }

        if (symbolDensity > 0.35) {
            score -= 0.30;
            detectedIssues.add("High symbol-to-text density (possible unparsed binary/table noise)");
        }

        // Clamp score between 0.0 and 1.0
        score = Math.max(0.0, Math.min(1.0, Math.round(score * 100.0) / 100.0));

        // Quality status category
        String status;
        if (score >= 0.85) {
            status = "PRISTINE";
        } else if (score >= 0.70) {
            status = "GOOD";
        } else if (score >= ragProperties.quality().minQualityScore()) {
            status = "ACCEPTABLE";
        } else {
            status = "POOR_QUALITY";
            detectedIssues.add("Quality score " + score + " is below minimum threshold " + ragProperties.quality().minQualityScore());
        }

        boolean accepted = score >= ragProperties.quality().minQualityScore() && wordCount >= 3;

        // Try extracting title from content
        String extractedTitle = sourceName;
        Matcher titleMatcher = TITLE_PATTERN.matcher(cleaned);
        if (titleMatcher.find()) {
            String candidateTitle = titleMatcher.group(1).trim();
            if (candidateTitle.length() > 3 && candidateTitle.length() < 120) {
                extractedTitle = candidateTitle;
            }
        }

        return new DocumentQualityReport(
                accepted,
                score,
                status,
                charCount,
                wordCount,
                uniqueWordCount,
                Math.round(lexicalDiversity * 1000.0) / 1000.0,
                Math.round(noiseRatio * 1000.0) / 1000.0,
                detectedIssues,
                cleaned,
                extractedTitle
        );
    }

    /**
     * Cleans an existing Spring AI Document and enriches it with quality metadata.
     */
    public Document cleanAndEnrichDocument(Document document) {
        String source = String.valueOf(document.getMetadata().getOrDefault("source", "Document"));
        DocumentQualityReport report = inspectAndClean(document.getText(), source);

        Map<String, Object> enrichedMeta = new HashMap<>(document.getMetadata());
        enrichedMeta.put("quality_score", report.qualityScore());
        enrichedMeta.put("quality_status", report.status());
        enrichedMeta.put("quality_accepted", report.accepted());
        enrichedMeta.put("word_count", report.wordCount());
        enrichedMeta.put("character_count", report.characterCount());
        enrichedMeta.put("lexical_diversity", report.lexicalDiversity());
        if (report.extractedTitle() != null && !report.extractedTitle().isBlank()) {
            enrichedMeta.put("document_title", report.extractedTitle());
        }

        return new Document(report.cleanedText(), enrichedMeta);
    }
}
