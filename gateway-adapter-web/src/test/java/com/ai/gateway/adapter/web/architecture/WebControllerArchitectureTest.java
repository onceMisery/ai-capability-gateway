package com.ai.gateway.adapter.web.architecture;

import com.ai.gateway.domain.port.ManifestRepository;
import com.ai.gateway.domain.port.OperationRepository;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** Web adapters delegate state and persistence changes to application use cases. */
@AnalyzeClasses(packages = "com.ai.gateway.adapter.web.controller")
class WebControllerArchitectureTest {

    @ArchTest
    static final ArchRule controllersMustNotDependOnManifestRepository = noClasses()
            .that().resideInAPackage("com.ai.gateway.adapter.web.controller..")
            .should().dependOnClassesThat().areAssignableTo(ManifestRepository.class);

    @ArchTest
    static final ArchRule controllersMustNotDependOnOperationRepository = noClasses()
            .that().resideInAPackage("com.ai.gateway.adapter.web.controller..")
            .should().dependOnClassesThat().areAssignableTo(OperationRepository.class);
}
