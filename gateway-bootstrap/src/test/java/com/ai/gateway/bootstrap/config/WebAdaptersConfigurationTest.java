package com.ai.gateway.bootstrap.config;

import com.ai.gateway.adapter.web.manifest.ManifestDocumentMapper;
import com.ai.gateway.adapter.web.controller.AgentToolController;
import com.ai.gateway.adapter.web.controller.ToolController;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WebAdaptersConfigurationTest 类。
 *
 * @author cmiracle@163.com
 */
class WebAdaptersConfigurationTest {

    @Test
    void shouldExplicitlyImportRuntimeWebComponents() {
        Import imports = WebAdaptersConfiguration.class.getAnnotation(Import.class);

        assertThat(imports).isNotNull();
        assertThat(Arrays.asList(imports.value()))
                .contains(ManifestDocumentMapper.class, ToolController.class,
                        AgentToolController.class);
    }
}
