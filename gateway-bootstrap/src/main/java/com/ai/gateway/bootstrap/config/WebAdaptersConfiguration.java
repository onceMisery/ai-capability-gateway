package com.ai.gateway.bootstrap.config;

import com.ai.gateway.adapter.web.controller.AclAdminController;
import com.ai.gateway.adapter.web.controller.AgentToolController;
import com.ai.gateway.adapter.web.GatewayWebProperties;
import com.ai.gateway.adapter.web.controller.AdminController;
import com.ai.gateway.adapter.web.controller.CatalogDiagnosticsController;
import com.ai.gateway.adapter.web.controller.CatalogQueryController;
import com.ai.gateway.adapter.web.controller.ConsoleAuthController;
import com.ai.gateway.adapter.web.controller.HealthController;
import com.ai.gateway.adapter.web.controller.MonitorQueryController;
import com.ai.gateway.adapter.web.controller.NaturalLanguageController;
import com.ai.gateway.adapter.web.controller.OperationController;
import com.ai.gateway.adapter.web.controller.SystemConfigController;
import com.ai.gateway.adapter.web.controller.ToolController;
import com.ai.gateway.adapter.web.filter.RequestSizeLimitFilter;
import com.ai.gateway.adapter.web.filter.AdminAuthenticationFilter;
import com.ai.gateway.adapter.web.filter.TraceContextFilter;
import com.ai.gateway.adapter.web.handler.GlobalExceptionHandler;
import com.ai.gateway.adapter.web.manifest.ManifestDocumentMapper;
import com.ai.gateway.adapter.web.support.RequestContextFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Web 适配器装配（gateway-adapter-web）。
 *
 * <p>聚合 REST 层的全部控制器、过滤器、异常处理器与请求上下文工厂。
 * 运行面控制器（自然语言查询、确定性执行、健康检查）与管理面控制器
 * （管理后台认证、目录查询、监控统计、ACL 管理、系统配置）均在此注册。</p>
 *
 * @since 0.1.0
 * @author cmiracle@163.com
 */
@Configuration
@EnableConfigurationProperties(GatewayWebProperties.class)
@Import({
        RequestContextFactory.class,
        ManifestDocumentMapper.class,
        AdminController.class,
        ToolController.class,
        NaturalLanguageController.class,
        OperationController.class,
        HealthController.class,
        ConsoleAuthController.class,
        AclAdminController.class,
        AgentToolController.class,
        MonitorQueryController.class,
        CatalogQueryController.class,
        SystemConfigController.class,
        CatalogDiagnosticsController.class,
        GlobalExceptionHandler.class,
        TraceContextFilter.class,
        RequestSizeLimitFilter.class,
        AdminAuthenticationFilter.class,
})
public class WebAdaptersConfiguration {
}
