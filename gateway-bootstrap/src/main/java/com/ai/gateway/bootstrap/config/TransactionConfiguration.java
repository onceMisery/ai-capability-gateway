package com.ai.gateway.bootstrap.config;

import com.ai.gateway.domain.port.TransactionPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;

/**
 * 将 {@link TransactionPort} 接入 Spring 的事务基础设施。
 *
 * <p>适配 {@link TransactionTemplate}，使与框架无关的应用用例能够在多个
 * 仓储之间划定单一事务边界。标注了 {@code @Transactional} 且使用默认
 * {@code REQUIRED} 传播行为的仓储方法会加入该边界。</p>
 *
 * @since 0.1.0
 * @author cmiracle@163.com
 */
@Configuration
public class TransactionConfiguration {

    @Bean
    public TransactionPort transactionPort(PlatformTransactionManager transactionManager) {
        return new SpringTransactionPortAdapter(transactionManager);
    }

    /**
     * 基于 Spring 的 {@link TransactionPort} 实现。
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
