package com.ai.gateway.domain.service;

import com.ai.gateway.domain.model.RedactionMethod;
import com.ai.gateway.domain.model.RedactionRule;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class RedactionServiceTest {
    private final RedactionService service = new RedactionService();

    @Test
    void shouldApplyPartialMask() {
        Map<String, Object> data = new HashMap<>();
        data.put("customerName", "Zhang San");
        List<RedactionRule> rules = List.of(new RedactionRule("/customerName", RedactionMethod.PARTIAL_MASK));
        Object result = service.redact(data, rules);
        assertThat(result).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        String masked = (String) map.get("customerName");
        assertThat(masked).isNotEqualTo("Zhang San");
        assertThat(masked).contains("*");
        // "Zhang San" (9 chars) -> keep first 2 "Zh" + mask 5 "*****" + keep last 2 "an"
        assertThat(masked).isEqualTo("Zh*****an");
    }

    @Test
    void shouldFullyMaskShortString() {
        Map<String, Object> data = new HashMap<>();
        data.put("code", "ab");
        List<RedactionRule> rules = List.of(new RedactionRule("/code", RedactionMethod.PARTIAL_MASK));
        Object result = service.redact(data, rules);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        String masked = (String) map.get("code");
        // 2 chars <= 4 (prefix+suffix), fully masked
        assertThat(masked).isEqualTo("**");
    }

    @Test
    void shouldApplyHash() {
        Map<String, Object> data = new HashMap<>();
        data.put("phone", "13800138000");
        List<RedactionRule> rules = List.of(new RedactionRule("/phone", RedactionMethod.HASH));
        Object result = service.redact(data, rules);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        String hashed = (String) map.get("phone");
        assertThat(hashed).isNotEqualTo("13800138000");
        assertThat(hashed).hasSize(64); // SHA-256 hex
        assertThat(hashed).matches("[0-9a-f]{64}");
    }

    @Test
    void shouldApplyDelete() {
        Map<String, Object> data = new HashMap<>();
        data.put("secret", "sensitive");
        data.put("public", "visible");
        List<RedactionRule> rules = List.of(new RedactionRule("/secret", RedactionMethod.DELETE));
        Object result = service.redact(data, rules);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        assertThat(map.containsKey("secret")).isFalse();
        assertThat(map.get("public")).isEqualTo("visible");
    }

    @Test
    void shouldHandleNonExistentPath() {
        Map<String, Object> data = new HashMap<>();
        data.put("name", "test");
        List<RedactionRule> rules = List.of(new RedactionRule("/nonExistent", RedactionMethod.DELETE));
        Object result = service.redact(data, rules);
        assertThat(result).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        assertThat(map.get("name")).isEqualTo("test");
    }

    @Test
    void shouldApplyMultipleRulesInOrder() {
        Map<String, Object> data = new HashMap<>();
        data.put("name", "Alice");
        data.put("email", "alice@example.com");
        List<RedactionRule> rules = List.of(
                new RedactionRule("/name", RedactionMethod.PARTIAL_MASK),
                new RedactionRule("/email", RedactionMethod.HASH)
        );
        Object result = service.redact(data, rules);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        assertThat((String) map.get("name")).contains("*");
        assertThat((String) map.get("email")).hasSize(64);
    }

    @Test
    void shouldHandleNestedPath() {
        Map<String, Object> inner = new HashMap<>();
        inner.put("ssn", "123-45-6789");
        Map<String, Object> data = new HashMap<>();
        data.put("person", inner);
        List<RedactionRule> rules = List.of(new RedactionRule("/person/ssn", RedactionMethod.HASH));
        Object result = service.redact(data, rules);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        @SuppressWarnings("unchecked")
        Map<String, Object> person = (Map<String, Object>) map.get("person");
        assertThat((String) person.get("ssn")).hasSize(64);
    }

    @Test
    void shouldThrowOnNullData() {
        assertThatThrownBy(() -> service.redact(null, List.of()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldThrowOnNullRules() {
        assertThatThrownBy(() -> service.redact(Map.of("key", "value"), null))
                .isInstanceOf(NullPointerException.class);
    }
}
