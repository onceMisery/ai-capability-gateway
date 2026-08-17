package com.ai.gateway.domain.service;

import com.ai.gateway.domain.model.PayloadLimits;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PayloadTreeGuard 的边界和 UTF-8 字节预算测试。 */
class PayloadTreeGuardTest {

    private PayloadLimits limits(long maxBytes,
                                 int maxDepth,
                                 int maxCollectionLength,
                                 int maxObjectFields,
                                 int maxStringBytes,
                                 long maxNodes) {
        return new PayloadLimits(maxBytes, maxBytes, maxDepth,
                maxCollectionLength, maxObjectFields, maxStringBytes, maxNodes);
    }

    @Test
    void acceptsExactUtf8BoundaryAndRejectsChineseOverflow() {
        Object value = Map.of("x", "中");
        PayloadTreeGuard guard = new PayloadTreeGuard(limits(
                11, 4, 10, 10, 10, 20));

        assertThatCode(() -> guard.validateInput(value)).doesNotThrowAnyException();
        assertThatThrownBy(() -> new PayloadTreeGuard(limits(
                10, 4, 10, 10, 10, 20)).validateInput(value))
                .isInstanceOf(PayloadLimitExceededException.class)
                .hasMessageContaining("JSON bytes exceed 10");
    }

    @Test
    void rejectsDepthBeforeJavaStackCanOverflow() {
        Map<String, Object> value = Map.of("child", Map.of("child", Map.of("value", "ok")));

        assertThatThrownBy(() -> new PayloadTreeGuard(limits(
                1024, 1, 10, 10, 10, 100)).validateInput(value))
                .isInstanceOf(PayloadLimitExceededException.class)
                .hasMessageContaining("JSON depth exceeds 1");
    }

    @Test
    void rejectsCollectionFieldsStringAndNodeBudgets() {
        assertThatThrownBy(() -> new PayloadTreeGuard(limits(
                1024, 4, 2, 10, 10, 100)).validateInput(List.of(1, 2, 3)))
                .isInstanceOf(PayloadLimitExceededException.class)
                .hasMessageContaining("array length exceeds 2");
        assertThatThrownBy(() -> new PayloadTreeGuard(limits(
                1024, 4, 10, 1, 10, 100)).validateInput(Map.of("a", 1, "b", 2)))
                .isInstanceOf(PayloadLimitExceededException.class)
                .hasMessageContaining("object fields exceed 1");
        assertThatThrownBy(() -> new PayloadTreeGuard(limits(
                1024, 4, 10, 10, 2, 100)).validateInput(Map.of("x", "中文")))
                .isInstanceOf(PayloadLimitExceededException.class)
                .hasMessageContaining("string exceeds 2");
        assertThatThrownBy(() -> new PayloadTreeGuard(limits(
                1024, 4, 10, 10, 10, 2)).validateInput(List.of(1, 2)))
                .isInstanceOf(PayloadLimitExceededException.class)
                .hasMessageContaining("node count exceeds 2");
    }
}
