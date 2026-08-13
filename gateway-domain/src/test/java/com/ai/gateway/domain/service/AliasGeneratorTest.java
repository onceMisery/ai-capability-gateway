package com.ai.gateway.domain.service;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.*;

class AliasGeneratorTest {
    private final AliasGenerator generator = new AliasGenerator();

    @Test
    void shouldGenerateAliasWithCapPrefix() {
        String alias = generator.generate(103L, "order.detail.query", "1.0.0");
        assertThat(alias).startsWith("cap_");
        assertThat(alias).hasSize(20); // "cap_" (4) + 16 chars
    }

    @Test
    void shouldGenerateDeterministicAlias() {
        String alias1 = generator.generate(103L, "order.detail.query", "1.0.0");
        String alias2 = generator.generate(103L, "order.detail.query", "1.0.0");
        assertThat(alias1).isEqualTo(alias2);
    }

    @Test
    void shouldGenerateDifferentAliasForDifferentInputs() {
        String alias1 = generator.generate(103L, "order.detail.query", "1.0.0");
        String alias2 = generator.generate(103L, "order.detail.query", "2.0.0");
        assertThat(alias1).isNotEqualTo(alias2);
    }

    @Test
    void shouldGenerateDifferentAliasForDifferentSnapshots() {
        String alias1 = generator.generate(103L, "order.detail.query", "1.0.0");
        String alias2 = generator.generate(104L, "order.detail.query", "1.0.0");
        assertThat(alias1).isNotEqualTo(alias2);
    }

    @Test
    void shouldOnlyContainValidBase32Characters() {
        String alias = generator.generate(103L, "order.detail.query", "1.0.0");
        String hashPart = alias.substring(4);
        assertThat(hashPart).matches("[A-Z2-7]+");
    }

    @Test
    void shouldGenerateUniqueAliasWhenCollisionExists() {
        String alias = generator.generate(103L, "order.detail.query", "1.0.0");
        // Pass the same alias as existing to force collision resolution
        String longerAlias = generator.generate(103L, "order.detail.query", "1.0.0", Set.of(alias));
        assertThat(longerAlias).startsWith("cap_");
        assertThat(longerAlias).isNotEqualTo(alias);
        assertThat(longerAlias.length()).isGreaterThan(20);
    }

    @Test
    void shouldThrowOnNullCapabilityId() {
        assertThatThrownBy(() -> generator.generate(103L, null, "1.0.0"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldThrowOnNullVersion() {
        assertThatThrownBy(() -> generator.generate(103L, "order.detail.query", null))
                .isInstanceOf(NullPointerException.class);
    }
}
