package com.ai.gateway.application.operation;

import com.ai.gateway.application.runtime.NaturalLanguageQueryUseCase;
import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.CatalogSnapshot;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OperationPrepareStructuredTest {

    @Test
    void persistsPreparedWriteWithoutNaturalLanguageRoutingOrProviderCall() {
        Fixtures fixtures = new Fixtures(RiskLevel.WRITE_LOW, 11L);
        when(fixtures.repository.saveOrGetByIdempotencyKey(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        OperationPrepareUseCase.PrepareResult result = fixtures.useCase().prepareStructured(
                RequestContext.empty(), "req-1", "order.create", "1.0.0",
                Map.of(), "zh-CN", 11L, "agent-call-1");

        assertThat(result.success()).isTrue();
        assertThat(result.token()).isNotNull();
        verify(fixtures.routing, never()).execute(any(), anyString(), anyString(), anyString());
        verify(fixtures.repository).saveOrGetByIdempotencyKey(any(OperationRecord.class));
        verify(fixtures.encryption).encrypt("[]");
    }

    @Test
    void rejectsStaleSnapshotBeforePersistingArguments() {
        Fixtures fixtures = new Fixtures(RiskLevel.WRITE_LOW, 11L);

        OperationPrepareUseCase.PrepareResult result = fixtures.useCase().prepareStructured(
                RequestContext.empty(), "req-1", "order.create", "1.0.0",
                Map.of(), "zh-CN", 10L, "agent-call-1");

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("snapshot");
        verify(fixtures.repository, never()).saveOrGetByIdempotencyKey(any());
        verify(fixtures.encryption, never()).encrypt(anyString());
    }

    @Test
    void rejectsReadOnlyCapabilityBeforePrepare() {
        Fixtures fixtures = new Fixtures(RiskLevel.READ_ONLY, 11L);

        OperationPrepareUseCase.PrepareResult result = fixtures.useCase().prepareStructured(
                RequestContext.empty(), "req-1", "order.query", "1.0.0",
                Map.of(), "zh-CN", 11L, "agent-call-1");

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("write");
        verify(fixtures.repository, never()).saveOrGetByIdempotencyKey(any());
    }

    private static final class Fixtures {
        private final NaturalLanguageQueryUseCase routing = mock(NaturalLanguageQueryUseCase.class);
        private final TypeConverterRegistry converters = mock(TypeConverterRegistry.class);
        private final SchemaValidator schemaValidator = mock(SchemaValidator.class);
        private final AuthorizationPort authorization = mock(AuthorizationPort.class);
        private final EncryptionPort encryption = mock(EncryptionPort.class);
        private final OperationRepository repository = mock(OperationRepository.class);
        private final CatalogPort catalog = mock(CatalogPort.class);
        private final AuthenticationPort authentication = mock(AuthenticationPort.class);
        private final ConfirmationTokenCodec tokenCodec = mock(ConfirmationTokenCodec.class);
        private final ArgumentPayloadCodec payloadCodec = mock(ArgumentPayloadCodec.class);
        private final Principal principal = new Principal("user-1", 7L, List.of("user"),
                List.of(), Instant.now(), "JWT");
        private final CapabilityManifest manifest;

        private Fixtures(RiskLevel risk, long snapshotVersion) {
            manifest = manifest(risk);
            when(authentication.authenticate(any())).thenReturn(principal);
            when(catalog.loadCurrentSnapshot("production"))
                    .thenReturn(new CatalogSnapshot(snapshotVersion, "production",
                            List.of(manifest), "policy-" + snapshotVersion, "digest"));
            when(authorization.filterVisibleCapabilities(principal, List.of(manifest)))
                    .thenReturn(List.of(manifest));
            when(authorization.authorizeExecution(principal, manifest.metadata().id(),
                    manifest.metadata().version())).thenReturn(true);
            when(schemaValidator.validate(anyMap(), anyMap()))
                    .thenReturn(ValidationReport.success());
            when(payloadCodec.encode(List.of())).thenReturn("[]");
            when(encryption.encrypt("[]")).thenReturn("ciphertext");
            when(tokenCodec.issue(anyString(), anyString(), anyLong(), anyString(), any()))
                    .thenReturn(new ConfirmationToken("token", "operation", "principal",
                            7L, "arguments", "signature", Instant.now().plusSeconds(300), false));
        }

        private OperationPrepareUseCase useCase() {
            return new OperationPrepareUseCase(
                    routing, converters, schemaValidator, authorization, encryption,
                    repository, catalog, authentication, tokenCodec, payloadCodec);
        }

        private static CapabilityManifest manifest(RiskLevel risk) {
            CapabilityManifest.Metadata metadata = new CapabilityManifest.Metadata(
                    risk == RiskLevel.READ_ONLY ? "order.query" : "order.create", "1.0.0",
                    new CapabilityManifest.Owner("orders", "orders@example.com"), List.of());
            ProtocolBinding binding = new ProtocolBinding(
                    Protocol.DUBBO, "main", "com.example.OrderService", null, "1.0.0",
                    risk == RiskLevel.READ_ONLY ? "query" : "create", List.of(), "hessian2",
                    List.of(), Map.of());
            OutputContract output = new OutputContract(
                    OutputMode.DIRECT, null, List.of(), Map.of(), List.of(), 4096);
            return new CapabilityManifest("gateway.ai/v1", "Capability", metadata,
                    new CapabilityManifest.Spec(
                            risk == RiskLevel.READ_ONLY ? "Query order" : "Create order",
                            "Order operation", new CapabilityManifest.Examples(List.of(),
                            List.of(), List.of()), risk, Map.of("type", "object"), null,
                            binding, output, new ResiliencePolicy(1000L, 0, 1, false)));
        }
    }
}
