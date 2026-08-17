package com.ai.gateway.bootstrap.config;

import com.ai.gateway.domain.port.TransactionPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;

/**
 * Wires the {@link TransactionPort} to Spring's transaction infrastructure.
 *
 * <p>Adapts {@link TransactionTemplate} so application use cases (which are
 * framework-free) can demarcate a single transaction across multiple
 * repositories. Repository methods annotated {@code @Transactional} with the
 * default {@code REQUIRED} propagation join this boundary.</p>
 *
 * @since 0.1.0
 */
@Configuration
public class TransactionConfiguration {

    @Bean
    public TransactionPort transactionPort(PlatformTransactionManager transactionManager) {
        return new SpringTransactionPortAdapter(transactionManager);
    }

    /**
     * Spring-backed {@link TransactionPort} implementation.
     */
    static final class SpringTransactionPortAdapter implements TransactionPort {

        private final TransactionTemplate transactionTemplate;

        SpringTransactionPortAdapter(PlatformTransactionManager transactionManager) {
            Objects.requireNonNull(transactionManager, "transactionManager must not be null");
            this.transactionTemplate = new TransactionTemplate(transactionManager);
        }

        @Override
        public <T> T inTransaction(TransactionWork<T> work) {
            Objects.requireNonNull(work, "work must not be null");
            return transactionTemplate.execute(status -> work.execute());
        }
    }
}
