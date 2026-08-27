package com.ai.gateway.adapter.a2a;

import com.ai.gateway.domain.model.A2aTaskContext;
import com.ai.gateway.domain.model.AgentIdentity;
import com.ai.gateway.domain.model.ArgumentBinding;
import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.ErrorCode;
import com.ai.gateway.domain.model.InvocationRequest;
import com.ai.gateway.domain.model.InvocationResult;
import com.ai.gateway.domain.model.Protocol;
import com.ai.gateway.domain.model.ProtocolBinding;
import com.ai.gateway.domain.model.TrustTier;
import com.ai.gateway.domain.model.ValidationReport;
import com.ai.gateway.domain.port.InvocationAdapter;
import com.ai.gateway.domain.port.ManifestRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.a2a.spec.DataPart;
import io.a2a.spec.Message;
import io.a2a.spec.MessageSendConfiguration;
import io.a2a.spec.MessageSendParams;
import io.a2a.spec.Part;
import io.a2a.spec.SendMessageRequest;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;

/**
 * 把远端 Agent 当作一种协议绑定来调用的出站适配器（设计 §3.7）。
 *
 * <p>这是整个 A2A 方案的架构闭环所在：<b>Domain Agent 也只是一种被治理的能力提供者</b>。
 * 有了它，A2A 才从「绕过网关的旁路」变成「网关的第四种协议绑定」——清单依然是事实源，
 * 入参绑定、Principal 注入、结果归一化、脱敏、审计与韧性策略一条都不因对端是 Agent 而跳过。</p>
 *
 * <p><b>出站消息只携带结构化参数，不携带自由文本。</b>网关已经确定性地选出了能力、
 * 绑定完了参数，此时再拼一段自然语言交给远端 Agent 去「理解」，等于把一次已经确定的调用
 * 重新退化成一次语义猜测，而猜错的后果落在业务副作用上。因此请求体是一个
 * {@link DataPart}：{@code skillId} + 具名参数。</p>
 *
 * <p><b>远端返回值是不可信输入。</b>本适配器只做协议层解包与稳定错误码映射，
 * 返回中性的 {@link InvocationResult}；结果的信封判定、投影白名单、字段脱敏、出参 Schema 校验
 * 全部由下游既有链路（{@code ResultNormalizer} / {@code RedactionService}）完成。
 * 这里不做任何「因为对端是受信 Agent 所以可以少校验一层」的让步——脱敏是否执行不应取决于
 * 数据来自哪种协议。</p>
 *
 * <p><b>并发约束落在结构上而不是约定上。</b>A2A SDK 的会话对象（如 {@link Message}，带 setter）
 * 不可跨线程共享，因此本类不缓存任何请求/会话对象：每次调用现场构造。传输出口
 * {@link A2aClientTransport} 是无状态函数，本类因此没有可复用的客户端会话。
 * 另有一个显式并发上限（{@link Semaphore}）：许可耗尽时立刻以 {@link ErrorCode#RATE_LIMITED}
 * 退化，而<b>不排队</b>——出站调用已经处在上游舱壁的截止预算里，在这里排队只会把一个
 * 可以立即回答的拒绝，拖成一次超时。</p>
 *
 * <p><b>先取许可再落审计。</b>顺序反过来的话，一次因许可耗尽而根本没有发生的调用，
 * 会在审计里留下一条 {@code a2a.delegated} 记录；而「审计里有一次不存在的对外委托」
 * 比缺一条记录更难排查。</p>
 *
 * <p>本类不可变且线程安全。</p>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
@Slf4j
public final class A2aInvocationAdapter implements InvocationAdapter {

    /** 出站请求体里承载远端技能标识的字段名。 */
    private static final String FIELD_SKILL_ID = "skillId";

    /** 出站请求体里承载具名参数的字段名。 */
    private static final String FIELD_ARGUMENTS = "arguments";

    /** 审计明细里承载远端技能标识的字段名。 */
    private static final String AUDIT_SKILL_ID = "skillId";

    /** 审计明细里承载远端 Agent 名称的字段名（对端自报，仅作标签）。 */
    private static final String AUDIT_TARGET_AGENT = "targetAgentName";

    /** 远端可接受的产出形态：结构化能力的结果必须能被出参 Schema 校验。 */
    private static final List<String> ACCEPTED_OUTPUT_MODES = List.of("application/json");

    /** 响应体超限时传输层上报的状态码。 */
    private static final int STATUS_PAYLOAD_TOO_LARGE = 413;

    /**
     * 属于「契约不匹配」而非「一时不可用」的 JSON-RPC 错误码。
     *
     * <p>这一组必须映射成不可重试的 {@link ErrorCode#PROVIDER_REJECTED}：方法不存在、
     * 参数非法、操作不受支持这些事实不会因为再试一次而改变，而按可重试处理会把一次
     * 配置错误放大成对远端的持续重试。</p>
     */
    private static final Set<Integer> CONTRACT_ERROR_CODES = Set.of(
            -32601, // method not found
            -32602, // invalid params
            -32001, // task not found
            -32002, // task not cancelable
            -32004, // unsupported operation
            -32005); // content type not supported

    private final ManifestRepository manifestRepository;
    private final A2aAgentEndpointResolver endpointResolver;
    private final A2aClientTransport transport;
    private final A2aTaskAuditRecorder auditRecorder;
    private final ObjectMapper objectMapper;
    private final Semaphore permits;
    private final int maxConcurrency;

    /**
     * @param manifestRepository 已发布清单仓库，不能为 {@code null}
     * @param endpointResolver   远端 Agent 端点解析器，不能为 {@code null}
     * @param transport          出站传输出口，不能为 {@code null}
     * @param auditRecorder      A2A 平面审计出口，不能为 {@code null}
     * @param objectMapper       JSON 编解码器，不能为 {@code null}
     * @param maxConcurrency     出站并发上限，必须为正数
     */
    public A2aInvocationAdapter(ManifestRepository manifestRepository,
                                A2aAgentEndpointResolver endpointResolver,
                                A2aClientTransport transport,
                                A2aTaskAuditRecorder auditRecorder,
                                ObjectMapper objectMapper,
                                int maxConcurrency) {
        this.manifestRepository =
                Objects.requireNonNull(manifestRepository, "manifestRepository must not be null");
        this.endpointResolver =
                Objects.requireNonNull(endpointResolver, "endpointResolver must not be null");
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
        this.auditRecorder = Objects.requireNonNull(auditRecorder, "auditRecorder must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        if (maxConcurrency <= 0) {
            // 上限为 0 意味着「装配好了但一次也调不通」，这种形态只会在压测时暴露，
            // 因此在装配期就拒绝，而不是留给运行期去表现成全量限流。
            throw new IllegalArgumentException("maxConcurrency must be positive");
        }
        this.maxConcurrency = maxConcurrency;
        this.permits = new Semaphore(maxConcurrency);
    }

    @Override
    public Protocol protocol() {
        return Protocol.A2A;
    }

    /**
     * 校验 A2A 协议绑定。
     *
     * <p>其中「{@code registryRef} 必须是引用键而不是地址」这一条是安全约束而非风格约束：
     * 允许清单内联一个 URL，就等于让能力作者可以给网关新增出站目标。</p>
     *
     * @param binding 协议绑定
     * @return 校验报告
     */
    @Override
    public ValidationReport validate(ProtocolBinding binding) {
        if (binding == null) {
            return ValidationReport.failure(List.of("A2A binding must not be null"));
        }
        List<String> errors = new ArrayList<>();
        if (binding.protocol() != Protocol.A2A) {
            errors.add("Protocol must be A2A");
        }
        if (blank(binding.registryRef())) {
            errors.add("A2A agent reference must not be blank");
        } else if (binding.registryRef().contains("://")) {
            errors.add("A2A agent reference must be an operator-configured key, not a URL");
        }
        if (blank(binding.interfaceName())) {
            errors.add("A2A remote agent name must not be blank");
        }
        if (blank(binding.method())) {
            errors.add("A2A skillId must not be blank");
        }
        if (binding.arguments().size() != binding.parameterTypes().size()) {
            errors.add("A2A parameterTypes and arguments must have the same size");
        }
        return errors.isEmpty() ? ValidationReport.success() : ValidationReport.failure(errors);
    }

    /**
     * 向远端 Agent 委托一次调用。
     *
     * @param request 中性调用请求
     * @return 中性调用结果；任何失败都以稳定错误码表达，不透传远端措辞
     */
    @Override
    public InvocationResult invoke(InvocationRequest request) {
        long started = System.currentTimeMillis();
        CapabilityManifest manifest = manifestRepository
                .findByIdAndVersion(request.capabilityId(), request.capabilityVersion())
                .orElse(null);
        if (manifest == null || manifest.spec().invocation() == null) {
            return failure(ErrorCode.CAPABILITY_UNAVAILABLE,
                    "Published manifest not found", started, "CAPABILITY_NOT_FOUND");
        }
        ProtocolBinding binding = manifest.spec().invocation();
        if (!validate(binding).valid()) {
            return failure(ErrorCode.PROTOCOL_ERROR,
                    "A2A binding validation failed", started, "INVALID_BINDING");
        }
        long timeoutMillis = request.deadlineBudget().remainingMs();
        if (timeoutMillis <= 0) {
            // 预算已耗尽时不发起调用：发出去也只能立刻超时，却已经在远端产生了副作用。
            return failure(ErrorCode.PROVIDER_TIMEOUT,
                    "A2A delegation timed out", started, "DEADLINE_EXPIRED");
        }
        URI endpoint;
        try {
            endpoint = endpointResolver.resolve(binding.registryRef());
        } catch (RuntimeException e) {
            return failure(ErrorCode.PROTOCOL_ERROR,
                    "A2A agent endpoint is not configured", started, "ENDPOINT_NOT_CONFIGURED");
        }
        if (!permits.tryAcquire()) {
            return failure(ErrorCode.RATE_LIMITED,
                    "A2A delegation concurrency limit reached", started, "CONCURRENCY_LIMIT");
        }
        try {
            return delegate(request, binding, endpoint, timeoutMillis, started);
        } finally {
            permits.release();
        }
    }

    /**
     * 在已持有并发许可的前提下完成一次委托。
     *
     * <p>审计先于调用，且落库失败即放弃调用：出站调用会在别人的系统上留下副作用，
     * 一次没有痕迹的对外委托在事后无法归因，因此「记不下来就不发」是这里唯一安全的方向。</p>
     */
    private InvocationResult delegate(InvocationRequest request, ProtocolBinding binding,
                                      URI endpoint, long timeoutMillis, long started) {
        String messageId = UUID.randomUUID().toString();
        String skillId = binding.method().trim();
        try {
            auditRecorder.record(delegationEntry(request, binding, endpoint, messageId, skillId));
        } catch (RuntimeException e) {
            log.warn("A2A delegation withheld because the audit sink is unavailable: capability={}",
                    request.capabilityId());
            return failure(ErrorCode.PROTOCOL_ERROR,
                    "A2A delegation was withheld", started, "AUDIT_UNAVAILABLE");
        }
        try {
            String body = objectMapper.writeValueAsString(
                    sendMessageRequest(request, binding, messageId, skillId));
            A2aClientResponse response = transport.send(endpoint, body, timeoutMillis);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return failure(classifyStatus(response.statusCode()),
                        stableStatusMessage(response.statusCode()), started,
                        "HTTP_" + response.statusCode());
            }
            return readResult(response.body(), started);
        } catch (java.net.http.HttpTimeoutException e) {
            // 写操作的不确定性由上游两阶段协议兜底，这里只保证不把超时说成失败。
            return failure(ErrorCode.PROVIDER_TIMEOUT,
                    "A2A delegation timed out", started, "TIMEOUT");
        } catch (java.net.ConnectException e) {
            return failure(ErrorCode.PROVIDER_TIMEOUT,
                    "Remote agent is unavailable", started, "CONNECT_FAILED");
        } catch (Exception e) {
            // 只记异常类型不记消息：远端措辞与内部地址都不得进入日志与响应。
            log.warn("A2A delegation failed: capability={}, reason={}",
                    request.capabilityId(), e.getClass().getSimpleName());
            return failure(ErrorCode.PROTOCOL_ERROR,
                    "A2A delegation failed", started, "DELEGATION_FAILED");
        }
    }

    /**
     * 构造一条出站委托审计事件。
     *
     * <p>设计 §3.8 要求明细里出现 {@code targetAgentDigest}。这里把目标摘要放在
     * {@code AgentIdentity.peerDigest()} 上——它最终落到审计记录的 {@code subjectDigest} 字段，
     * 而不是复制一份进明细：审计出口已经约定「摘要有固定承载位置，两处都放等于多一个泄漏面」。
     * 出站事件的 {@code subjectDigest} 语义因此是「委托目标」而非「调用方」，
     * 二者由 {@code plane=a2a-outbound} 标签区分——这正是入站与出站分成两个平面取值的用途。</p>
     *
     * <p>摘要取自<b>已解析出的端点</b>而不是引用键：同一个键在不同环境指向不同 Agent，
     * 按键聚合会把两个环境的委托记成同一个目标。</p>
     *
     * <p>信任分级恒为 {@link TrustTier#UNTRUSTED}：该字段描述的是「网关授予对端多少可见面」，
     * 而一个出站目标不被授予任何可见面。把它填成受信档会让同一份注册表语义在两个方向上打架。</p>
     */
    private A2aTaskAuditRecorder.Entry delegationEntry(InvocationRequest request,
                                                       ProtocolBinding binding,
                                                       URI endpoint,
                                                       String messageId,
                                                       String skillId) {
        String traceId = request.systemContext().traceId();
        A2aTaskContext taskContext = new A2aTaskContext(messageId, traceId, traceId, 0);
        AgentIdentity target = new AgentIdentity(
                binding.interfaceName(), sha256Hex(endpoint.toString()), TrustTier.UNTRUSTED);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put(AUDIT_SKILL_ID, skillId);
        details.put(AUDIT_TARGET_AGENT, binding.interfaceName());
        return new A2aTaskAuditRecorder.Entry(
                A2aTaskAuditRecorder.EventType.DELEGATED, taskContext, target, null, details);
    }

    /**
     * 构造一次 {@code message/send} 请求。
     *
     * <p>每次调用现场构造：SDK 的 {@link Message} 带 setter（{@code setTaskId}、
     * {@code setContextId}），缓存一个实例复用就等于把可变会话状态暴露给并发。</p>
     *
     * <p>{@code blocking=true} 是结构化调用的必要条件：网关的执行链是同步的，
     * 一个非阻塞的 A2A 调用只会返回一个待轮询的任务号，而它无法被出参 Schema 校验。</p>
     */
    private SendMessageRequest sendMessageRequest(InvocationRequest request,
                                                  ProtocolBinding binding,
                                                  String messageId,
                                                  String skillId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(FIELD_SKILL_ID, skillId);
        payload.put(FIELD_ARGUMENTS,
                namedArguments(binding.arguments(), request.boundArguments()));
        Message message = new Message.Builder()
                .role(Message.Role.USER)
                .messageId(messageId)
                .contextId(request.systemContext().traceId())
                .parts(List.<Part<?>>of(new DataPart(payload)))
                .build();
        MessageSendConfiguration configuration = new MessageSendConfiguration.Builder()
                .acceptedOutputModes(ACCEPTED_OUTPUT_MODES)
                .blocking(Boolean.TRUE)
                .build();
        return new SendMessageRequest(messageId, new MessageSendParams.Builder()
                .message(message)
                .configuration(configuration)
                .build());
    }

    /**
     * 按绑定顺序把位置参数还原成具名参数。
     *
     * <p>远端 Agent 没有本网关的参数序列约定，位置参数在跨进程边界上没有意义；
     * 而具名之后，远端多接一个参数或少接一个参数都会在它自己的校验里暴露，
     * 而不是静默地把第二个参数当成第一个用。</p>
     */
    private static Map<String, Object> namedArguments(List<ArgumentBinding> bindings,
                                                      List<Object> values) {
        if (bindings.size() != values.size()) {
            throw new IllegalArgumentException("A2A argument count does not match binding");
        }
        Map<String, Object> named = new LinkedHashMap<>();
        for (int i = 0; i < bindings.size(); i++) {
            named.put(bindings.get(i).name(), values.get(i));
        }
        return named;
    }

    /**
     * 解包 JSON-RPC 响应。
     *
     * <p>只做协议层解包：取出结构化载荷交给下游归一化与脱敏，不做任何业务判断。</p>
     */
    private InvocationResult readResult(String body, long started)
            throws Exception {
        if (body == null || body.isBlank()) {
            return failure(ErrorCode.PROTOCOL_ERROR,
                    "Remote agent returned an empty response", started, "EMPTY_RESPONSE");
        }
        JsonNode root = objectMapper.readTree(body);
        JsonNode error = root.get("error");
        if (error != null && !error.isNull()) {
            int code = error.path("code").asInt();
            // 远端的 error.message 一律不透传：它是远端的内部措辞，可能包含其拓扑与栈信息。
            return failure(classifyJsonRpcError(code),
                    "Remote agent rejected the delegation", started, "JSONRPC_" + code);
        }
        JsonNode result = root.get("result");
        if (result == null || !result.isObject()) {
            return failure(ErrorCode.PROTOCOL_ERROR,
                    "Remote agent returned an unexpected response shape", started,
                    "MALFORMED_RESULT");
        }
        String kind = result.path("kind").asText("");
        if ("message".equals(kind)) {
            // 远端直接回一条 Message 是 A2A 允许的即时应答形态，按成功处理。
            return dataResult(partList(result.path("parts")), started, "MESSAGE");
        }
        if (!"task".equals(kind)) {
            return failure(ErrorCode.PROTOCOL_ERROR,
                    "Remote agent returned an unexpected response shape", started,
                    "UNKNOWN_RESULT_KIND");
        }
        return readTaskResult(result, started);
    }

    /**
     * 按远端任务终态派生调用结果。
     *
     * <p>非终态（{@code submitted} / {@code working} / {@code unknown}）映射成
     * {@link ErrorCode#EXECUTION_UNKNOWN} 而不是失败：请求已经到达远端、副作用可能已经发生，
     * 报成失败会诱导上游重试，而重试一次可能已发生的写操作是最坏的处理方式。</p>
     *
     * <p>{@code input-required} 对结构化委托而言是契约不匹配：网关送出的参数已经完整绑定，
     * 远端仍要求补充输入，说明双方对该技能的入参约定不一致，重试不会改变这件事。</p>
     */
    private InvocationResult readTaskResult(JsonNode task, long started) {
        String state = task.path("status").path("state").asText("");
        switch (state) {
            case "completed" -> {
                return dataResult(artifactParts(task.path("artifacts")), started, "COMPLETED");
            }
            case "auth-required" -> {
                return failure(ErrorCode.PERMISSION_DENIED,
                        "Remote agent rejected authorization", started, "REMOTE_AUTH_REQUIRED");
            }
            case "input-required" -> {
                return failure(ErrorCode.PROVIDER_REJECTED,
                        "Remote agent rejected the delegation", started, "REMOTE_INPUT_REQUIRED");
            }
            case "failed", "rejected", "canceled" -> {
                return failure(ErrorCode.PROVIDER_REJECTED,
                        "Remote agent rejected the delegation", started,
                        "REMOTE_" + state.toUpperCase(java.util.Locale.ROOT));
            }
            default -> {
                return failure(ErrorCode.EXECUTION_UNKNOWN,
                        "Remote agent did not reach a terminal state", started,
                        "NON_TERMINAL_STATE");
            }
        }
    }

    /**
     * 汇总所有 Artifact 的 Part 序列。
     *
     * <p>不在这里挑选「主要」Artifact：挑选规则一旦存在，同一份清单就会因远端的排版差异
     * 产出不同结果。多个结构化载荷统一在 {@link #dataResult} 里按「歧义」拒绝。</p>
     */
    private static List<JsonNode> artifactParts(JsonNode artifacts) {
        List<JsonNode> parts = new ArrayList<>();
        if (artifacts != null && artifacts.isArray()) {
            for (JsonNode artifact : artifacts) {
                JsonNode artifactParts = artifact.path("parts");
                if (artifactParts.isArray()) {
                    artifactParts.forEach(parts::add);
                }
            }
        }
        return parts;
    }

    /**
     * 从 Part 序列里取出唯一的结构化载荷。
     *
     * <p><b>零个或多个结构化载荷都按协议错误拒绝</b>，而不是「尽力挑一个」：结构化能力的出参
     * 要经过 Schema 校验，一段自由文本无法被校验，而在多个载荷里猜一个「主要结果」意味着
     * 同一份清单会因远端排版差异产出不同结果——两种情形都应当在协议层被挡住，
     * 而不是让一个来源不确定的载荷继续往下游走。</p>
     *
     * <p>返回值只经过 JSON 树到 Java 容器的转换，<b>不做任何内容判定</b>：
     * 远端 Agent 的返回值是不可信输入，脱敏与出参校验由下游既有链路负责。</p>
     */
    private InvocationResult dataResult(List<JsonNode> parts, long started,
                                        String protocolStatus) {
        List<JsonNode> dataParts = new ArrayList<>();
        for (JsonNode part : parts) {
            if ("data".equals(part.path("kind").asText(""))) {
                dataParts.add(part);
            }
        }
        if (dataParts.isEmpty()) {
            return failure(ErrorCode.PROTOCOL_ERROR,
                    "Remote agent returned no structured result", started,
                    "RESULT_NOT_STRUCTURED");
        }
        if (dataParts.size() > 1) {
            return failure(ErrorCode.PROTOCOL_ERROR,
                    "Remote agent returned an ambiguous result", started, "RESULT_AMBIGUOUS");
        }
        JsonNode data = dataParts.get(0).path("data");
        if (!data.isObject() && !data.isArray()) {
            return failure(ErrorCode.PROTOCOL_ERROR,
                    "Remote agent returned no structured result", started,
                    "RESULT_NOT_STRUCTURED");
        }
        return new InvocationResult(objectMapper.convertValue(data, Object.class),
                protocolStatus, null, null,
                Map.of("durationMs", elapsed(started)));
    }

    /** 把 JSON 数组节点摊成可遍历的 Part 列表；非数组视为空。 */
    private static List<JsonNode> partList(JsonNode arrayNode) {
        List<JsonNode> parts = new ArrayList<>();
        if (arrayNode != null && arrayNode.isArray()) {
            arrayNode.forEach(parts::add);
        }
        return parts;
    }

    /**
     * @return 配置的出站并发上限，供装配期日志与运维核对
     */
    public int maxConcurrency() {
        return maxConcurrency;
    }

    /** 把 JSON-RPC 错误码映射成稳定错误码；契约类错误不可重试，其余按协议错误处理。 */
    private static ErrorCode classifyJsonRpcError(int code) {
        return CONTRACT_ERROR_CODES.contains(code)
                ? ErrorCode.PROVIDER_REJECTED
                : ErrorCode.PROTOCOL_ERROR;
    }

    /** 把传输层状态码映射成稳定错误码，口径与 REST 适配器一致。 */
    private static ErrorCode classifyStatus(int status) {
        if (status == 408 || status == 504) {
            return ErrorCode.PROVIDER_TIMEOUT;
        }
        if (status == 429) {
            return ErrorCode.RATE_LIMITED;
        }
        if (status == 401 || status == 403) {
            return ErrorCode.PERMISSION_DENIED;
        }
        if (status == STATUS_PAYLOAD_TOO_LARGE) {
            return ErrorCode.RESULT_TOO_LARGE;
        }
        return status >= 400 && status < 500
                ? ErrorCode.PROVIDER_REJECTED
                : ErrorCode.PROTOCOL_ERROR;
    }

    /** 稳定对外措辞：远端与内部的原始报错一律不透传。 */
    private static String stableStatusMessage(int status) {
        if (status == 408 || status == 504) {
            return "A2A delegation timed out";
        }
        if (status == 429) {
            return "Remote agent rate limit reached";
        }
        if (status == 401 || status == 403) {
            return "Remote agent rejected authorization";
        }
        if (status == STATUS_PAYLOAD_TOO_LARGE) {
            return "Remote agent response exceeded the size limit";
        }
        return status >= 500
                ? "Remote agent returned a server error"
                : "Remote agent rejected the delegation";
    }

    /**
     * 构造失败结果。
     *
     * <p>{@code reason} 只进入调用元数据供运维归因，不进入 {@code errorMessage}：
     * 对外措辞必须是稳定的一组常量，否则调用方会开始依赖这些内部原因码。</p>
     */
    private static InvocationResult failure(ErrorCode code, String message,
                                           long started, String reason) {
        return new InvocationResult(null, "ERROR", code, message,
                Map.of("durationMs", elapsed(started), "reason", reason));
    }

    private static String elapsed(long started) {
        return String.valueOf(System.currentTimeMillis() - started);
    }

    /** 计算 SHA-256 十六进制摘要；{@link AgentIdentity} 只接受这种形态。 */
    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 强制实现的算法，走到这里说明运行环境已不可信。
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}

