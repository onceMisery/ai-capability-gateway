package com.ai.gateway.domain.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class TextNormalizerTest {
    private final TextNormalizer normalizer = new TextNormalizer();

    @Test
    void shouldLowercaseText() {
        String result = normalizer.normalize("Hello World");
        assertThat(result).doesNotContain("Hello");
        assertThat(result).contains("hello");
        assertThat(result).contains("world");
    }

    @Test
    void shouldTrimWhitespace() {
        String result = normalizer.normalize(" hello ");
        assertThat(result).doesNotStartWith(" ");
        assertThat(result).doesNotEndWith(" ");
    }

    @Test
    void shouldHandleEmptyInput() {
        String result = normalizer.normalize("");
        assertThat(result).isNotNull();
        assertThat(result).isEmpty();
    }

    @Test
    void shouldHandleNullInput() {
        String result = normalizer.normalize(null);
        assertThat(result).isNotNull();
        assertThat(result).isEmpty();
    }

    @Test
    void shouldHandleBlankInput() {
        String result = normalizer.normalize(" ");
        assertThat(result).isEmpty();
    }

    @Test
    void shouldRemoveEnglishStopWords() {
        String result = normalizer.normalize("the order and the payment");
        assertThat(result).doesNotContain("the");
        assertThat(result).doesNotContain("and");
        assertThat(result).contains("order");
        assertThat(result).contains("payment");
    }

    @Test
    void shouldTokenizeCjkCharacters() {
        String result = normalizer.normalize("查询订单");
        // Each CJK character is an individual token; stop words removed
        assertThat(result).isNotNull();
        assertThat(result).isNotEmpty();
    }

    @Test
    void shouldSplitOnNonAlphanumericBoundaries() {
        String result = normalizer.normalize("order.detail.query");
        assertThat(result).contains("order");
        assertThat(result).contains("detail");
        assertThat(result).contains("query");
    }

    @Test
    void shouldReturnEmptyForAllStopWords() {
        String result = normalizer.normalize("the a an is are");
        assertThat(result).isEmpty();
    }
}
