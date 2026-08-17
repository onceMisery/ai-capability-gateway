package com.ai.gateway.application.operation;

import com.ai.gateway.application.runtime.NaturalLanguageQueryUseCase;
import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.ConfirmationToken;
import com.ai.gateway.domain.model.OperationRecord;
import com.ai.gateway.domain.model.OutputContract;
import com.ai.gateway.domain.model.OutputMode;
import com.ai.gateway.domain.model.Principal;
import com.ai.gateway.domain.model.Protocol;
import com.ai.gateway.domain.model.ProtocolBinding;
import com.ai.gateway.domain.model.RequestContext;
import com.ai.gateway.domain.model.ResiliencePolicy;
import com.ai.gateway.domain.model.RiskLevel;
import com.ai.gateway.domain.model.ValidationReport;
import com.ai.gateway.domain.port.ArgumentPayloadCodec;
import com.ai.gateway.domain.port.AuthenticationPort;
import com.ai.gateway.domain.port.AuthorizationPort;
import com.ai.gateway.domain.port.CatalogPort;
import com.ai.gateway.domain.port.ConfirmationTokenCodec;
import com.ai.gateway.domain.port.EncryptionPort;
import com.ai.gateway.domain.port.OperationRepository;
import com.ai.gateway.domain.port.SchemaValidator;
import com.ai.gateway.domain.port.TypeConverterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OperationPrepareIdempotencyTest {

    @Test
    void repeatedClientKeyReturnsTheSameOperation() {
        NaturalLanguageQueryUseCase routing = mock(NaturalLanguageQueryUseCase.class);
        TypeConverterRegistry converters = mock(TypeConverterRegistry.class);
        SchemaValidator schemaValidator = mock(SchemaValidator.class);
        AuthorizationPort authorization = mock(AuthorizationPort.class);
        EncryptionPort encryption = mock(EncryptionPort.class);
        OperationRepository repository = mock(OperationRepository.class);
        CatalogPort catalog = mock(CatalogPort.class);
        AuthenticationPort authentication = mock(AuthenticationPort.class);
        ConfirmationTokenCodec tokenCodec = mock(ConfirmationTokenCodec.class);
        ArgumentPayloadCodec payloadCodec = mock(ArgumentPayloadCodec.class);
        RequestContext context = mock(RequestContext.class);
        Principal principal = new Principal("user-1", 7L, List.of(), List.of(),
                Instant.now(), "JWT");
        CapabilityManifest manifest = manifest();

        when(routing.execute(context, "create order", "zh-CN", "UTC"))
                .thenReturn(new NaturalLanguageQueryUseCase.QueryResult(
                        NaturalLanguageQueryUseCase.QueryStatus.COMPLETED,
                        Map.of("capabilityId", "order.create",
                                "capabilityVersion", "1.0.0",
                                "modelArguments", Map.of()),
                        null, null, 11L, null, null));
        when(catalog.findCapability("order.create", "1.0.0"))
                .thenReturn(Optional.of(manifest));
        when(authentication.authenticate(context)).thenReturn(principal);
        when(schemaValidator.validate(any(), any())).thenReturn(ValidationReport.success());
        when(authorization.authorizeExecution(principal, "order.create", "1.0.0"))
                .thenReturn(true);
        when(payloadCodec.encode(List.of())).thenReturn("[]");
        when(encryption.encrypt("[]")).thenReturn("ciphertext");

        AtomicReference<OperationRecord> owner = new AtomicReference<>();
        when(repository.saveOrGetByIdempotencyKey(any())).thenAnswer(invocation -> {
            OperationRecord candidate = invocation.getArgument(0);
            owner.compareAndSet(null, candidate);
            return owner.get();
        });
        when(tokenCodec.issue(any(), any(), any(Long.class), any(), any()))
                .thenAnswer(invocation -> new ConfirmationToken(
                        "token", invocation.getArgument(0), invocation.getArgument(1),
                        invocation.getArgument(2), invocation.getArgument(3), "signature",
                        invocation.getArgument(4), false));

        OperationPrepareUseCase useCase = new OperationPrepareUseCase(
                routing, converters, schemaValidator, authorization, encryption, repository,
                catalog, authentication, tokenCodec, payloadCodec);

        OperationPrepareUseCase.PrepareResult first = useCase.prepare(
                context, "create order", "zh-CN", "UTC", "client-request-1");
        OperationPrepareUseCase.PrepareResult second = useCase.prepare(
                context, "create order", "zh-CN", "UTC", "client-request-1");

        assertThat(first.operationId()).isEqualTo(second.operationId());
        ArgumentCaptor<OperationRecord> candidates = ArgumentCaptor.forClass(OperationRecord.class);
        verify(repository, org.mockito.Mockito.times(2))
                .saveOrGetByIdempotencyKey(candidates.capture());
        assertThat(candidates.getAllValues())
                .extracting(OperationRecord::idempotencyKey)
                .containsOnly(candidates.getValue().idempotencyKey());
        assertThat(candidates.getValue().idempotencyKey()).hasSize(64);
    }

    private static CapabilityManifest manifest() {
        CapabilityManifest.Metadata metadata = new CapabilityManifest.Metadata(
                "order.create", "1.0.0",
                new CapabilityManifest.Owner("orders", "orders@example.com"), List.of());
        ProtocolBinding binding = new ProtocolBinding(
                Protocol.DUBBO, "main", "com.example.OrderService", null, "1.0.0",
                "create", List.of(), "hessian2", List.of(), null);
        OutputContract output = new OutputContract(
                OutputMode.DIRECT, null, List.of(), Map.of(), List.of(), 4096);
        CapabilityManifest.Spec spec = new CapabilityManifest.Spec(
                "Create order", "Creates an order",
                new CapabilityManifest.Examples(List.of(), List.of(), List.of()),
                RiskLevel.WRITE_LOW, Map.of("properties", Map.of()), null, binding, output,
                new ResiliencePolicy(1000L, 0, 1, false));
        return new CapabilityManifest("gateway.ai/v1", "Capability", metadata, spec);
    }
}
