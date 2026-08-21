package com.ai.gateway.testprovider;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 用于兼容性测试的独立 Spring Boot + Dubbo 测试 Provider（设计文档 §21.2）。
 *
 * <p>该服务在网关 classpath 上无需真实业务 API JAR 即可模拟真实业务 Dubbo 接口。
 * 它暴露基于泛型 {@code Map} 的接口并返回平台标准 Envelope 结构，从而可以端到端地
 * 验证网关的调用、归一化与治理能力。</p>
 *
 * @author cmiracle@163.com
 */
@SpringBootApplication
@EnableDubbo
public class TestProviderApplication {

    public static void main(String[] args) {
        SpringApplication.run(TestProviderApplication.class, args);
    }
}
