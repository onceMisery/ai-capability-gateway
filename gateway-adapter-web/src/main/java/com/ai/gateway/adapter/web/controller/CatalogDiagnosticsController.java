package com.ai.gateway.adapter.web.controller;

import com.ai.gateway.adapter.web.support.ApiResponse;
import com.ai.gateway.adapter.web.support.SecurityHelper;
import com.ai.gateway.application.runtime.NlRouteDiagnosticsUseCase;
import com.ai.gateway.domain.model.AdminAction;
import com.ai.gateway.domain.model.Principal;
import com.ai.gateway.domain.port.AuthenticationPort;
import com.ai.gateway.domain.port.AuthorizationPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 管理面能力目录诊断控制器。
 *
 * <p>暴露 {@code POST /admin/v1/diagnostics/nl-route}：以 dry-run 方式复现运行面
 * 自然语言路由的完整判定链，并输出面向清单作者的归因。这是运行面 LLM 路由内核在
 * {@code DIAGNOSTIC} 档下唯一的对外出口。</p>
 *
 * <p><b>为什么必须手工组装响应体</b>：诊断报告内部持有能力清单派生信息，一旦直接
 * 序列化领域/用例记录，将来在这些记录上新增字段（例如为内部排障加入协议绑定摘要）
 * 就会静默泄漏到管理台响应里。逐字段白名单映射把「能对外说什么」固定在这一层，
 * 使泄漏必须是一次显式改动。</p>
 *
 * <p>身份认证与 {@link AdminAction#DIAGNOSE} 授权由
 * {@code AdminAuthenticationFilter} 前置完成，此处再做一次兜底校验，
 * 以免过滤器配置遗漏导致端点裸奔。</p>
 *
 * @author cmiracle@163.com
 * @since 0.2.0
 */
@RestController
@RequestMapping("/admin/v1/diagnostics")
@Slf4j
public class CatalogDiagnosticsController {

    private final NlRouteDiagnosticsUseCase diagnosticsUseCase;
    private final AuthenticationPort authenticationPort;
    private final AuthorizationPort authorizationPort;

    /**
     * @param diagnosticsUseCase 能力目录诊断用例
     * @param authenticationPort 认证端口，用于兜底提取 Principal
     * @param authorizationPort 授权端口，用于兜底校验管理员权限
     */
    public CatalogDiagnosticsController(NlRouteDiagnosticsUseCase diagnosticsUseCase,
                                        AuthenticationPort authenticationPort,
                                        AuthorizationPort authorizationPort) {
        this.diagnosticsUseCase = Objects.requireNonNull(diagnosticsUseCase);
        this.authenticationPort = Objects.requireNonNull(authenticationPort);
        this.authorizationPort = Objects.requireNonNull(authorizationPort);
    }

    /**
     * 运行一次自然语言路由诊断。
     *
     * @param request 诊断请求体
     * @return 200 + 诊断报告；端点未开启返回 501，入参非法返回 400
     */
    @PostMapping("/nl-route")
    public ResponseEntity<Map<String, Object>> diagnoseNlRoute(
            @RequestBody(required = false) NlRouteDiagnosticsRequest request) {

        Principal principal;
        try {
            SecurityHelper.requireAdmin(authenticationPort, authorizationPort,
                    AdminAction.DIAGNOSE);
            principal = SecurityHelper.getCurrentPrincipal(authenticationPort);
        } catch (SecurityException e) {
            // 过滤器通常已拦下；此处仅兜底，且不回传内部原因文本。
            return ApiResponse.forbidden("当前账号没有运行能力目录诊断的权限。");
        }
        if (principal == null) {
            return ApiResponse.unauthorized("登录凭证无效或缺失，请重新登录。");
        }
        NlRouteDiagnosticsRequest safeRequest = request == null
                ? new NlRouteDiagnosticsRequest(null, null, null, 0, null, null)
                : request;

        NlRouteDiagnosticsUseCase.DiagnosticsReport report =
                diagnosticsUseCase.diagnose(principal, safeRequest.toUseCaseRequest());
        return switch (report.status()) {
            case COMPLETED -> ApiResponse.ok(toResponseBody(report));
            case DISABLED -> ApiResponse.error(report.errorCode(),
                    "本部署未开启能力目录诊断端点。", HttpStatus.NOT_IMPLEMENTED);
            case REJECTED -> ApiResponse.error("ARGUMENT_VALIDATION_FAILED",
                    report.message(), HttpStatus.BAD_REQUEST);
            case UNRESOLVED -> ApiResponse.ok(toResponseBody(report));
        };
    }

    /**
     * 把诊断报告逐字段映射为响应体（白名单）。
     *
     * <p>{@code UNRESOLVED} 同样以 200 返回：诊断本身成功完成了「解释为什么没结果」
     * 这件事，把它映射成 4xx/5xx 会让管理台无法区分「诊断跑失败了」与
     * 「诊断跑通了，结论是目录里没有可见能力」。</p>
     */
    private Map<String, Object> toResponseBody(
            NlRouteDiagnosticsUseCase.DiagnosticsReport report) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("outcome", report.status().name());
        body.put("snapshotVersion", report.snapshotVersion());
        body.put("indexedCatalogVersion", report.indexedCatalogVersion());
        body.put("normalizedText", report.normalizedText());
        body.put("visibleCapabilityCount", report.visibleCapabilityCount());
        body.put("durationMs", report.durationMs());
        body.put("reason", report.message());
        body.put("candidates", toCandidates(report.candidates()));
        body.put("thresholdVerdict", toThresholdVerdict(report.thresholdVerdict()));
        body.put("modelVerdict", toModelVerdict(report.modelVerdict()));
        body.put("findings", toFindings(report.findings()));
        return body;
    }

    private List<Map<String, Object>> toCandidates(
            List<NlRouteDiagnosticsUseCase.CandidateView> candidates) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (NlRouteDiagnosticsUseCase.CandidateView view : candidates) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("rank", view.rank());
            item.put("alias", view.alias());
            item.put("capabilityId", view.capabilityId());
            item.put("capabilityVersion", view.capabilityVersion());
            item.put("manifestDigest", view.manifestDigest());
            item.put("score", view.score());
            item.put("risk", view.risk() == null ? null : view.risk().name());
            item.put("modelVisibleDisplayName", view.displayName());
            item.put("modelVisiblePurpose", view.purpose());
            item.put("schemaClass", view.schemaClass() == null
                    ? null : view.schemaClass().name());
            item.put("argumentContract", view.argumentContract());
            item.put("projectionSuppressed", view.projectionSuppressed());
            item.put("strippedTrustedFields", view.strippedTrustedFields());
            result.add(item);
        }
        return result;
    }

    private Map<String, Object> toThresholdVerdict(
            NlRouteDiagnosticsUseCase.ThresholdVerdict verdict) {
        if (verdict == null) {
            return null;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("decision", verdict.decision().name());
        body.put("selectedAlias", verdict.selectedAlias());
        body.put("selectedCapabilityId", verdict.selectedCapabilityId());
        body.put("clarificationQuestion", verdict.clarificationQuestion());
        body.put("noMatchReason", verdict.noMatchReason());
        body.put("topScore", verdict.topScore());
        body.put("runnerUpScore", verdict.runnerUpScore());
        body.put("minRelevanceScore", verdict.minRelevanceScore());
        body.put("minTop1Top2ScoreDiff", verdict.minTop1Top2ScoreDiff());
        return body;
    }

    private Map<String, Object> toModelVerdict(
            NlRouteDiagnosticsUseCase.ModelVerdict verdict) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("outcome", verdict.outcome().name());
        body.put("returnedAlias", verdict.returnedAlias());
        body.put("extractedArguments", verdict.extractedArguments());
        body.put("errorCode", verdict.errorCode());
        body.put("detail", verdict.detail());
        return body;
    }

    /** 发现项同时输出稳定枚举名与中文修复提示：前者供基线断言，后者供管理台展示。 */
    private List<Map<String, Object>> toFindings(
            List<NlRouteDiagnosticsUseCase.Finding> findings) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (NlRouteDiagnosticsUseCase.Finding finding : findings) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", finding.name());
            item.put("hint", finding.hint());
            result.add(item);
        }
        return result;
    }

    /**
     * 诊断请求体。
     *
     * <p>刻意不接受 {@code orgId} / {@code tenantId} / {@code userId}：组织上下文属于
     * 受信参数，只能来自已认证的 {@link Principal}。请求体只能替换角色与权限词，
     * 因此无法跨组织侦察能力目录。</p>
     *
     * <p>{@code dryRun} 用包装类型并默认 {@code true}：调用方省略即为安全默认，
     * 显式传 {@code false} 会被用例拒绝——「不会执行」由此成为可被断言的契约。</p>
     *
     * @param query 待诊断的自然语言查询
     * @param subjectRoles 被诊断视角的角色；省略表示沿用当前管理员视角
     * @param subjectPermissions 被诊断视角的权限词；省略表示沿用当前管理员视角
     * @param topK 候选数上限；省略或非正表示取运行面默认值
     * @param explain 是否执行 LLM 受限选择；省略视为 {@code true}
     * @param dryRun 必须为 {@code true}；省略视为 {@code true}
     */
    public record NlRouteDiagnosticsRequest(String query,
                                            List<String> subjectRoles,
                                            List<String> subjectPermissions,
                                            int topK,
                                            Boolean explain,
                                            Boolean dryRun) {

        /**
         * 转换为用例入参，对省略字段应用安全默认值。
         *
         * @return 用例诊断请求
         */
        NlRouteDiagnosticsUseCase.DiagnosticsRequest toUseCaseRequest() {
            return new NlRouteDiagnosticsUseCase.DiagnosticsRequest(
                    query,
                    subjectRoles == null ? List.of() : subjectRoles,
                    subjectPermissions == null ? List.of() : subjectPermissions,
                    topK,
                    explain == null || explain,
                    dryRun == null || dryRun);
        }
    }
}
