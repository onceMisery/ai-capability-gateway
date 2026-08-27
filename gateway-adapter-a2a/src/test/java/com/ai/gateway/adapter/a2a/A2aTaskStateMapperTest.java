package com.ai.gateway.adapter.a2a;

import com.ai.gateway.application.agent.AgentModelResultMapper.ModelResult;
import com.ai.gateway.domain.model.A2aTaskContext;
import io.a2a.spec.DataPart;
import io.a2a.spec.Message;
import io.a2a.spec.Part;
import io.a2a.spec.Task;
import io.a2a.spec.TaskState;
import io.a2a.spec.TextPart;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Acceptance tests for the gateway-state → A2A-state mapping (design §3.5).
 *
 * <p>Three properties are load-bearing here and none of them is visible from a test that only
 * checks the {@link TaskState} value: the confirmation token never reaches the wire, a
 * rejection carries no cause a peer could enumerate, and upstream error wording is replaced
 * rather than forwarded. The tests are written against those, not against the enum mapping,
 * which is the easy part.</p>
 *
 * @author cmiracle@163.com
 */
class A2aTaskStateMapperTest {

    private static final Instant NOW = Instant.parse("2026-08-25T10:15:30Z");
    private static final A2aTaskContext TASK = new A2aTaskContext(
            "task-1", "ctx-1", "root-1", 0);

