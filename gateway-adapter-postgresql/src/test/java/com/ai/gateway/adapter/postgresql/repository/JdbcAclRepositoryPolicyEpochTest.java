package com.ai.gateway.adapter.postgresql.repository;

import com.ai.gateway.domain.model.CapabilityAclEntry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdbcAclRepositoryPolicyEpochTest {

    @Test
    void readsAndAtomicallyIncrementsPolicyEpoch() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(contains("SELECT policy_epoch"), eq(Long.class)))
                .thenReturn(7L);
        when(jdbc.queryForObject(contains("RETURNING policy_epoch"), eq(Long.class)))
                .thenReturn(8L);
        JdbcAclRepository repository = new JdbcAclRepository(jdbc);

        assertThat(repository.currentPolicyEpoch()).isEqualTo(7L);
        assertThat(repository.incrementPolicyEpoch()).isEqualTo(8L);
    }

    @Test
    void aclMutationAndEpochIncrementShareTransactionalBoundary() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(contains("UPDATE capability_acl"),
                org.mockito.ArgumentMatchers.<Object>any())).thenReturn(1);
        when(jdbc.queryForObject(contains("RETURNING policy_epoch"), eq(Long.class)))
                .thenReturn(9L);
        JdbcAclRepository repository = new JdbcAclRepository(jdbc);
        CapabilityAclEntry entry = new CapabilityAclEntry(
                "orders.detail.query", "1.0.0", List.of("user"),
                List.of("orders:detail:read"), Instant.now(), "admin");

        repository.saveAclEntry(entry);

        assertThat(JdbcAclRepository.class
                .getMethod("saveAclEntry", CapabilityAclEntry.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
        verify(jdbc).queryForObject(contains("RETURNING policy_epoch"), eq(Long.class));
    }
}
