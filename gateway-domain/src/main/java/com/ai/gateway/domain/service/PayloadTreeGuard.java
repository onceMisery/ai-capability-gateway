package com.ai.gateway.domain.service;

import com.ai.gateway.domain.model.PayloadLimits;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * 对 JSON 兼容对象树执行一次受限、非递归遍历。
 *
 * <p>该服务同时检查深度、节点数、集合长度、对象字段数、字符串长度和
 * UTF-8 JSON 字节数。使用显式栈而不是 Java 调用栈，避免在真正报告超限前
 * 因恶意深层数据触发 {@code StackOverflowError}。</p>
 */
public final class PayloadTreeGuard {

    private final PayloadLimits limits;

    /**
     * 使用指定预算创建树守卫。
     *
     * @param limits 统一 Payload 预算
     */
    public PayloadTreeGuard(PayloadLimits limits) {
        this.limits = java.util.Objects.requireNonNull(limits, "limits must not be null");
    }

    /**
     * 校验模型输入树。
     *
     * @param value 输入树
     */
    public void validateInput(Object value) {
        validate(value, limits.maxInputBytes(), "Input");
    }

    /**
     * 校验 Provider 输出树。能力级限制为正数时取全局限制和能力级限制的较小值。
     *
     * @param value 输出树
     * @param capabilityMaxBytes Manifest 声明的能力级输出限制
     */
    public void validateOutput(Object value, int capabilityMaxBytes) {
        long effectiveMax = capabilityMaxBytes > 0
                ? Math.min(limits.maxOutputBytes(), capabilityMaxBytes)
                : limits.maxOutputBytes();
        validate(value, effectiveMax, "Output");
    }

    private void validate(Object root, long maxBytes, String kind) {
        Deque<Work> work = new ArrayDeque<>();
        Set<Object> activeContainers = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        work.push(new Visit(root, 0));
        long[] nodeCount = {0L};
        long[] byteCount = {0L};

        while (!work.isEmpty()) {
            Work next = work.pop();
            if (next instanceof Visit visit) {
                visitValue(visit.value(), visit.depth(), kind, maxBytes,
                        work, activeContainers, nodeCount, byteCount);
            } else {
                Container container = (Container) next;
                processContainer(container, kind, maxBytes, work,
                        activeContainers, byteCount);
            }
        }
    }

    private void visitValue(Object value,
                            int depth,
                            String kind,
                            long maxBytes,
                            Deque<Work> work,
                            Set<Object> activeContainers,
                            long[] nodeCount,
                            long[] byteCount) {
        if (depth > limits.maxDepth()) {
            throw exceeded(kind + " JSON depth exceeds " + limits.maxDepth());
        }
        nodeCount[0]++;
        if (nodeCount[0] > limits.maxNodes()) {
            throw exceeded(kind + " JSON node count exceeds " + limits.maxNodes());
        }

        if (value == null) {
            addBytes(4L, maxBytes, kind, byteCount);
            return;
        }
        if (value instanceof String string) {
            long stringBytes = utf8Bytes(string);
            if (stringBytes > limits.maxStringBytes()) {
                throw exceeded(kind + " string exceeds " + limits.maxStringBytes()
                        + " UTF-8 bytes");
            }
            addBytes(quotedJsonBytes(string), maxBytes, kind, byteCount);
            return;
        }
        if (value instanceof Boolean bool) {
            addBytes(bool ? 4L : 5L, maxBytes, kind, byteCount);
            return;
        }
        if (value instanceof Number number) {
            addBytes(utf8Bytes(String.valueOf(number)), maxBytes, kind, byteCount);
            return;
        }
        if (value instanceof Map<?, ?> map) {
            if (map.size() > limits.maxObjectFields()) {
                throw exceeded(kind + " object fields exceed " + limits.maxObjectFields());
            }
            if (map.size() > limits.maxCollectionLength()) {
                throw exceeded(kind + " object members exceed " + limits.maxCollectionLength());
            }
            if (!activeContainers.add(map)) {
                throw exceeded(kind + " JSON tree contains a cyclic object");
            }
            addBytes(1L, maxBytes, kind, byteCount);
            work.push(new Container(map, map.entrySet().iterator(), depth, true, false, '}'));
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            if (value instanceof java.util.Collection<?> collection
                    && collection.size() > limits.maxCollectionLength()) {
                throw exceeded(kind + " array length exceeds " + limits.maxCollectionLength());
            }
            if (!activeContainers.add(value)) {
                throw exceeded(kind + " JSON tree contains a cyclic collection");
            }
            addBytes(1L, maxBytes, kind, byteCount);
            work.push(new Container(value, iterable.iterator(), depth, true, true, ']'));
            return;
        }

        throw exceeded(kind + " contains unsupported value type "
                + value.getClass().getName());
    }

