package com.ai.gateway.bootstrap.config;

import com.ai.gateway.domain.model.ErrorCode;
import com.ai.gateway.domain.model.InvocationRequest;
import com.ai.gateway.domain.model.InvocationResult;
import com.ai.gateway.domain.model.Protocol;
import com.ai.gateway.domain.model.ProtocolBinding;
import com.ai.gateway.domain.model.ValidationReport;
import com.ai.gateway.domain.port.InvocationAdapter;
import com.ai.gateway.domain.port.ManifestRepository;
import lombok.RequiredArgsConstructor;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
final class ProtocolRoutingInvocationAdapter implements InvocationAdapter {

    private final ManifestRepository manifestRepository;
    private final Map<Protocol, InvocationAdapter> adapters;

    static ProtocolRoutingInvocationAdapter of(ManifestRepository repository,
                                               List<InvocationAdapter> delegates) {
        EnumMap<Protocol, InvocationAdapter> adapters = new EnumMap<>(Protocol.class);
        for (InvocationAdapter delegate : delegates) {
            if (delegate.protocol() != null) {
                adapters.put(delegate.protocol(), delegate);
            }
        }
        return new ProtocolRoutingInvocationAdapter(repository, Map.copyOf(adapters));
    }

    @Override
    public Protocol protocol() {
        return Protocol.DUBBO;
    }

    @Override
    public ValidationReport validate(ProtocolBinding binding) {
        InvocationAdapter delegate = adapters.get(binding.protocol());
        return delegate == null
                ? ValidationReport.failure(List.of("No adapter for protocol: " + binding.protocol()))
                : delegate.validate(binding);
    }

    @Override
    public InvocationResult invoke(InvocationRequest request) {
        var manifest = manifestRepository.findByIdAndVersion(
                request.capabilityId(), request.capabilityVersion()).orElse(null);
        if (manifest == null || manifest.spec().invocation() == null) {
            return new InvocationResult(null, "ERROR",
                    ErrorCode.CAPABILITY_UNAVAILABLE,
                    "Published manifest not found", Map.of());
        }
        Protocol protocol = manifest.spec().invocation().protocol();
        InvocationAdapter delegate = adapters.get(protocol);
        if (delegate == null) {
            return new InvocationResult(null, "ERROR", ErrorCode.PROTOCOL_ERROR,
                    "Protocol adapter is not configured", Map.of("protocol", protocol.name()));
        }
        return delegate.invoke(request);
    }
}
