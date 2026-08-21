package com.ai.gateway.adapter.rest;

import com.ai.gateway.domain.model.ArgumentBinding;
import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.ErrorCode;
import com.ai.gateway.domain.model.InvocationRequest;
import com.ai.gateway.domain.model.InvocationResult;
import com.ai.gateway.domain.model.Protocol;
import com.ai.gateway.domain.model.ProtocolBinding;
import com.ai.gateway.domain.model.ValidationReport;
import com.ai.gateway.domain.port.InvocationAdapter;
import com.ai.gateway.domain.port.ManifestRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
public class RestInvocationAdapter implements InvocationAdapter {

    private static final List<String> METHODS = List.of("GET", "POST", "PUT", "PATCH", "DELETE");

    private final ManifestRepository manifestRepository;
    private final RestEndpointResolver endpointResolver;
    private final RestHttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Override
    public Protocol protocol() {
        return Protocol.REST;
    }

    @Override
    public ValidationReport validate(ProtocolBinding binding) {
        List<String> errors = new ArrayList<>();
        if (binding == null) {
            return ValidationReport.failure(List.of("REST binding must not be null"));
        }
        if (binding.protocol() != Protocol.REST) errors.add("Protocol must be REST");
        if (blank(binding.registryRef())) errors.add("REST endpoint reference must not be blank");
        if (blank(binding.interfaceName()) || !binding.interfaceName().startsWith("/")) {
            errors.add("REST path template must start with '/'");
        }
        if (blank(binding.method())
                || !METHODS.contains(binding.method().trim().toUpperCase())) {
            errors.add("REST method must be one of " + METHODS);
        }
        if (binding.arguments().size() != binding.parameterTypes().size()) {
            errors.add("REST parameterTypes and arguments must have the same size");
        }
        return errors.isEmpty() ? ValidationReport.success() : ValidationReport.failure(errors);
    }

    @Override
    public InvocationResult invoke(InvocationRequest request) {
        long started = System.currentTimeMillis();
        try {
            CapabilityManifest manifest = manifestRepository
                    .findByIdAndVersion(request.capabilityId(), request.capabilityVersion())
                    .orElse(null);
            if (manifest == null) {
                return failure(ErrorCode.CAPABILITY_UNAVAILABLE,
                        "Published manifest not found", started, "CAPABILITY_NOT_FOUND");
            }
            ProtocolBinding binding = manifest.spec().invocation();
            ValidationReport validation = validate(binding);
            if (!validation.valid()) {
                return failure(ErrorCode.PROTOCOL_ERROR,
                        "REST binding validation failed", started, "INVALID_BINDING");
            }
            long timeoutMs = request.deadlineBudget().remainingMs();
            if (timeoutMs <= 0) {
                return failure(ErrorCode.PROVIDER_TIMEOUT,
                        "REST provider timed out", started, "DEADLINE_EXPIRED");
            }

            Map<String, Object> values = namedArguments(binding.arguments(), request.boundArguments());
            String method = binding.method().trim().toUpperCase();
            URI uri = buildUri(endpointResolver.resolve(binding.registryRef()),
                    binding.interfaceName(), method, values, request.systemContext().locale());
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .header("Accept", "application/json")
                    .header("X-Trace-Id", request.systemContext().traceId());
            if (request.idempotencyKey() != null) {
                builder.header("Idempotency-Key", request.idempotencyKey());
            }
            if (method.equals("GET") || method.equals("DELETE")) {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                builder.header("Content-Type", contentType(binding.serialization()));
                builder.method(method, HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(values)));
            }

            RestHttpResponse response = httpClient.send(builder.build(), timeoutMs);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return failure(classifyStatus(response.statusCode()),
                        stableStatusMessage(response.statusCode()), started,
                        "HTTP_" + response.statusCode());
            }
            Object data = response.body() == null || response.body().isBlank()
                    ? null : objectMapper.readValue(response.body(), Object.class);
            return new InvocationResult(data, "HTTP_" + response.statusCode(), null, null,
                    Map.of("durationMs", elapsed(started),
                            "statusCode", String.valueOf(response.statusCode()),
                            "method", method));
        } catch (java.net.http.HttpTimeoutException e) {
            return failure(ErrorCode.PROVIDER_TIMEOUT,
                    "REST provider timed out", started, "TIMEOUT");
        } catch (java.net.ConnectException e) {
            return failure(ErrorCode.PROVIDER_TIMEOUT,
                    "REST provider unavailable", started, "CONNECT_FAILED");
        } catch (Exception e) {
            log.warn("REST invocation failed: capability={}, reason={}",
                    request.capabilityId(), e.getClass().getSimpleName());
            return failure(ErrorCode.PROTOCOL_ERROR,
                    "REST provider invocation failed", started, "INVOCATION_FAILED");
        }
    }

    private URI buildUri(URI base, String pathTemplate, String method,
                         Map<String, Object> values, String locale) {
        String path = pathTemplate;
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String encoded = encode(String.valueOf(entry.getValue()));
            path = path.replace("{" + entry.getKey() + "}", encoded);
        }
        URI resolved = base.resolve(path.startsWith("/") ? path.substring(1) : path);
        if (!method.equals("GET") && !method.equals("DELETE")) return resolved;
        String query = values.entrySet().stream()
                .filter(entry -> !pathTemplate.contains("{" + entry.getKey() + "}"))
                .map(entry -> encode(entry.getKey()) + "=" + encode(String.valueOf(entry.getValue())))
                .collect(java.util.stream.Collectors.joining("&"));
        String localeQuery = "locale=" + encode(locale);
        query = query.isBlank() ? localeQuery : query + "&" + localeQuery;
        return URI.create(resolved + "?" + query);
    }

    private static Map<String, Object> namedArguments(List<ArgumentBinding> bindings,
                                                       List<Object> values) {
        if (bindings.size() != values.size()) {
            throw new IllegalArgumentException("REST argument count does not match binding");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < bindings.size(); i++) {
            result.put(bindings.get(i).name(), values.get(i));
        }
        return result;
    }

    private static ErrorCode classifyStatus(int status) {
        if (status == 408 || status == 504) return ErrorCode.PROVIDER_TIMEOUT;
        if (status == 429) return ErrorCode.RATE_LIMITED;
        if (status == 401 || status == 403) return ErrorCode.PERMISSION_DENIED;
        if (status >= 400 && status < 500) return ErrorCode.PROVIDER_REJECTED;
        return ErrorCode.PROTOCOL_ERROR;
    }

    private static String stableStatusMessage(int status) {
        if (status == 408 || status == 504) return "REST provider timed out";
        if (status == 429) return "REST provider rate limit reached";
        if (status == 401 || status == 403) return "REST provider rejected authorization";
        return status >= 500 ? "REST provider returned a server error"
                : "REST provider rejected the request";
    }

    private static InvocationResult failure(ErrorCode code, String message,
                                            long started, String reason) {
        return new InvocationResult(null, "ERROR", code, message,
                Map.of("durationMs", elapsed(started), "reason", reason));
    }

    private static String elapsed(long started) {
        return String.valueOf(System.currentTimeMillis() - started);
    }

    private static String contentType(String serialization) {
        return blank(serialization) ? "application/json" : serialization;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
