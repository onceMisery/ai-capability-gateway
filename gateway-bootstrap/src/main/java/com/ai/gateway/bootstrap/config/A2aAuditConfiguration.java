package com.ai.gateway.bootstrap.config;

import com.ai.gateway.adapter.a2a.A2aMode;
import com.ai.gateway.adapter.a2a.A2aTaskAuditRecorder;
import com.ai.gateway.bootstrap.audit.A2aAuditPortRecorder;
import com.ai.gateway.domain.port.AuditPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * A2A <b>平面</b>审计出口的装配（设计 §3.8）。
 *
 * <p>单独成类，是因为审计出口既不属于入站也不属于出站，而是两者共用的一件事：
 * 「必须留下痕迹、且痕迹落不下去就不能当作成功」。若把它留在
 * {@link A2aConfiguration}（服务端门禁）里，{@code CLIENT_ONLY} 的部署就会拿不到审计出口，
 * 于是出站委托要么无痕发生、要么装配失败——两种结果都不能接受。</p>
 *
 * <p>装配条件是「入站或出站至少启用其一」，且刻意<b>不</b>写成 {@code mode != DISABLED}：
 * 按语义属性取并集，将来新增一种承载模式时不会静默漏掉审计出口。</p>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 * @see A2aTaskAuditRecorder
 */
@Configuration
@Conditional(A2aAuditConfiguration.PlaneEnabledCondition.class)
public class A2aAuditConfiguration {

    /**
     * A2A 平面审计记录器，入站与出站共用。
     *
     * <p>刻意<b>不</b>注入容器里的共享 {@link ObjectMapper}：审计明细的字段形态不该随一次全局
     * {@code spring.jackson.*} 调整而改变，否则同一张审计表里会同时存在两种形状的明细，
     * 而查询侧无从判断某条记录属于哪一种。</p>
     *
     * @param auditPort 统一审计出口
     * @return 记录器；落库失败时抛出，由调用方降级为失败态
     */
    @Bean
    public A2aTaskAuditRecorder a2aTaskAuditRecorder(AuditPort auditPort) {
        return new A2aAuditPortRecorder(auditPort, new ObjectMapper());
    }

    /**
     * 「A2A 平面是否存在」的判定：入站或出站启用其一即需要审计出口。
     */
    static class PlaneEnabledCondition implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            Environment environment = context.getEnvironment();
            return planeEnabled(environment.getProperty("gateway.a2a.enabled"),
                    environment.getProperty("gateway.a2a.mode"));
        }

        /**
         * @param enabled {@code gateway.a2a.enabled} 原始值，允许为 {@code null}
         * @param mode    {@code gateway.a2a.mode} 原始值，允许为 {@code null}
         * @return 是否需要装配平面审计出口
         */
        static boolean planeEnabled(String enabled, String mode) {
            return planeEnabled(Boolean.parseBoolean(enabled), mode);
        }

        /**
         * @param enabled 总开关
         * @param mode    承载模式原始值，允许为 {@code null}
         * @return 是否需要装配平面审计出口
         */
        static boolean planeEnabled(boolean enabled, String mode) {
            if (!enabled) {
                return false;
            }
            A2aMode resolved = A2aMode.from(mode);
            return resolved.serverEnabled() || resolved.clientEnabled();
        }
    }
}
