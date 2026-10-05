package ru.practicum.crm.tenant.context;

import java.util.UUID;
import org.springframework.stereotype.Component;
import ru.practicum.crm.common.model.TenantIds;
import ru.practicum.crm.tenant.api.TenantContext;

@Component
public class ThreadLocalTenantContext implements TenantContext {

    public void setTenantId(UUID tenantId) {
        TenantIds.set(tenantId);
    }

    @Override
    public UUID getCurrentTenantId() {
        return TenantIds.get();
    }

    public void clear() {
        TenantIds.clear();
    }
}
