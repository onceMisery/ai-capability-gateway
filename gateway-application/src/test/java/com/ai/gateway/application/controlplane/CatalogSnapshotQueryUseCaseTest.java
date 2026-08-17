package com.ai.gateway.application.controlplane;

import com.ai.gateway.domain.model.CatalogSnapshot;
import com.ai.gateway.domain.port.CatalogPort;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CatalogSnapshotQueryUseCaseTest {

    @Test
    void loadsSnapshotThroughQueryUseCase() {
        CatalogPort catalog = mock(CatalogPort.class);
        CatalogSnapshot snapshot = new CatalogSnapshot(3L, "production", List.of(), "policy", "digest");
        when(catalog.loadSnapshot(3L)).thenReturn(snapshot);

        assertThat(new CatalogSnapshotQueryUseCase(catalog).find(3L)).containsSame(snapshot);
    }
}
