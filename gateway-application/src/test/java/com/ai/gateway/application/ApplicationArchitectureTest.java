package com.ai.gateway.application;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * ArchUnit tests to enforce architectural constraints for the application layer.
 *
 * <p>The application layer MUST NOT depend on any adapter, bootstrap, or
 * framework packages. It may only depend on the domain layer and JDK
 * standard library.</p>
 *
 * @since 0.1.0
 */
@AnalyzeClasses(packages = "com.ai.gateway.application")
public class ApplicationArchitectureTest {

    /**
     * Application must not depend on any adapter packages.
     */
    @ArchTest
    static final ArchRule applicationMustNotDependOnAdapters =
            noClasses().that().resideInAPackage("com.ai.gateway.application..")
                    .should().dependOnClassesThat().resideInAPackage("com.ai.gateway.adapter..");

    /**
     * Application must not depend on bootstrap package.
     */
    @ArchTest
    static final ArchRule applicationMustNotDependOnBootstrap =
            noClasses().that().resideInAPackage("com.ai.gateway.application..")
                    .should().dependOnClassesThat().resideInAPackage("com.ai.gateway.bootstrap..");

    /**
     * Application must not depend on any Spring framework classes.
     */
    @ArchTest
    static final ArchRule applicationMustNotDependOnSpring =
            noClasses().that().resideInAPackage("com.ai.gateway.application..")
                    .should().dependOnClassesThat().resideInAPackage("org.springframework..");

    /**
     * Application must not depend on Dubbo.
     */
    @ArchTest
    static final ArchRule applicationMustNotDependOnDubbo =
            noClasses().that().resideInAPackage("com.ai.gateway.application..")
                    .should().dependOnClassesThat().resideInAPackage("org.apache.dubbo..");

    /**
     * Application must not depend on Jackson.
     */
    @ArchTest
    static final ArchRule applicationMustNotDependOnJackson =
            noClasses().that().resideInAPackage("com.ai.gateway.application..")
                    .should().dependOnClassesThat().resideInAPackage("com.fasterxml.jackson..");
}