    private final A2aTaskStateMapper mapper =
            new A2aTaskStateMapper(Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void aCompletedResultCarriesTheBusinessDataInExactlyOneArtifact() {
        Task task = mapper.toTask(TASK, new ModelResult(ModelResult.Status.COMPLETED,
                Map.of("orderNo", "SO202607210001", "state", "SHIPPED"), null,
                "provider said something", null, null));

        assertThat(task.getStatus().state()).isEqualTo(TaskState.COMPLETED);
        assertThat(task.getId()).isEqualTo("task-1");
        assertThat(task.getContextId()).isEqualTo("ctx-1");
        assertThat(task.getArtifacts()).hasSize(1);
        assertThat(dataOf(task.getArtifacts().get(0).parts()))
                .containsEntry("status", "completed")
                .containsEntry("data", Map.of("orderNo", "SO202607210001",
                        "state", "SHIPPED"));
    }

    @Test
    void aCompletedResultWithoutDataStillProducesAValidArtifact() {
        Task task = mapper.toTask(TASK, new ModelResult(
                ModelResult.Status.COMPLETED, null, null, null, null, null));

        // Artifact 的 parts 非空是 SDK 断言，data 为 null 时必须落到空映射而不是省略该段。
        assertThat(dataOf(task.getArtifacts().get(0).parts()))
                .containsEntry("data", Map.of());
    }

    @Test
    void aPendingConfirmationIsInputRequiredAndReferencesOnlyTheOperationId() {
        Task task = mapper.toTask(TASK, new ModelResult(
                ModelResult.Status.CONFIRMATION_REQUIRED, null, null,
                "confirm to settle 12345.00", "op-77", NOW.plusSeconds(300)));

        assertThat(task.getStatus().state()).isEqualTo(TaskState.INPUT_REQUIRED);
        // 待确认态没有业务产物：造一个空 Artifact 会被对端读成「有产物但为空」。
        assertThat(task.getArtifacts()).isEmpty();
        assertThat(statusData(task))
                .containsEntry("operationId", "op-77")
                .containsEntry("expiresAt", "2026-08-25T10:20:30Z");
        assertThat(statusData(task).keySet())
                .containsExactly("status", "operationId", "expiresAt");
    }

    @Test
    void theConfirmationPayloadNeverCarriesTheConfirmationToken() {
        Task task = mapper.toTask(TASK, new ModelResult(
                ModelResult.Status.CONFIRMATION_REQUIRED, null, null, null,
                "op-77", NOW.plusSeconds(60)));

        // token 只存在于网关侧的待确认存储：一旦它出现在协议消息里，两阶段写就退化成一阶段。
        assertThat(statusData(task).keySet())
                .noneMatch(key -> key.toLowerCase(java.util.Locale.ROOT).contains("token"));
    }

    @Test
    void aConfirmationTokenHiddenAnywhereInThePayloadFailsClosed() {
        ModelResult pending = new ModelResult(ModelResult.Status.CONFIRMATION_REQUIRED,
                null, null, null, "op-77", NOW.plusSeconds(60));

        // 嵌套、列表内、以及换一种写法（下划线）都必须被同一条断言拦住，
        // 否则这条约束只是「顶层字段名不叫这个」而已。
        assertThatThrownBy(() -> mapper.toConfirmationTask(TASK, pending,
                Map.of("nested", Map.of("confirmationToken", "t-1"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("confirmationToken");
        assertThatThrownBy(() -> mapper.toConfirmationTask(TASK, pending,
                Map.of("items", List.of(Map.of("confirmation_token", "t-1")))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> mapper.toConfirmationTask(TASK, pending,
                Map.of("Confirmation-Token", "t-1")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aConfirmationWithoutAnOperationIdIsRefusedRatherThanEmittedIncomplete() {
        ModelResult incomplete = new ModelResult(ModelResult.Status.CONFIRMATION_REQUIRED,
                null, null, null, null, NOW.plusSeconds(60));

        // 没有 operationId 的待确认响应无法被第二跳引用，会留下一个永远悬挂的任务。
        assertThatThrownBy(() -> mapper.toTask(TASK, incomplete))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("operationId");
    }

    @Test
    void aRedactedSummaryIsIncludedOnlyWhenTheCallerSuppliesOne() {
        ModelResult pending = new ModelResult(ModelResult.Status.CONFIRMATION_REQUIRED,
                null, null, null, "op-77", NOW.plusSeconds(60));

        Task without = mapper.toConfirmationTask(TASK, pending, null);
        Task with = mapper.toConfirmationTask(TASK, pending,
                Map.of("risk", "WRITE_LOW", "action", "settle payment"));

        // 确认摘要的原始形态含接口名与序列化方式，因此「哪些字段可出境」必须是上游的显式决定。
        assertThat(without.getStatus().message()).isNotNull();
        assertThat(statusData(without)).doesNotContainKey("confirmation");
        assertThat(statusData(with)).containsEntry("confirmation",
                Map.of("risk", "WRITE_LOW", "action", "settle payment"));
    }

    @Test
    void aNonConfirmationResultCannotBeForcedThroughTheConfirmationMapping() {
        ModelResult completed = new ModelResult(
                ModelResult.Status.COMPLETED, Map.of(), null, null, null, null);

        assertThatThrownBy(() -> mapper.toConfirmationTask(TASK, completed, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CONFIRMATION_REQUIRED");
    }

    @Test
    void anErrorBecomesFailedRatherThanRejected() {
        Task task = mapper.toTask(TASK, new ModelResult(ModelResult.Status.ERROR,
                null, "CAPABILITY_UNAVAILABLE", null, null, null));

        // 「受理了但没成功」与「不该被受理」对调用方的下一步动作含义完全不同。
        assertThat(task.getStatus().state()).isEqualTo(TaskState.FAILED);
        assertThat(statusData(task)).containsEntry("errorCode", "CAPABILITY_UNAVAILABLE");
    }

    @Test
    void anErrorWithoutACodeStillCarriesAStableCode() {
        Task task = mapper.toTask(TASK, new ModelResult(
                ModelResult.Status.ERROR, null, "  ", null, null, null));

        assertThat(statusData(task))
                .containsEntry("errorCode", A2aTaskStateMapper.FALLBACK_ERROR_CODE);
    }

    @Test
    void upstreamWordingIsReplacedRatherThanForwarded() {
        String upstream = "Dubbo invoke failed: com.acme.OrderService#queryDetail timeout at 10.2.3.4:20880";

        Task task = mapper.toTask(TASK, new ModelResult(ModelResult.Status.ERROR,
                null, "PROVIDER_TIMEOUT", upstream, null, null));

        // Provider 的原始措辞会连带暴露接口名与服务地址，这两样都不得出境。
        assertThat(textOf(task)).isEqualTo("Request failed");
        assertThat(statusData(task).values()).noneMatch(
                value -> value instanceof String text && text.contains("20880"));
    }

    @Test
    void everyRejectionLooksIdenticalRegardlessOfWhyItHappened() {
        Task first = mapper.rejected(TASK);
        Task second = mapper.rejected(new A2aTaskContext(
                "task-1", "ctx-1", "root-1", 3));

        assertThat(first.getStatus().state()).isEqualTo(TaskState.REJECTED);
        assertThat(statusData(first))
                .containsEntry("errorCode", A2aTaskStateMapper.REASON_REJECTED);
        // 注入命中、跳数超限与越权在对端看来必须完全一致，真实原因只写审计。
        assertThat(statusData(second)).isEqualTo(statusData(first));
        assertThat(textOf(second)).isEqualTo(textOf(first));
    }

    @Test
    void authRequiredIsDistinctFromRejectionAndNamesNoCapability() {
        Task task = mapper.authRequired(TASK);

        assertThat(task.getStatus().state()).isEqualTo(TaskState.AUTH_REQUIRED);
        assertThat(statusData(task))
                .containsEntry("errorCode", A2aTaskStateMapper.REASON_AUTH_REQUIRED);
        // 「去完成注册」对调用方是可行动的信息，且不透露任何能力面的存在与否。
        assertThat(statusData(task)).hasSize(2);
    }

    @Test
    void aCandidateHandbackIsInputRequiredAndMustCarrySomething() {
        Task task = mapper.toCandidateTask(TASK, Map.of(
                "candidates", List.of(Map.of("toolRef", "cap_ab12", "summary", "订单查询")),
                "argumentContract", Map.of("orderNo", "string")));

        assertThat(task.getStatus().state()).isEqualTo(TaskState.INPUT_REQUIRED);
        assertThat(statusData(task)).containsKeys("candidates", "argumentContract");
        // 空候选集必须由上游转成明确的「无可用能力」结果，而不是一个语义为空的 INPUT_REQUIRED。
        assertThatThrownBy(() -> mapper.toCandidateTask(TASK, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theStatusMessageIsBoundToTheTaskSoTheSecondHopCanBeCorrelated() {
        Message message = mapper.rejected(TASK).getStatus().message();

        assertThat(message.getRole()).isEqualTo(Message.Role.AGENT);
        assertThat(message.getTaskId()).isEqualTo("task-1");
        assertThat(message.getContextId()).isEqualTo("ctx-1");
    }

    @Test
    void timestampsAndIdentifiersAreDerivedRatherThanRandomSoRetriesAreEquivalent() {
        Task first = mapper.rejected(TASK);
        Task second = mapper.rejected(TASK);

        assertThat(first.getStatus().timestamp()).isEqualTo(second.getStatus().timestamp());
        assertThat(first.getStatus().timestamp().toInstant()).isEqualTo(NOW);
        assertThat(first.getStatus().message().getMessageId())
                .isEqualTo(second.getStatus().message().getMessageId());
    }

    private static String textOf(Task task) {
        return task.getStatus().message().getParts().stream()
                .filter(TextPart.class::isInstance)
                .map(part -> ((TextPart) part).getText())
                .findFirst()
                .orElseThrow();
    }

    private static Map<String, Object> statusData(Task task) {
        return dataOf(task.getStatus().message().getParts());
    }

    private static Map<String, Object> dataOf(List<Part<?>> parts) {
        return parts.stream()
                .filter(DataPart.class::isInstance)
                .map(part -> ((DataPart) part).getData())
                .findFirst()
                .orElseThrow();
    }
}
