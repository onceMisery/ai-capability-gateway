package com.ai.gateway.example.demo;

import com.ai.gateway.example.client.GatewayApiClient;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 演示控制面（control plane）管理流程（设计文档 §15.3）。
 *
 * <p>本示例展示完整的能力生命周期：
 * <ol>
 * <li>导入能力清单（Capability Manifest）</li>
 * <li>触发校验（10 步流水线）</li>
 * <li>审批已校验的能力</li>
 * <li>发布到某环境（生成不可变快照）</li>
 * <li>下线某个能力（应急）</li>
 * <li>回滚到历史快照</li>
 * </ol>
 *
 * <p>控制面生命周期确保任何能力在上生产前都必须经过完整的治理流水线：</p>
 * <pre>
 * DRAFT -> IMPORTED -> VALIDATED -> APPROVED -> PUBLISHED
 * |
 * SUSPENDED
 * </pre>
 *
 * <p>关键设计约束：</p>
 * <ul>
 * <li>10 步校验流水线在导入时执行，也可经由 validate 接口重新触发。</li>
 * <li>审批要求具备授权的审批者身份。</li>
 * <li>发布会生成不可变、带版本的快照，其内容在创建后不可修改。</li>
 * <li>下线是一项应急操作，会立即从活动快照中移除该能力。</li>
 * </ul>
 *
 * <p>前置条件：
 * <ul>
 * <li>网关运行于 http://localhost:8080</li>
 * <li>具备控制面权限的管理面 JWT Token</li>
 * </ul>
 *
 * <p>运行方式：
 * <pre>{@code
 * java -cp gateway-example.jar com.ai.gateway.example.demo.AdminWorkflowDemo
 * }</pre>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
@Slf4j
public class AdminWorkflowDemo {

    private static final String DEFAULT_BASE_URL = "http://localhost:8080";
    private static final String DEFAULT_TOKEN = "admin-jwt-token";

    /** 本示例使用的 capability ID。 */
    private static final String DEMO_CAPABILITY_ID = "order.detail.query";

    /** 本示例使用的 capability 版本。 */
    private static final String DEMO_CAPABILITY_VERSION = "1.0.0";

    /**
     * 管理流程示例的入口方法。
     *
     * <p>接受可选的命令行参数：</p>
     * <ul>
     * <li>args[0] — 网关基础 URL（默认：http://localhost:8080）</li>
     * <li>args[1] — 管理面 JWT Bearer Token（默认：admin-jwt-token）</li>
     * </ul>
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        String baseUrl = args.length > 0 ? args[0] : DEFAULT_BASE_URL;
        String token = args.length > 1 ? args[1] : DEFAULT_TOKEN;

        System.out.println("=".repeat(70));
        System.out.println("AI Capability Gateway — Admin Workflow Demo");
        System.out.println("设计文档 / ");
        System.out.println("=".repeat(70));
        System.out.println("Gateway URL: " + baseUrl);
        System.out.println();

        GatewayApiClient client = new GatewayApiClient(baseUrl, token);

        // 步骤 1：导入能力清单
        stepImportManifest(client);

        // 步骤 2：触发校验（10 步流水线）
        stepValidateCapability(client);

        // 步骤 3：审批已校验的能力
        stepApproveCapability(client);

        // 步骤 4：发布到某环境
        stepPublishRelease(client);

        // 步骤 5：下线某个能力（应急）
        stepSuspendCapability(client);

        // 步骤 6：回滚到历史快照
        stepRollback(client);

        System.out.println();
        System.out.println("=".repeat(70));
        System.out.println("Admin workflow demo complete.");
        System.out.println("=".repeat(70));
    }

    /**
     * 步骤 1：导入能力清单。
     *
     * <p>从 classpath 加载示例 {@code order-detail-query.yaml} 并提交到网关的导入接口。</p>
     *
     * <p>导入会触发 10 步校验流水线：</p>
     * <ol>
     * <li>针对带版本的 Manifest Schema 进行 JSON Schema 校验</li>
     * <li>能力 ID 格式检查（domain.resource.action）</li>
     * <li>语义化版本格式检查</li>
     * <li>入参 Schema 安全约束（additionalProperties: false）</li>
     * <li>参数绑定一致性检查</li>
     * <li>序列化白名单检查</li>
     * <li>出参契约校验（envelope、projection、redaction）</li>
     * <li>韧性策略边界检查</li>
     * <li>语义描述完整性（正向/负向/同义词）</li>
     * <li>内容摘要计算（SHA-256）</li>
     * </ol>
     *
     * @param client 网关 API 客户端
     */
    private static void stepImportManifest(GatewayApiClient client) {
        printStep(1, "Import Capability Manifest");

        String manifestYaml = loadClasspathResource("/manifests/order-detail-query.yaml");
        if (manifestYaml == null) {
            System.out.println(" [SKIP] Could not load manifest from classpath");
            return;
        }

        System.out.println(" Manifest: order-detail-query.yaml");
        System.out.println(" Capability ID: " + DEMO_CAPABILITY_ID);
        System.out.println(" Version: " + DEMO_CAPABILITY_VERSION);

        try {
            Map<String, Object> result = client.importManifest(manifestYaml);

            String status = (String) result.get("status");
            System.out.println(" Status: " + status);

            if ("IMPORTED".equals(status)) {
                System.out.println(" Manifest Digest: " + result.get("manifestDigest"));
                System.out.println(" Validation: " + result.get("validationReport"));
            } else if ("REJECTED".equals(status)) {
                System.out.println(" Error: " + result.get("error"));
                System.out.println(" Validation: " + result.get("validationReport"));
            } else {
                System.out.println(" Full response: " + result);
            }
        } catch (GatewayApiClient.GatewayApiException e) {
            System.out.println(" [ERROR] " + e.getMessage());
            log.warn("Manifest import failed", e);
        }

        System.out.println();
    }

