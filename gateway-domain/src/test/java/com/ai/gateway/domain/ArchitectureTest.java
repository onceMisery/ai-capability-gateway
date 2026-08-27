package com.ai.gateway.domain;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * ArchUnit tests to enforce architectural constraints.
 *
 * <p>The domain module MUST NOT depend on any framework, database, protocol client, or LLM SDK.
 * Only JDK standard library is allowed.
 */
@AnalyzeClasses(packages = "com.ai.gateway.domain")
public class ArchitectureTest {

    /**
     * Domain must not depend on any Spring framework classes.
     */
    @ArchTest
    static final ArchRule domainMustNotDependOnSpring =
            noClasses().that().resideInAPackage("com.ai.gateway.domain..")
                    .should().dependOnClassesThat().resideInAPackage("org.springframework..");

    /**
     * Domain must not depend on Dubbo.
     */
    @ArchTest
    static final ArchRule domainMustNotDependOnDubbo =
            noClasses().that().resideInAPackage("com.ai.gateway.domain..")
                    .should().dependOnClassesThat().resideInAPackage("org.apache.dubbo..");

    /**
     * Domain must not depend on Jackson.
     */
    @ArchTest
    static final ArchRule domainMustNotDependOnJackson =
            noClasses().that().resideInAPackage("com.ai.gateway.domain..")
                    .should().dependOnClassesThat().resideInAPackage("com.fasterxml.jackson..");

    /**
     * Domain must not depend on any adapter or application packages.
     */
    @ArchTest
    static final ArchRule domainMustNotDependOnAdaptersOrApplication =
            noClasses().that().resideInAPackage("com.ai.gateway.domain..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.ai.gateway.adapter..",
                            "com.ai.gateway.application..",
                            "com.ai.gateway.bootstrap..");

    /**
     * Domain must not depend on SLF4J or any logging framework.
     */
    @ArchTest
    static final ArchRule domainMustNotDependOnLogging =
            noClasses().that().resideInAPackage("com.ai.gateway.domain..")
                    .should().dependOnClassesThat().resideInAPackage("org.slf4j..");

    /**
     * Domain must not depend on any agent-interoperability protocol SDK.
     *
     * <p>A2A and MCP are transport-level protocols: their record types describe how a peer talks
     * to the gateway, not what the gateway governs. Letting either into the domain would make a
     * protocol version bump a domain change, and would silently turn protocol-shaped payloads
     * into domain vocabulary — the exact coupling the hexagonal split exists to prevent.
     */
    @ArchTest
    static final ArchRule domainMustNotDependOnAgentProtocolSdks =
            noClasses().that().resideInAPackage("com.ai.gateway.domain..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "io.a2a..",
                            "io.modelcontextprotocol..");
}
