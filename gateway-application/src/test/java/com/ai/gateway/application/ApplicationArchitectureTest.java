package com.ai.gateway.application;

import com.ai.gateway.domain.port.ManifestRepository;
import com.ai.gateway.domain.port.OperationRepository;
import com.ai.gateway.domain.service.LifecycleStateMachine;
import com.ai.gateway.domain.service.OperationStateMachine;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
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

    /**
     * Operation state transitions must go through {@link OperationStateMachine}:
     * any class that calls {@link OperationRepository#casUpdateState} must also
     * depend on the state machine, so the rules cannot be bypassed by accident.
     */
    @ArchTest
    static final ArchRule operationStateTransitionsGoThroughStateMachine =
            classes().that(callsMethod(OperationRepository.class, "casUpdateState"))
                    .should().dependOnClassesThat().areAssignableTo(OperationStateMachine.class);

    /**
     * Manifest lifecycle transitions must go through {@link LifecycleStateMachine}:
     * any class that calls {@link ManifestRepository#updateLifecycle} must also
     * depend on the state machine.
     */
    @ArchTest
    static final ArchRule lifecycleTransitionsGoThroughStateMachine =
            classes().that(callsMethod(ManifestRepository.class, "updateLifecycle"))
                    .should().dependOnClassesThat().areAssignableTo(LifecycleStateMachine.class);

    /**
     * Predicate selecting classes that make at least one method call to the
     * given target. Uses {@link JavaClass#getMethodCallsFromSelf()} so the rule
     * is independent of any particular ArchUnit DSL version.
     */
    private static DescribedPredicate<JavaClass> callsMethod(Class<?> owner, String methodName) {
        return new DescribedPredicate<>(
                "call " + owner.getSimpleName() + "." + methodName + "()") {
            @Override
            public boolean test(JavaClass javaClass) {
                return javaClass.getMethodCallsFromSelf().stream()
                        .anyMatch(call -> call.getTargetOwner().isEquivalentTo(owner)
                                && call.getName().equals(methodName));
            }
        };
    }
}
