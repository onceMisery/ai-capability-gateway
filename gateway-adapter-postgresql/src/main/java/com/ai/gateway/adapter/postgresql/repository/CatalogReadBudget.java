package com.ai.gateway.adapter.postgresql.repository;

/** Hard limits applied while reading a catalog snapshot from PostgreSQL. */
public record CatalogReadBudget(int maxRows, int queryTimeoutSeconds,
                                long maxPayloadBytes) {

    public CatalogReadBudget {
        if (maxRows <= 0) {
            throw new IllegalArgumentException("maxRows must be positive");
        }
        if (queryTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("queryTimeoutSeconds must be positive");
        }
        if (maxPayloadBytes <= 0) {
            throw new IllegalArgumentException("maxPayloadBytes must be positive");
        }
    }
}
