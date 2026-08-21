package com.ai.gateway.bootstrap.config;

import com.ai.gateway.domain.model.CatalogSnapshot;
import com.ai.gateway.application.catalog.InMemoryCatalogManager;
import com.ai.gateway.domain.port.CatalogPort;
import com.ai.gateway.domain.port.SecretManager;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * GatewayHealthIndicatorTest 类。
 *
 * @author cmiracle@163.com
 */
class GatewayHealthIndicatorTest {

    @Test
    void emptySnapshotIsNotReady() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        CatalogPort catalogPort = mock(CatalogPort.class);
        SecretManager secretManager = key -> "present";
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(5)).thenReturn(true);
        when(catalogPort.loadCurrentSnapshot("staging"))
                .thenReturn(new CatalogSnapshot(0L, "staging", List.of(), null, ""));

        GatewayProperties properties = new GatewayProperties();
        properties.setEnvironment("staging");
        GatewayHealthIndicator indicator = new GatewayHealthIndicator(
                dataSource, catalogPort, secretManager, properties);

        assertThat(indicator.health().getStatus().getCode()).isEqualTo("DOWN");
        assertThat(indicator.health().getDetails().get("snapshot").toString())
                .contains("no active snapshot");
    }

    @Test
    void staleInMemorySnapshotIsNotReadyEvenWhenDatabaseHasAValidSnapshot() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        CatalogPort catalogPort = mock(CatalogPort.class);
        InMemoryCatalogManager catalogManager = mock(InMemoryCatalogManager.class);
        SecretManager secretManager = key -> "present";
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(5)).thenReturn(true);
        CatalogSnapshot persisted = new CatalogSnapshot(8L, "staging", List.of(), "policy", "digest");
        when(catalogPort.loadCurrentSnapshot("staging")).thenReturn(persisted);
        when(catalogManager.getCurrentSnapshot()).thenReturn(
                new CatalogSnapshot(7L, "staging", List.of(), "policy", "digest"));

        GatewayProperties properties = new GatewayProperties();
        properties.setEnvironment("staging");
        GatewayHealthIndicator indicator = new GatewayHealthIndicator(
                dataSource, catalogPort, secretManager, properties, catalogManager);

        assertThat(indicator.health().getStatus().getCode()).isEqualTo("DOWN");
    }
}
