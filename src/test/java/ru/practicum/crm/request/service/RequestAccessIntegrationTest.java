package ru.practicum.crm.request.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.crm.common.error.NotFoundException;

/**
 * Доступ к заявке с учётом владения на реальной базе и с настоящей проверкой права
 * ({@code @PreAuthorize}) — T-030, DoD #31. Пользователь задаётся в контексте безопасности так
 * же, как его туда кладёт проверка access-токена (T-026): субъект — идентификатор, права —
 * коды.
 */
class RequestAccessIntegrationTest extends BaseIntegrationTest {

    private static final UUID CLIENT = UUID.randomUUID();
    private static final UUID OTHER_CLIENT = UUID.randomUUID();
    private static final UUID OPERATOR = UUID.randomUUID();

    @Autowired
    private RequestAccess access;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID tenantId;
    private UUID ownRequest;
    private UUID foreignRequest;

    @BeforeEach
    void setUp() {
        tenantId = insertTenant();
        ownRequest = insertRequest(tenantId, CLIENT);
        foreignRequest = insertRequest(tenantId, OTHER_CLIENT);
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void readable_withReadOwnPermission_returnsOwnRequest() {
        signIn(CLIENT, "REQUEST_READ_OWN");

        assertThat(access.readable(tenantId, ownRequest).getId()).isEqualTo(ownRequest);
    }

    /** DoD: клиент, запросивший чужую заявку, получает «не найдено», а не «запрещено». */
    @Test
    void readable_withReadOwnPermission_hidesForeignRequestAsNotFound() {
        signIn(CLIENT, "REQUEST_READ_OWN");

        assertThatThrownBy(() -> access.readable(tenantId, foreignRequest))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void readable_withReadAllPermission_returnsAnyRequestOfTenantButNotOfAnotherTenant() {
        UUID requestOfAnotherTenant = insertRequest(insertTenant(), OTHER_CLIENT);
        signIn(OPERATOR, "REQUEST_READ_ALL");

        assertThat(access.readable(tenantId, foreignRequest).getId()).isEqualTo(foreignRequest);
        assertThatThrownBy(() -> access.readable(tenantId, requestOfAnotherTenant))
                .isInstanceOf(NotFoundException.class);
    }

    /** Без права читать заявки — отказ (403) ещё до поиска заявки, даже своей. */
    @Test
    void readable_withoutReadPermission_isDenied() {
        signIn(CLIENT, "REQUEST_CREATE");

        assertThatThrownBy(() -> access.readable(tenantId, ownRequest))
                .isInstanceOf(AccessDeniedException.class);
    }

    private static void signIn(UUID userId, String... permissions) {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
                userId.toString(), null, AuthorityUtils.createAuthorityList(permissions)));
    }

    private UUID insertTenant() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO tenants (id, name, slug, active, created_at, updated_at)"
                + " VALUES (?, 'Арендатор', ?, true, now(), now())", id, "tenant-" + id);
        return id;
    }

    private UUID insertRequest(UUID tenant, UUID authorId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO requests (id, tenant_id, subject, description, status,"
                + " author_id, resolution_overdue, deleted, version, created_at, updated_at)"
                + " VALUES (?, ?, 'Тема', 'Описание', 'NEW', ?, false, false, 0, now(), now())",
                id, tenant, authorId);
        return id;
    }
}
