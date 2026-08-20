package com.ai.gateway.adapter.postgresql.repository;

import com.ai.gateway.domain.model.CapabilityLifecycle;
import org.junit.jupiter.api.Test;
import org.springframework.dao.IncorrectUpdateSemanticsDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link JdbcManifestRepository} 的单元测试，验证校验与审批记录的持久化暴露，以及清单不存在时
 * 生命周期更新失败。
 *
 * @author cmiracle@163.com
 */
class JdbcManifestRepositoryTest {

    @Test
    void manifestRepositoryExposesValidationAndApprovalRecordPersistence() throws Exception {
        assertThat(com.ai.gateway.domain.port.ManifestRepository.class.getMethod(
                "recordValidation", String.class, String.class,
                com.ai.gateway.domain.model.ValidationReport.class)).isNotNull();
        assertThat(com.ai.gateway.domain.port.ManifestRepository.class.getMethod(
                "recordApproval", String.class, String.class, String.class,
                String.class, com.ai.gateway.domain.model.ConfirmationSummary.class)).isNotNull();
    }

    @Test
    void lifecycleUpdateFailsWhenManifestDoesNotExist() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), org.mockito.ArgumentMatchers.<Object[]>any())).thenReturn(0);

        JdbcManifestRepository repository = new JdbcManifestRepository(jdbc);

        assertThatThrownBy(() -> repository.updateLifecycle(
                "missing.capability", "1.0.0", CapabilityLifecycle.VALIDATED))
                .isInstanceOf(IncorrectUpdateSemanticsDataAccessException.class);
    }
}
