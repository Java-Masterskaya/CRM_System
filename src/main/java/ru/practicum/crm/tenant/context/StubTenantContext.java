package ru.practicum.crm.tenant.context;

import java.util.UUID;
import org.springframework.stereotype.Component;
import ru.practicum.crm.tenant.api.context.TenantContext;

// TODO: Необходимо заменить на реальную реализацию,
//  которая будет получать текущего арендодателя из контекста безопасности.
@Component
class StubTenantContext implements TenantContext {

    private static final UUID STUB_TENANT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Override
    public UUID getCurrentTenantId() {
        return STUB_TENANT_ID;
    }
}
