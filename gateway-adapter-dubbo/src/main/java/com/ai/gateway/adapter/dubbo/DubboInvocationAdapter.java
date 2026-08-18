package com.ai.gateway.adapter.dubbo;

import com.ai.gateway.domain.model.AttachmentWhitelist;
import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.DeadlineBudget;
import com.ai.gateway.domain.model.ErrorCode;
import com.ai.gateway.domain.model.InvocationRequest;
import com.ai.gateway.domain.model.InvocationResult;
import com.ai.gateway.domain.model.Protocol;
import com.ai.gateway.domain.model.ProtocolBinding;
import com.ai.gateway.domain.model.ValidationReport;
import com.ai.gateway.domain.port.InvocationAdapter;
import com.ai.gateway.domain.port.ManifestRepository;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.service.GenericService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 实现 {@link InvocationAdapter} 的 Dubbo 泛化调用适配器。
 *
 * <p>该适配器使用 Apache Dubbo 的 {@link GenericService} 调用目标能力，而无需在
 * 运行时加载任何业务 API JAR。方法名和参数类型名来自已发布 Manifest 的
 * {@link ProtocolBinding}。适配器不会调用 {@code Class.forName}——所有类型信息
 * 仅以字符串形式存在。</p>
 *
 * <p>调用流程如下：</p>
 * <ol>
 * <li>按 capabilityId + version 查找已发布的 Manifest，获取
 * {@link ProtocolBinding}。</li>
 * <li>从 {@link DubboReferenceManager} 获取或创建 {@link GenericService}。</li>
 * <li>使用 {@link GenericArgumentBuilder} 构建泛化参数。</li>
 * <li>使用 {@link DubboAttachmentManager} 构建 Dubbo 附件。</li>
 * <li>在 {@link RpcContext} 上设置附件，并调用
 * {@code genericService.$invoke(method, parameterTypes, arguments)}。</li>
 * <li>使用 {@link GenericResultStripper} 从结果中剥离协议元数据键。</li>
 * <li>返回包含 JSON 兼容数据的 {@link InvocationResult}。</li>
 * </ol>
 *
 * <p>适配器不得执行自然语言路由、用户授权或能力状态变更。</p>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
@Component
public class DubboInvocationAdapter implements InvocationAdapter {

    private static final Logger log = LoggerFactory.getLogger(DubboInvocationAdapter.class);

    private final DubboReferenceManager referenceManager;
    private final GenericArgumentBuilder argumentBuilder;
    private final GenericResultStripper resultStripper;
    private final DubboAttachmentManager attachmentManager;
    private final ManifestRepository manifestRepository;

    /**
     * 构造一个新的 DubboInvocationAdapter。
     *
     * @param referenceManager Dubbo 引用缓存管理器
     * @param argumentBuilder 泛化参数构建器
     * @param resultStripper 协议元数据剥离器
     * @param attachmentManager 附件白名单管理器
     * @param manifestRepository 用于查找已发布 Manifest 的仓库
     */
    public DubboInvocationAdapter(DubboReferenceManager referenceManager,
                                  GenericArgumentBuilder argumentBuilder,
                                  GenericResultStripper resultStripper,
                                  DubboAttachmentManager attachmentManager,
                                  ManifestRepository manifestRepository) {
        this.referenceManager = Objects.requireNonNull(referenceManager,
                "referenceManager must not be null");
        this.argumentBuilder = Objects.requireNonNull(argumentBuilder,
                "argumentBuilder must not be null");
        this.resultStripper = Objects.requireNonNull(resultStripper,
                "resultStripper must not be null");
        this.attachmentManager = Objects.requireNonNull(attachmentManager,
                "attachmentManager must not be null");
        this.manifestRepository = Objects.requireNonNull(manifestRepository,
                "manifestRepository must not be null");
        log.info("DubboInvocationAdapter initialized");
    }

    @Override
    public Protocol protocol() {
        return Protocol.DUBBO;
    }