    private void processContainer(Container container,
                                   String kind,
                                   long maxBytes,
                                   Deque<Work> work,
                                   Set<Object> activeContainers,
                                   long[] byteCount) {
        if (container.iterator().hasNext()) {
            if (container.memberCount() >= limits.maxCollectionLength()) {
                throw exceeded(kind + " collection length exceeds "
                        + limits.maxCollectionLength());
            }
            Object member = container.iterator().next();
            int memberCount = container.memberCount() + 1;
            if (!container.first()) {
                addBytes(1L, maxBytes, kind, byteCount);
            }
            if (!container.list()) {
                Map.Entry<?, ?> entry = (Map.Entry<?, ?>) member;
                String key = String.valueOf(entry.getKey());
                addBytes(quotedJsonBytes(key) + 1L, maxBytes, kind, byteCount);
                work.push(new Container(container.value(), container.iterator(),
                        container.depth(), false, false, container.closeChar(), memberCount));
                work.push(new Visit(entry.getValue(), container.depth() + 1));
            } else {
                work.push(new Container(container.value(), container.iterator(),
                        container.depth(), false, true, container.closeChar(), memberCount));
                work.push(new Visit(member, container.depth() + 1));
            }
            return;
        }

        addBytes(1L, maxBytes, kind, byteCount);
        activeContainers.remove(container.value());
    }

    private void addBytes(long delta, long maxBytes, String kind, long[] byteCount) {
        if (delta < 0 || byteCount[0] > maxBytes - delta) {
            throw exceeded(kind + " JSON bytes exceed " + maxBytes);
        }
        byteCount[0] += delta;
    }

    private static long utf8Bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    /** 计算 JSON 字符串的实际 UTF-8 字节数（包括引号和必要转义）。 */
    private static long quotedJsonBytes(String value) {
        long bytes = 2L;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (codePoint == '"' || codePoint == '\\') {
                bytes += 2L;
            } else if (codePoint == '\b' || codePoint == '\t' || codePoint == '\n'
                    || codePoint == '\f' || codePoint == '\r') {
                // Jackson 默认使用两字符短转义，而不是统一写成 \\uXXXX。
                bytes += 2L;
            } else if (codePoint <= 0x1F) {
                bytes += 6L;
            } else {
                bytes += new String(Character.toChars(codePoint))
                        .getBytes(StandardCharsets.UTF_8).length;
            }
        }
        return bytes;
    }

    private PayloadLimitExceededException exceeded(String message) {
        return new PayloadLimitExceededException(message);
    }

    private sealed interface Work permits Visit, Container {
    }

    private record Visit(Object value, int depth) implements Work {
    }

    private record Container(Object value,
                             Iterator<?> iterator,
                             int depth,
                             boolean first,
                             boolean list,
                             char closeChar,
                             int memberCount) implements Work {
        private Container(Object value,
                          Iterator<?> iterator,
                          int depth,
                          boolean first,
                          boolean list,
                          char closeChar) {
            this(value, iterator, depth, first, list, closeChar, 0);
        }
    }
}
