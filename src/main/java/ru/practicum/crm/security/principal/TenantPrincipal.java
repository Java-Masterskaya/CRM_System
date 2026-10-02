package ru.practicum.crm.security.principal;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public record TenantPrincipal(UUID tenantId) implements UserDetails {

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of();
    }

    @Override
    public String getPassword() {
        return null;
    }

    @Override
    public String getUsername() {
        // TODO: временное решение — используем username как переносчик tenantId,
        // пока не появится нормальный Principal с отдельным полем tenantId (T-026).
        return tenantId.toString();
    }
}