    /**
     * 校验 Dubbo 协议绑定在结构、语义与安全合规性方面是否通过。
     *
     * <p>校验内容包括：</p>
     * <ul>
     * <li>协议为 {@link Protocol#DUBBO}。</li>
     * <li>{@code registryRef} 存在（引用预配置的注册中心）。</li>
     * <li>{@code serialization} 属于平台白名单。</li>
     * <li>{@code parameterTypes} 与参数位置一一对应。</li>
     * <li>{@code group} 和 {@code version} 存在，用于服务定位。</li>
     * </ul>
     *
     * @param binding 待校验的协议绑定
     * @return 校验报告；仅当 errors 为空时校验通过
     */
    @Override
    public ValidationReport validate(ProtocolBinding binding) {
        Objects.requireNonNull(binding, "binding must not be null");

        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // 协议必须为 DUBBO
        if (binding.protocol() != Protocol.DUBBO) {
            errors.add("Protocol must be DUBBO, got: " + binding.protocol());
        }

        // registryRef 必须存在
        if (binding.registryRef() == null || binding.registryRef().isBlank()) {
            errors.add("registryRef must not be null or blank");
        }

        // serialization 必须在白名单内
        if (binding.serialization() == null) {
            errors.add("serialization must not be null");
        } else if (!SerializationWhitelist.isAllowed(binding.serialization())) {
            errors.add("Serialization '" + binding.serialization()
                    + "' is not in the platform whitelist. Allowed: "
                    + SerializationWhitelist.allowedValues()
                    + "");
        }

        // group 和 version 应存在，用于服务定位
        if (binding.group() == null || binding.group().isBlank()) {
            warnings.add("group is null or blank — Dubbo default group will be used");
        }
        if (binding.version() == null || binding.version().isBlank()) {
            warnings.add("version is null or blank — Dubbo default version will be used");
        }

        // parameterTypes 必须与参数一一对应
        if (binding.parameterTypes().size() != binding.arguments().size()) {
            errors.add("parameterTypes count (" + binding.parameterTypes().size()
                    + ") does not match arguments count (" + binding.arguments().size()
                    + ") — must correspond one-to-one");
        }

        if (errors.isEmpty()) {
            log.debug("Dubbo binding validation passed: interface={}, method={}",
                    binding.interfaceName(), binding.method());
            return ValidationReport.success();
        } else {
            log.warn("Dubbo binding validation failed: {} error(s)", errors.size());
            return new ValidationReport(false, errors, warnings);
        }
    }

