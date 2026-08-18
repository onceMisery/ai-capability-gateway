package com.ai.gateway.adapter.dubbo;

import com.ai.gateway.domain.model.AttachmentWhitelist;
import com.ai.gateway.domain.model.SystemContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Dubbo 泛化调用的契约与兼容性测试。
 *
 * <p>单元测试嵌套类在无运行 Provider 的情况下测试协议安全的组件
 * （{@link GenericResultStripper}、{@link GenericArgumentBuilder}、
 * {@link SerializationWhitelist}、{@link DubboAttachmentManager}）。真实的
 * GenericService 调用由 Failsafe 阶段的 {@link DubboGenericInvocationIT} 单独覆盖。</p>
 *
 * @author cmiracle@163.com
 */
class DubboContractTest {

    @Nested
    @DisplayName("GenericResultStripper")
    class GenericResultStripperTests {

        private final GenericResultStripper stripper = new GenericResultStripper();

        @Test
        @DisplayName("Strips class and @type keys recursively from maps")
        void stripsMetadataKeysRecursively() {
            Map<String, Object> lineItem = new LinkedHashMap<>();
            lineItem.put("class", "com.example.LineItem");
            lineItem.put("sku", "SKU-1");

            Map<String, Object> order = new LinkedHashMap<>();
            order.put("class", "com.example.Order");
            order.put("@type", "Order");
            order.put("id", "A100");
            order.put("items", List.of(lineItem));

            Object result = stripper.strip(order);

            assertThat(result).isInstanceOf(Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> cleaned = (Map<String, Object>) result;
            assertThat(cleaned).doesNotContainKeys("class", "@type");
            assertThat(cleaned).containsEntry("id", "A100");
            List<?> items = (List<?>) cleaned.get("items");
            assertThat(items).hasSize(1);
            @SuppressWarnings("unchecked")
            Map<String, Object> innerCleaned = (Map<String, Object>) items.get(0);
            assertThat(innerCleaned)
                    .doesNotContainKeys("class", "@type")
                    .containsEntry("sku", "SKU-1");
        }

        @Test
        @DisplayName("Returns null for null input")
        void returnsNullForNull() {
            assertThat(stripper.strip(null)).isNull();
        }

        @Test
        @DisplayName("Returns primitive values as-is")
        void returnsPrimitivesAsIs() {
            assertThat(stripper.strip(42)).isEqualTo(42);
            assertThat(stripper.strip("plain")).isEqualTo("plain");
            assertThat(stripper.strip(true)).isEqualTo(true);
        }

        @Test
        @DisplayName("Recursively strips object arrays but leaves primitive arrays")
        void handlesArrays() {
            Object[] objectArray = {Map.of("class", "x", "v", 1), 2};
            Object cleaned = stripper.strip(objectArray);
            assertThat(cleaned).isInstanceOf(Object[].class);
            @SuppressWarnings("unchecked")
            Map<String, Object> first = (Map<String, Object>) ((Object[]) cleaned)[0];
            assertThat(first).doesNotContainKey("class");

            int[] primitiveArray = {1, 2, 3};
            assertThat(stripper.strip(primitiveArray)).isSameAs(primitiveArray);
        }
    }

    @Nested
    @DisplayName("GenericArgumentBuilder")
    class GenericArgumentBuilderTests {

        private final GenericArgumentBuilder builder = new GenericArgumentBuilder();

        @Test
        @DisplayName("Passes simple types through as-is")
        void passesSimpleTypesAsIs() {
            Object[] args = builder.buildArguments(
                    List.<Object>of("hello", 42),
                    List.of("java.lang.String", "int"));
            assertThat(args).containsExactly("hello", 42);
        }

