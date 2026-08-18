package com.ai.gateway.capability.annotation;

import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class CapabilityAnnotationMetadataTest {

    @Test
    void shouldUseSourceRetentionAndNarrowTargets() {
        Map<Class<?>, Set<ElementType>> expectedTargets = Map.ofEntries(
                Map.entry(CapabilityGroup.class, Set.of(ElementType.TYPE)),
                Map.entry(Capability.class, Set.of(ElementType.METHOD)),
                Map.entry(CapArg.class, Set.of(ElementType.PARAMETER)),
                Map.entry(CapComposite.class, Set.of(ElementType.PARAMETER)),
                Map.entry(CapFieldBinding.class, Set.of()),
                Map.entry(CapInput.class, Set.of(ElementType.METHOD)),
                Map.entry(CapOutput.class, Set.of(ElementType.METHOD)),
                Map.entry(CapProjection.class, Set.of()),
                Map.entry(CapRedaction.class, Set.of()));

        expectedTargets.forEach((annotationType, expectedTarget) -> {
            Retention retention = annotationType.getAnnotation(Retention.class);
            Target target = annotationType.getAnnotation(Target.class);

            assertNotNull(retention, annotationType.getSimpleName() + " 缺少 @Retention");
            assertEquals(RetentionPolicy.SOURCE, retention.value(),
                    annotationType.getSimpleName() + " 必须只在编译期保留");
            assertNotNull(target, annotationType.getSimpleName() + " 缺少 @Target");
            assertEquals(expectedTarget, Set.copyOf(Arrays.asList(target.value())),
                    annotationType.getSimpleName() + " 的使用位置不符合契约");
        });
    }
}
