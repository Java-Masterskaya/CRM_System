package ru.practicum.crm.tenant.persistence;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.practicum.crm.tenant.api.TenantContext;

public class TenantAwareJpaTransactionManager extends JpaTransactionManager {

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2")
    private final TenantContext tenantContext;

    public TenantAwareJpaTransactionManager(
            EntityManagerFactory entityManagerFactory,
            TenantContext tenantContext
    ) {
        super(entityManagerFactory);
        this.tenantContext = tenantContext;
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        super.doBegin(transaction, definition);

        EntityManagerHolder holder = (EntityManagerHolder) TransactionSynchronizationManager
                .getResource(obtainEntityManagerFactory());

        if (holder == null) {
            return;
        }

        TenantPersistence.bind(holder.getEntityManager(), tenantContext.getCurrentTenantId());
    }
}
