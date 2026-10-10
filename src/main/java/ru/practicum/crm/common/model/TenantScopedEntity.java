package ru.practicum.crm.common.model;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@FilterDef(
        name = TenantIds.FILTER_NAME,
        parameters = @ParamDef(name = TenantIds.PARAM, type = UUID.class)
)
@Filter(name = TenantIds.FILTER_NAME,
        condition = "tenant_id = :" + TenantIds.PARAM
)
@Log4j2
@MappedSuperclass
public abstract class TenantScopedEntity extends BaseEntity {

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    protected TenantScopedEntity(UUID tenantId) {
        this.tenantId = tenantId;
    }

    @PrePersist
    void assignTenantFromContext() {
        UUID fromContext = TenantIds.get();

        if (fromContext != null && tenantId != null && !fromContext.equals(tenantId)) {
            log.warn(" tenantId сущности {} заменен с {} на {} из контекста",
                    getClass().getSimpleName(), tenantId, fromContext
            );
        }

        tenantId = TenantIds.resolve(tenantId);
    }
}