    /**
     * 步骤 2：触发校验。
     *
     * <p>重新校验一个已存在的清单版本。当校验规则更新、需要对存量清单重新检查时很有用。</p>
     *
     * @param client 网关 API 客户端
     */
    private static void stepValidateCapability(GatewayApiClient client) {
        printStep(2, "Validate Capability (10-step pipeline)");

        try {
            Map<String, Object> result = client.validateCapability(
                    DEMO_CAPABILITY_ID, DEMO_CAPABILITY_VERSION);

            String status = (String) result.get("status");
            System.out.println(" Status: " + status);
            System.out.println(" Validation Report: " + result.get("validationReport"));
        } catch (GatewayApiClient.GatewayApiException e) {
            System.out.println(" [ERROR] " + e.getMessage());
            log.warn("Validation failed", e);
        }

        System.out.println();
    }

    /**
     * 步骤 3：审批已校验的能力。
     *
     * <p>审批将清单从 VALIDATED 状态迁移至 APPROVED。仅 APPROVED 的能力具备发布资格。</p>
     *
     * <p>审批记录包含审批者身份，并存入审计日志。</p>
     *
     * @param client 网关 API 客户端
     */
    private static void stepApproveCapability(GatewayApiClient client) {
        printStep(3, "Approve Capability");

        try {
            Map<String, Object> result = client.approveCapability(
                    DEMO_CAPABILITY_ID, DEMO_CAPABILITY_VERSION);

            String status = (String) result.get("status");
            System.out.println(" Status: " + status);

            if ("APPROVED".equals(status)) {
                System.out.println(" Capability ID: " + result.get("capabilityId"));
                System.out.println(" Version: " + result.get("capabilityVersion"));
                System.out.println(" Risk Level: " + result.get("riskLevel"));
            } else {
                System.out.println(" Message: " + result.get("message"));
            }
        } catch (GatewayApiClient.GatewayApiException e) {
            System.out.println(" [ERROR] " + e.getMessage());
            log.warn("Approval failed", e);
        }

        System.out.println();
    }