        @Test
        @DisplayName("Wraps POJO maps with class metadata from the protocol type")
        void wrapsPojoWithClassMetadata() {
            Object[] args = builder.buildArguments(
                    List.<Object>of(Map.of("orderId", "A100")),
                    List.of("com.example.Order"));
            assertThat(args[0]).isInstanceOf(Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> generic = (Map<String, Object>) args[0];
            assertThat(generic)
                    .containsEntry("class", "com.example.Order")
                    .containsEntry("orderId", "A100");
        }

        @Test
        @DisplayName("Removes reserved class/@type keys injected by user or model")
        void removesReservedKeysFromModelOutput() {
            Map<String, Object> modelOutput = new LinkedHashMap<>();
            modelOutput.put("class", "com.evil.Injected");
            modelOutput.put("@type", "Injected");
            modelOutput.put("orderId", "A100");

            Object[] args = builder.buildArguments(
                    List.<Object>of(modelOutput),
                    List.of("com.example.Order"));
            @SuppressWarnings("unchecked")
            Map<String, Object> generic = (Map<String, Object>) args[0];
            assertThat(generic)
                    .containsEntry("class", "com.example.Order")
                    .containsEntry("orderId", "A100")
                    .doesNotContainKey("@type");
        }

        @Test
        @DisplayName("Passes arrays and null values through")
        void passesArraysAndNulls() {
            Object[] args = builder.buildArguments(
                    java.util.Arrays.asList(new String[]{"a", "b"}, null),
                    List.of("java.lang.String[]", "com.example.Pojo"));
            assertThat((String[]) args[0]).containsExactly("a", "b");
            assertThat(args[1]).isNull();
        }

        @Test
        @DisplayName("Rejects argument/type count mismatch")
        void rejectsCountMismatch() {
            assertThatThrownBy(() -> builder.buildArguments(
                    List.<Object>of("a"),
                    List.of("int", "long")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("does not match");
        }
    }

    @Nested
    @DisplayName("SerializationWhitelist")
    class SerializationWhitelistTests {

        @Test
        @DisplayName("Allows only platform-maintained serializations")
        void allowsStandardSerializations() {
            assertThat(SerializationWhitelist.isAllowed("hessian2")).isTrue();
            assertThat(SerializationWhitelist.isAllowed("fastjson2")).isTrue();
            assertThat(SerializationWhitelist.isAllowed("custom-internal")).isFalse();
            assertThat(SerializationWhitelist.isAllowed(null)).isFalse();
        }

        @Test
        @DisplayName("validate rejects unknown and null serializations")
        void validateRejectsUnknownAndNull() {
            assertThatThrownBy(() -> SerializationWhitelist.validate("custom-internal"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not in the platform whitelist");
            assertThatThrownBy(() -> SerializationWhitelist.validate(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("allowedValues is immutable")
        void allowedValuesIsImmutable() {
            assertThat(SerializationWhitelist.allowedValues()).contains("hessian2", "fastjson2");
            assertThatThrownBy(() -> SerializationWhitelist.allowedValues().add("x"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("DubboAttachmentManager")
    class DubboAttachmentManagerTests {

        private final DubboAttachmentManager manager = new DubboAttachmentManager();

        @Test
        @DisplayName("Builds only whitelisted attachments from system context")
        void buildsWhitelistedAttachments() {
            SystemContext ctx = new SystemContext("trace-1", 1234L, "key-1", "zh-CN");
            Map<String, String> attachments =
                    manager.buildAttachments(ctx, new AttachmentWhitelist());

            assertThat(attachments)
                    .containsEntry("traceId", "trace-1")
                    .containsEntry("deadline", "1234")
                    .containsEntry("locale", "zh-CN")
                    .containsEntry("b3-traceid", "trace-1")
                    .containsEntry("rtid", "trace-1")
                    .doesNotContainKey("delegatedToken");
            assertThat(attachments.get("b3-spanid")).matches("[0-9a-f]{16}");
            assertThat(attachments.keySet()).allMatch(AttachmentWhitelist::isAllowed);
        }

        @Test
        @DisplayName("Rejects null system context or whitelist")
        void rejectsNullArguments() {
            SystemContext ctx = new SystemContext("trace-1", 1L, null, "zh-CN");
            assertThatThrownBy(() -> manager.buildAttachments(null, new AttachmentWhitelist()))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> manager.buildAttachments(ctx, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

}
