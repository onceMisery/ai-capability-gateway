package com.ai.gateway.adapter.llm;

import com.ai.gateway.domain.model.ModelDecision;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmResponseParserTest {

    private final LlmResponseParser parser = new LlmResponseParser();

    @Test
    void parsesExactSelectShape() {
        ModelDecision decision = parser.parse(
                "{\"decision\":\"SELECT\",\"alias\":\"cap_1\",\"arguments\":{\"id\":7}}");

        assertThat(decision).isEqualTo(
                new ModelDecision.SelectDecision("cap_1", java.util.Map.of("id", 7)));
    }

    @Test
    void rejectsUnknownTopLevelField() {
        assertThatThrownBy(() -> parser.parse(
                "{\"decision\":\"SELECT\",\"alias\":\"cap_1\",\"arguments\":{},\"binding\":\"secret\"}"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("unexpected field");
    }

    @Test
    void rejectsWrongFieldTypes() {
        assertThatThrownBy(() -> parser.parse(
                "{\"decision\":\"SELECT\",\"alias\":7,\"arguments\":[]}"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("alias");
    }

    @Test
    void rejectsNoMatchWithoutReasonCode() {
        assertThatThrownBy(() -> parser.parse("{\"decision\":\"NO_MATCH\"}"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("reasonCode");
    }

    @Test
    void rejectsTrailingJsonDocument() {
        assertThatThrownBy(() -> parser.parse(
                "{\"decision\":\"NO_MATCH\",\"reasonCode\":\"NONE\"} {}"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("valid JSON");
    }
}
