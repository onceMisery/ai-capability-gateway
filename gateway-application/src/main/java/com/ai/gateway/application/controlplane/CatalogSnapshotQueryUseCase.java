package com.ai.gateway.application.controlplane;

import com.ai.gateway.domain.model.CatalogSnapshot;
import com.ai.gateway.domain.port.CatalogPort;

import java.util.Objects;
import java.util.Optional;

/** Owns read access to historical catalog snapshots for admin adapters. */
public final class CatalogSnapshotQueryUseCase {

    private final CatalogPort catalogPort;

    public CatalogSnapshotQueryUseCase(CatalogPort catalogPort) {
        this.catalogPort = Objects.requireNonNull(catalogPort);
    }

    public Optional<CatalogSnapshot> find(long snapshotVersion) {
        if (snapshotVersion < 1) {
            return Optional.empty();
        }
        return Optional.ofNullable(catalogPort.loadSnapshot(snapshotVersion));
    }
}
