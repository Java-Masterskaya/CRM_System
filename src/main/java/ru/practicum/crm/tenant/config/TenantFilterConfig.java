package ru.practicum.crm.tenant.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.transaction.TransactionManagerCustomizers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionManager;
import ru.practicum.crm.tenant.api.TenantActiveChecker;
import ru.practicum.crm.tenant.api.TenantContext;
import ru.practicum.crm.tenant.api.TenantContextFilter;
import ru.practicum.crm.tenant.persistence.TenantAwareJpaTransactionManager;

@Configuration
public class TenantFilterConfig {

    @Bean
    public TenantContextFilter tenantContextFilter(TenantActiveChecker tenantActiveChecker,
                                                   ObjectMapper objectMapper,
                                                   TenantContext tenantContext) {
        return new TenantContextFilter(tenantActiveChecker, objectMapper, tenantContext);
    }

    @Bean
    public PlatformTransactionManager transactionManager(
            EntityManagerFactory entityManagerFactory,
            TenantContext tenantContext,
            ObjectProvider<TransactionManagerCustomizers> customizers
    ) {
        TenantAwareJpaTransactionManager transactionManager =
                new TenantAwareJpaTransactionManager(entityManagerFactory, tenantContext);
        customizers.ifAvailable(items -> items.customize((TransactionManager) transactionManager));
        return transactionManager;
    }
}
