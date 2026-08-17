package com.ai.gateway.adapter.dubbo;

import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ProtocolConfig;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.ServiceConfig;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.service.GenericService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DubboGenericInvocationIT {

    private static final String INTERFACE = "com.example.gateway.ContractOnlyService";
    private static ServiceConfig<GenericService> service;
    private static ReferenceConfig<GenericService> reference;
    private static GenericService client;

    @BeforeAll
    static void exportGenericProvider() {
        GenericService provider = (method, parameterTypes, arguments) -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("method", method);
            result.put("parameterType", parameterTypes[0]);
            result.put("argument", arguments[0]);
            result.put("traceId", RpcContext.getServerAttachment().getAttachment("traceId"));
            return result;
        };

        ApplicationConfig application = new ApplicationConfig("gateway-generic-invocation-it");
        application.setQosEnable(false);
        service = new ServiceConfig<>();
        service.setApplication(application);
        service.setProtocol(new ProtocolConfig("injvm"));
        service.setInterface(INTERFACE);
        service.setGeneric("true");
        service.setRef(provider);
        service.export();

        reference = new ReferenceConfig<>();
        reference.setApplication(application);
        reference.setInterface(INTERFACE);
        reference.setGeneric("true");
        reference.setInjvm(true);
        reference.setCheck(true);
        client = reference.get();
    }

    @AfterAll
    static void releaseDubboResources() {
        if (reference != null) {
            reference.destroy();
        }
        if (service != null) {
            service.unexport();
        }
    }

    @Test
    void invokesByInterfaceAndTypeNamesWithoutBusinessApiJar() {
        RpcContext.getClientAttachment().setAttachment("traceId", "trace-it-1");
        try {
            Object result = client.$invoke("lookup",
                    new String[]{"java.lang.String"}, new Object[]{"A100"});

            assertThat(result).isInstanceOf(Map.class);
            assertThat(asMap(result))
                    .containsEntry("method", "lookup")
                    .containsEntry("parameterType", "java.lang.String")
                    .containsEntry("argument", "A100")
                    .containsEntry("traceId", "trace-it-1");
        } finally {
            RpcContext.getClientAttachment().clearAttachments();
        }
    }

    @Test
    void parameterTypeListDisambiguatesGenericOverloads() {
        Object result = client.$invoke("lookup",
                new String[]{"java.lang.Long"}, new Object[]{42L});

        assertThat(asMap(result))
                .containsEntry("parameterType", "java.lang.Long")
                .containsEntry("argument", 42L);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }
}