    /**
     * 使用 Dubbo 泛化调用方式调用目标能力。
     *
     * <p>适配器查找已发布的 Manifest 以获取 {@link ProtocolBinding}，构建泛化参数，
     * 设置附件，调用 {@code genericService.$invoke}，从结果中剥离协议元数据，
     * 并返回 JSON 兼容的 {@link InvocationResult}。</p>
     *
     * @param request 与协议无关的调用请求
     * @return 与协议无关的调用结果；永不为 null
     */
    @Override
    public InvocationResult invoke(InvocationRequest request) {
        Objects.requireNonNull(request, "request must not be null");

        long startTime = System.currentTimeMillis();
        String capabilityId = request.capabilityId();
        String capabilityVersion = request.capabilityVersion();

        log.debug("Dubbo invocation starting: capability={}, version={}",
                capabilityId, capabilityVersion);

        // 步骤 1：查找已发布的 Manifest，获取 ProtocolBinding
        Optional<CapabilityManifest> manifestOpt =
                manifestRepository.findByIdAndVersion(capabilityId, capabilityVersion);
        if (manifestOpt.isEmpty()) {
            log.error("Published manifest not found: capability={}, version={}",
                    capabilityId, capabilityVersion);
            return new InvocationResult(
                    null,
                    "ERROR",
                    ErrorCode.CAPABILITY_UNAVAILABLE,
                    "Published manifest not found for capability: "
                            + capabilityId + " version: " + capabilityVersion,
                    Map.of("durationMs", String.valueOf(
                            System.currentTimeMillis() - startTime))
            );
        }

        CapabilityManifest manifest = manifestOpt.get();
        ProtocolBinding binding = manifest.spec().invocation();

        // 步骤 2：校验绑定
        ValidationReport validation = validate(binding);
        if (!validation.valid()) {
            log.error("Protocol binding validation failed: {}", validation.errors());
            return new InvocationResult(
                    null,
                    "ERROR",
                    ErrorCode.PROTOCOL_ERROR,
                    "Protocol binding validation failed: " + validation.errors(),
                    Map.of("durationMs", String.valueOf(
                            System.currentTimeMillis() - startTime))
            );
        }

        // 步骤 3：从 DubboReferenceManager 获取 GenericService
        GenericService genericService;
        try {
            genericService = referenceManager.getOrCreate(
                    binding.registryRef(),
                    binding.interfaceName(),
                    binding.group(),
                    binding.version(),
                    binding.serialization());
        } catch (Exception e) {
            log.error("Failed to get GenericService: {}", e.getMessage());
            return new InvocationResult(
                    null,
                    "ERROR",
                    ErrorCode.PROTOCOL_ERROR,
                    "Failed to obtain Dubbo reference: " + e.getMessage(),
                    Map.of("durationMs", String.valueOf(
                            System.currentTimeMillis() - startTime),
                            "interface", binding.interfaceName())
            );
        }

        // 步骤 4：使用 GenericArgumentBuilder 构建泛化参数
        Object[] argumentValues;
        try {
            argumentValues = argumentBuilder.buildArguments(
                    request.boundArguments(), binding.parameterTypes());
        } catch (Exception e) {
            log.error("Failed to build generic arguments: {}", e.getMessage());
            return new InvocationResult(
                    null,
                    "ERROR",
                    ErrorCode.ARGUMENT_VALIDATION_FAILED,
                    "Failed to build generic arguments: " + e.getMessage(),
                    Map.of("durationMs", String.valueOf(
                            System.currentTimeMillis() - startTime))
            );
        }

        // 步骤 5：构建附件并设置到 RpcContext
        Map<String, String> attachments = attachmentManager.buildAttachments(
                request.systemContext(), new AttachmentWhitelist());

        // 应用请求截止时间预算中的 deadline
        DeadlineBudget deadlineBudget = request.deadlineBudget();
        if (!deadlineBudget.isExpired()) {
            attachments.putIfAbsent("deadline",
                    String.valueOf(System.currentTimeMillis() + deadlineBudget.remainingMs()));
        }

        // 调用前将附件设置到 RpcContext
        try {
            RpcContext.getClientAttachment().setAttachments(attachments);
        } catch (Exception e) {
            log.warn("Failed to set Dubbo attachments: {}", e.getMessage());
            // 继续执行——附件属于尽力而为的上下文传播
        }

        // 步骤 6：调用 genericService.$invoke
        referenceManager.incrementInFlight();
        Object rawResult;
        try {
            String[] parameterTypeNames = binding.parameterTypes().toArray(new String[0]);
            rawResult = genericService.$invoke(
                    binding.method(),
                    parameterTypeNames,
                    argumentValues);
        } catch (org.apache.dubbo.rpc.RpcException e) {
            log.error("Dubbo RpcException: code={}, message={}",
                    e.getCode(), e.getMessage());
            ErrorCode errorCode = classifyRpcException(e);
            return new InvocationResult(
                    null,
                    "RPC_ERROR",
                    errorCode,
                    "Dubbo RPC error: " + e.getMessage(),
                    Map.of("durationMs", String.valueOf(
                            System.currentTimeMillis() - startTime),
                            "method", binding.method(),
                            "rpcCode", String.valueOf(e.getCode()))
            );
        } catch (Exception e) {
            log.error("Dubbo invocation failed: {}", e.getMessage());
            return new InvocationResult(
                    null,
                    "ERROR",
                    ErrorCode.PROVIDER_REJECTED,
                    "Dubbo invocation failed: " + e.getMessage(),
                    Map.of("durationMs", String.valueOf(
                            System.currentTimeMillis() - startTime),
                            "method", binding.method())
            );
        } finally {
            referenceManager.decrementInFlight();
            // 调用完成后清理 RpcContext 附件
            try {
                RpcContext.getClientAttachment().clearAttachments();
            } catch (Exception e) {
                log.debug("Failed to clear RpcContext attachments", e);
            }
        }

        long durationMs = System.currentTimeMillis() - startTime;

        // 步骤 7：从结果中剥离协议元数据键
        // 必须在 Envelope 判定、投影和 Schema 校验之前调用
        Object strippedResult = resultStripper.strip(rawResult);

        log.info("Dubbo invocation completed: capability={}, method={}, durationMs={}",
                capabilityId, binding.method(), durationMs);

        // 步骤 8：返回包含 JSON 兼容数据的 InvocationResult
        return new InvocationResult(
                strippedResult,
                "OK",
                null,
                null,
                Map.of(
                        "durationMs", String.valueOf(durationMs),
                        "interface", binding.interfaceName(),
                        "method", binding.method()
                )
        );
    }

    /**
     * 将 Dubbo RpcException 归类为对应的 ErrorCode。
     *
     * <p>Dubbo RpcException 错误码：</p>
     * <ul>
     * <li>1（TIMEOUT_EXCEPTION）→ PROVIDER_TIMEOUT</li>
     * <li>2（NETWORK_EXCEPTION）→ PROVIDER_TIMEOUT（可重试）</li>
     * <li>3（FORBIDDEN_EXCEPTION）→ PERMISSION_DENIED</li>
     * <li>BIZ_EXCEPTION → PROVIDER_REJECTED</li>
     * <li>5（METHOD_NOT_FOUND）→ PROTOCOL_ERROR</li>
     * <li>其他 → PROTOCOL_ERROR</li>
     * </ul>
     *
     * @param e Dubbo RpcException
     * @return 对应的 ErrorCode
     */
    private ErrorCode classifyRpcException(RpcException e) {
        return switch (e.getCode()) {
            case RpcException.TIMEOUT_EXCEPTION,
                 RpcException.NETWORK_EXCEPTION ->
                    ErrorCode.PROVIDER_TIMEOUT;
            case RpcException.FORBIDDEN_EXCEPTION ->
                    ErrorCode.PERMISSION_DENIED;
            case RpcException.BIZ_EXCEPTION ->
                    ErrorCode.PROVIDER_REJECTED;
            default -> {
                log.warn("Unclassified Dubbo RpcException code: {}", e.getCode());
                yield ErrorCode.PROTOCOL_ERROR;
            }
        };
    }
}
