package com.ai.gateway.domain.service;

import java.util.Set;

/**
 * Normalizes natural-language text for BM25 candidate retrieval
 *
 * <p>The routing pipeline requires text normalization before
 * BM25 retrieval. This includes:</p>
 * <ol>
 * <li>Lowercase conversion — BM25 is case-insensitive by convention.</li>
 * <li>Whitespace trimming — leading/trailing spaces are removed.</li>
 * <li>Basic Chinese/English tokenization — the text is split on
 * non-alphanumeric boundaries. Each Chinese character is treated as
 * an individual token (unigram), since the initial release does not
 * depend on a full segmentation dictionary.</li>
 * <li>Stop-word removal — common English and Chinese stop words are
 * filtered out to reduce noise in BM25 scoring.</li>
 * </ol>
 *
 * <p>This is a simplified implementation suitable for the initial release's
 * 5-10 capability surface. Full Chinese tokenization should use Lucene
 * SmartChineseAnalyzer in the application adapter layer. The tokenizer
 * version, dictionary, and synonym version must be fixed and recorded in
 * the snapshot and evaluation report to avoid unexplainable routing
 * differences across instances or before/after publication.</p>
 *
 * <p>This class is thread-safe: it holds only immutable static data and
 * performs no mutation.</p>
 *
 * @since 0.1.0
 */
public final class TextNormalizer {

    /**
     * Common English stop words to remove after tokenization.
     */
    private static final Set<String> ENGLISH_STOP_WORDS = Set.of(
            "a", "an", "the", "and", "or", "but", "if", "then", "else",
            "of", "at", "by", "for", "with", "about", "against",
            "between", "into", "through", "during", "before", "after",
            "above", "below", "to", "from", "up", "down", "in", "out",
            "on", "off", "over", "under", "again", "further", "once",
            "here", "there", "when", "where", "why", "how", "all", "any",
            "both", "each", "few", "more", "most", "other", "some", "such",
            "no", "nor", "not", "only", "own", "same", "so", "than",
            "too", "very", "can", "will", "just", "should", "now", "is",
            "am", "are", "was", "were", "be", "been", "being", "have",
            "has", "had", "having", "do", "does", "did", "doing", "i",
            "me", "my", "we", "our", "you", "your", "he", "him", "his",
            "she", "her", "it", "its", "they", "them", "their", "this",
            "that", "these", "those", "what", "which", "who", "whom"
    );

    /**
     * Common Chinese stop words (function words) to remove after tokenization.
     */
    private static final Set<String> CHINESE_STOP_WORDS = Set.of(
            "的", "了", "是", "在", "和", "也", "与", "或", "但", "而",
            "对", "把", "被", "让", "使", "给", "为", "于", "以", "由",
            "从", "到", "向", "往", "上", "下", "里", "中", "内", "外",
            "前", "后", "左", "右", "就", "都", "还", "又", "才",
            "只", "刚", "正", "将", "会", "能", "可", "应", "须", "要",
            "想", "该", "此", "那", "这", "些", "个", "们", "我", "你",
            "他", "她", "它", "不", "没", "无", "有", "一", "二",
            "三"
    );

    /**
     * Combined set of all stop words (English + Chinese).
     */
    private static final Set<String> STOP_WORDS;

    static {
        Set<String> combined = new java.util.HashSet<>();
        combined.addAll(ENGLISH_STOP_WORDS);
        combined.addAll(CHINESE_STOP_WORDS);
        STOP_WORDS = Set.copyOf(combined);
    }

    /**
     * Normalizes the given text for BM25 retrieval.
     *
     * <p>The normalization pipeline:</p>
     * <ol>
     * <li>Trim leading and trailing whitespace.</li>
     * <li>Convert to lowercase.</li>
     * <li>Tokenize: split on non-alphanumeric boundaries; each CJK
     * ideograph (Unicode blocks CJK Unified, CJK Compatibility, etc.)
     * is treated as an individual token.</li>
     * <li>Remove stop words.</li>
     * <li>Join remaining tokens with single spaces.</li>
     * </ol>
     *
     * @param text the raw natural-language text; may be null or blank
     * @return the normalized, space-separated token string; empty string if
     * the input is null, blank, or consists entirely of stop words
     */
    public String normalize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        String trimmed = text.trim().toLowerCase();
        if (trimmed.isEmpty()) {
            return "";
        }

        java.util.List<String> tokens = tokenize(trimmed);

        java.util.List<String> filtered = new java.util.ArrayList<>(tokens.size());
        for (String token : tokens) {
            if (!STOP_WORDS.contains(token)) {
                filtered.add(token);
            }
        }

        return String.join(" ", filtered);
    }

    /**
     * Tokenizes the lowercased text into individual tokens.
     *
     * <p>Splitting rules:</p>
     * <ul>
     * <li>Consecutive ASCII alphanumeric characters form a single token
     * (e.g., {@code "order123"} is one token).</li>
     * <li>Each CJK ideograph (U+4E00–U+9FFF, U+3400–U+4DBF,
     * U+F900–U+FAFF, U+20000–U+2A6DF) is an individual token.</li>
     * <li>All other characters act as token delimiters and are discarded.</li>
     * </ul>
     *
     * @param lowercasedText the trimmed, lowercased text
     * @return the list of tokens
     */
    private java.util.List<String> tokenize(String lowercasedText) {
        java.util.List<String> tokens = new java.util.ArrayList<>();
        StringBuilder currentToken = new StringBuilder();

        for (int i = 0; i < lowercasedText.length(); i++) {
            char c = lowercasedText.charAt(i);

            if (isCjkIdeograph(c)) {
                // Flush any pending alphanumeric token
                if (!currentToken.isEmpty()) {
                    tokens.add(currentToken.toString());
                    currentToken.setLength(0);
                }
                // Each CJK character is an individual token
                tokens.add(String.valueOf(c));
            } else if (isAlphanumeric(c)) {
                currentToken.append(c);
            } else {
                // Delimiter: flush pending token
                if (!currentToken.isEmpty()) {
                    tokens.add(currentToken.toString());
                    currentToken.setLength(0);
                }
            }
        }

        // Flush any remaining token
        if (!currentToken.isEmpty()) {
            tokens.add(currentToken.toString());
        }

        return tokens;
    }

    /**
     * Checks whether a character is an ASCII alphanumeric (a-z, 0-9).
     *
     * @param c the character to check
     * @return {@code true} if the character is alphanumeric
     */
    private boolean isAlphanumeric(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }

    /**
     * Checks whether a character is a CJK ideograph.
     *
     * <p>Covers the following Unicode blocks:</p>
     * <ul>
     * <li>CJK Unified Ideographs (U+4E00–U+9FFF)</li>
     * <li>CJK Unified Ideographs Extension A (U+3400–U+4DBF)</li>
     * <li>CJK Compatibility Ideographs (U+F900–U+FAFF)</li>
     * <li>CJK Unified Ideographs Extension B (U+20000–U+2A6DF)</li>
     * </ul>
     *
     * @param c the character to check
     * @return {@code true} if the character is a CJK ideograph
     */
    private boolean isCjkIdeograph(char c) {
        return (c >= '\u4E00' && c <= '\u9FFF')
                || (c >= '\u3400' && c <= '\u4DBF')
                || (c >= '\uF900' && c <= '\uFAFF')
                || (c >= '\uD840' && c <= '\uD87F');
    }
}
