package ru.practicum.crm.tenant.context;

import java.util.UUID;
import org.springframework.stereotype.Component;
import ru.practicum.crm.tenant.api.TenantContext;

@Component
public class ThreadLocalTenantContext implements TenantContext {

    private static final ThreadLocal<UUID> CONTEXT = new ThreadLocal<>();

    public void setTenantId(UUID tenantId) {
        CONTEXT.set(tenantId);
    }

    @Override
    public UUID getCurrentTenantId() {
        return CONTEXT.get();
    }

    public void clear() {
        CONTEXT.remove();
    }
}