    /**
     * 步骤 4：发布到某环境。
     *
     * <p>发布会生成一个包含全部 APPROVED 能力的不可变目录快照。关键特性：</p>
     * <ul>
     * <li>快照版本单调递增。</li>
     * <li>快照内容在创建后不可修改。</li>
     * <li>快照包含内容摘要，用于完整性校验。</li>
     * <li>运行时数据面原子切换到新快照。</li>
     * </ul>
     *
     * @param client 网关 API 客户端
     */
    private static void stepPublishRelease(GatewayApiClient client) {
        printStep(4, "Publish Release (immutable snapshot)");

        try {
            Map<String, Object> result = client.publishRelease("production");

            String status = (String) result.get("status");
            System.out.println(" Status: " + status);

            if ("PUBLISHED".equals(status)) {
                System.out.println(" Snapshot Version: " + result.get("snapshotVersion"));
                System.out.println(" (快照不可变且带版本)");
            } else {
                System.out.println(" Message: " + result.get("message"));
            }
        } catch (GatewayApiClient.GatewayApiException e) {
            System.out.println(" [ERROR] " + e.getMessage());
            log.warn("Publish failed", e);
        }

        System.out.println();
    }

    /**
     * 步骤 5：下线某个能力。
     *
     * <p>下线是一项应急操作，会立即从活动目录快照中移除该能力，并生成一份不包含
     * 该能力的新快照版本。</p>
     *
     * <p>适用场景：</p>
     * <ul>
     * <li>下游 API 暴露出安全漏洞</li>
     * <li>Provider 返回错误数据</li>
     * <li>合规要求立即禁用访问</li>
     * </ul>
     *
     * @param client 网关 API 客户端
     */
    private static void stepSuspendCapability(GatewayApiClient client) {
        printStep(5, "Suspend Capability (emergency)");

        try {
            Map<String, Object> result = client.suspendCapability(
                    DEMO_CAPABILITY_ID,
                    "Emergency: Provider returning incorrect data");

            String status = (String) result.get("status");
            System.out.println(" Status: " + status);

            if ("SUSPENDED".equals(status)) {
                System.out.println(" Capability ID: " + result.get("capabilityId"));
                System.out.println(" New Snapshot Version: " + result.get("newSnapshotVersion"));
                System.out.println(" (能力已从活动目录移除)");
            } else {
                System.out.println(" Message: " + result.get("message"));
            }
        } catch (GatewayApiClient.GatewayApiException e) {
            System.out.println(" [ERROR] " + e.getMessage());
            log.warn("Suspension failed", e);
        }

        System.out.println();
    }

    /**
     * 步骤 6：回滚到历史快照。
     *
     * <p>回滚会创建一个新的快照版本，将目录状态恢复至某个历史快照。当最近的发布引入
     * 问题、需要撤销时很有用。</p>
     *
     * <p>注意：回滚不会删除问题快照，而是以历史内容创建新快照。完整历史会保留用于审计。</p>
     *
     * @param client 网关 API 客户端
     */
    private static void stepRollback(GatewayApiClient client) {
        printStep(6, "Rollback to Previous Snapshot");

        // 真实场景下应先查询快照历史，再决定回滚到哪个版本。此处使用占位版本。
        long targetVersion = 1L;

        System.out.println(" Target Snapshot Version: " + targetVersion);
        System.out.println(" (生产环境应先查询快照历史)");

        // 注意：当前实现的 GatewayApiClient 尚未提供 rollback 方法。
        // 该步骤仅演示概念。真实实现中应调用：
        // POST /admin/v1/releases:rollback
        // { "targetSnapshotVersion": 1, "environment": "production" }
        System.out.println(" [INFO] Rollback endpoint: POST /admin/v1/releases:rollback");
        System.out.println(" [INFO] Request: {\"targetSnapshotVersion\": "
                + targetVersion + ", \"environment\": \"production\"}");

        System.out.println();
    }

    /**
     * 以 UTF-8 字符串形式加载 classpath 资源。
     *
     * @param resourcePath classpath 资源路径（如 "/manifests/order-detail-query.yaml"）
     * @return 资源内容；未找到时返回 null
     */
    private static String loadClasspathResource(String resourcePath) {
        try (InputStream is = AdminWorkflowDemo.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                log.warn("Classpath resource not found: {}", resourcePath);
                return null;
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to read classpath resource: {}", resourcePath, e);
            return null;
        }
    }

    /**
     * 打印示例输出的带编号步骤头。
     *
     * @param stepNumber 步骤编号
     * @param title 步骤标题
     */
    private static void printStep(int stepNumber, String title) {
        System.out.println("-".repeat(70));
        System.out.println("Step " + stepNumber + ": " + title);
        System.out.println("-".repeat(70));
    }
}
