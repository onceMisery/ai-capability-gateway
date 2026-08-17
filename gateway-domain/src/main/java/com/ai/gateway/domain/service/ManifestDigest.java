package com.ai.gateway.domain.service;

import com.ai.gateway.domain.model.CapabilityManifest;

import java.lang.reflect.Array;
import java.lang.reflect.RecordComponent;
import java.util.Comparator;
import java.util.Map;

/**
 * Canonical content digest for a Capability Manifest.
 *
 * <p>The canonical form includes every record component, recursively sorts
 * map keys, preserves list order, and uses length-prefixed scalar values. All
 * control-plane confirmation, snapshot and execution paths must use this
 * implementation so a digest never silently degrades to id + version.</p>
 */
public final class ManifestDigest {

    private ManifestDigest() {
    }

    public static String sha256(CapabilityManifest manifest) {
        if (manifest == null) {
            throw new IllegalArgumentException("manifest must not be null");
        }
        StringBuilder canonical = new StringBuilder(1024);
        append(canonical, manifest);
        return Sha256Digest.sha256Hex(canonical.toString());
    }

    private static void append(StringBuilder out, Object value) {
        if (value == null) {
            out.append("N;");
            return;
        }
        if (value instanceof String || value instanceof Number || value instanceof Boolean
                || value instanceof Character || value instanceof Enum<?>) {
            out.append('S');
            appendScalar(out, value instanceof Enum<?> e ? e.name() : value.toString());
            return;
        }
        if (value instanceof Map<?, ?> map) {
            out.append("M[");
            map.entrySet().stream()
                    .sorted(Comparator.comparing(entry -> scalarKey(entry.getKey())))
                    .forEach(entry -> {
                        appendScalar(out, scalarKey(entry.getKey()));
                        append(out, entry.getValue());
                    });
            out.append("];" );
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            out.append("L[");
            iterable.forEach(item -> append(out, item));
            out.append("];" );
            return;
        }
        if (value.getClass().isArray()) {
            out.append("A[");
            for (int i = 0; i < Array.getLength(value); i++) {
                append(out, Array.get(value, i));
            }
            out.append("];" );
            return;
        }
        if (value.getClass().isRecord()) {
            out.append('R').append(value.getClass().getName()).append('[');
            for (RecordComponent component : value.getClass().getRecordComponents()) {
                appendScalar(out, component.getName());
                try {
                    append(out, component.getAccessor().invoke(value));
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(
                            "Unable to canonicalize manifest component " + component.getName(), e);
                }
            }
            out.append("];" );
            return;
        }
        appendScalar(out, value.toString());
    }

    private static String scalarKey(Object value) {
        return value == null ? "<null>" : value.toString();
    }

    private static void appendScalar(StringBuilder out, String value) {
        if (value == null) {
            out.append("N;");
            return;
        }
        out.append(value.length()).append(':').append(value).append(';');
    }
}
