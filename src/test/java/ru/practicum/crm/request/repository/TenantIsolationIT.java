package ru.practicum.crm.request.repository;

import static org.assertj.core.api.AssertionsForClassTypes.catchThrowableOfType;
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.common.error.NotFoundException;
import ru.practicum.crm.request.domain.Request;
import ru.practicum.crm.request.domain.RequestStatus;
import ru.practicum.crm.tenant.api.TenantContext;

public class TenantIsolationIT extends BaseIntegrationTest {

    @Autowired
    private RequestRepository requestRepository;

    @Autowired
    private TenantContext tenantContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    private UUID tenantA;
    private UUID tenantB;
    private UUID authorId;

    @BeforeEach
    void createTenant() {
        tenantA = insertTenant("Арендатор А");
        tenantB = insertTenant("Арендатор Б");
        authorId = UUID.randomUUID();
    }

    @AfterEach
    void clearTenantContext() {
        tenantContext.clear();
    }

    @Test
    void list_whenFilterEnabled_containsOnlyCurrentTenant() {
        insertRequest(tenantA, "Заявка А");
        insertRequest(tenantB, "Заявка Б");

        tenantContext.setTenantId(tenantA);
        String query = "SELECT subject FROM requests WHERE deleted = FALSE LIMIT 20";

        List<String> subjects = transactionTemplate.execute(status ->
                jdbcTemplate.queryForList(query, String.class, 20));

        assertThat(subjects).containsExactly("Заявка А");
    }

    @Test
    void get_whenIdBelongsToAnotherTenant_isFoundNotForbidden() {

        UUID foreignId = insertRequest(tenantA, "Чужая заявка");

        tenantContext.setTenantId(tenantB);
        String query = "SELECT subject FROM requests WHERE deleted = FALSE AND id = ? LIMIT 1";

        Optional<String> found = transactionTemplate.execute(status ->
                jdbcTemplate.query(query, (rs, rowNumber) ->
                                rs.getString("subject"), foreignId
                        )
                        .stream().findFirst());

        assertThat(found).isEmpty();
        NotFoundException error = catchThrowableOfType(
                () -> {
                    Assertions.assertNotNull(found);
                    found.orElseThrow(() ->
                            new NotFoundException(ErrorCode.NOT_FOUND, "Заявка не найдена"));
                },
                NotFoundException.class
        );

        assertThat(error.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(error.getErrorCode().getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(error.getErrorCode().getStatus()).isNotEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void delete_whenBelongsToAnotherTenant_doesNotRemoveRow() {
        UUID foreignId = insertRequest(tenantA, "Живая");

        tenantContext.setTenantId(tenantB);
        String query = "UPDATE requests SET deleted = TRUE WHERE id = ? AND deleted = FALSE";

        Integer deleted = transactionTemplate.execute(status ->
                jdbcTemplate.update(query, foreignId));

        assertThat(deleted).isNotNull().isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM requests WHERE id = ?",
                Integer.class, foreignId)).isEqualTo(1);
    }

    @Test
    void update_whenIdBelongsToAnotherTenant_doesNotChangeRow() {
        UUID foreignId = insertRequest(tenantA, "Исходная");

        tenantContext.setTenantId(tenantB);
        Integer updated = transactionTemplate.execute(status ->
                entityManager.createQuery(
                                "update Request as r set r.subject = :subject where r.id = :id")
                        .setParameter("subject", "Подмена")
                        .setParameter("id", foreignId)
                        .executeUpdate());

        assertThat(updated).isNotNull().isZero();
        assertThat(subjectOf(foreignId)).isEqualTo("Исходная");
    }

    @Test
    void save_whenBodyHasForeignTenant_storesTenantFromContext() {
        tenantContext.setTenantId(tenantA);

        Request request = new Request(
                tenantB,
                authorId,
                "Новая",
                "Описание",
                RequestStatus.NEW
        );

        Request findRequest = transactionTemplate.execute(status ->
                requestRepository.save(request));

        Assertions.assertNotNull(findRequest);
        UUID id = findRequest.getId();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT tenant_id FROM requests WHERE id = ?", UUID.class, id)
        ).isEqualTo(tenantA);
    }

    @Test
    void nativeSelect_whenAppRoleAndTenantSet_hidesOtherTenant() {
        UUID idA = insertRequest(tenantA, "Только А");
        UUID idB = insertRequest(tenantB, "Только Б");

        List<UUID> seen = jdbcTemplate.execute((Connection con) -> {
            boolean previewsAutoCommit = con.getAutoCommit();
            con.setAutoCommit(false);
            try {
                try (Statement reset = con.createStatement()) {
                    reset.execute("SET SESSION AUTHORIZATION crm_app");
                }
                try (PreparedStatement setting = con.prepareStatement(
                        "SELECT set_config('app.tenant_id', ?, true)")) {
                    setting.setString(1, tenantA.toString());
                    setting.execute();
                }

                List<UUID> ids = new ArrayList<>();
                try (var query = con.prepareStatement("SELECT id FROM requests ORDER BY subject");
                        ResultSet resultSet = query.executeQuery()) {
                    while (resultSet.next()) {
                        ids.add(resultSet.getObject(1, UUID.class));
                    }
                }

                try (PreparedStatement update = con.prepareStatement(
                        "UPDATE requests SET subject = 'Подмена' WHERE id = ?")) {
                    update.setObject(1, idA);
                    assertThat(update.executeUpdate()).isEqualTo(1);
                }

                try (PreparedStatement update = con.prepareStatement(
                        "UPDATE requests SET subject = 'Подмена' WHERE id = ?")) {
                    update.setObject(1, idB);
                    assertThat(update.executeUpdate()).isZero();
                }

                return ids;
            } finally {
                try (Statement restore = con.createStatement()) {
                    restore.execute("SET SESSION AUTHORIZATION DEFAULT");
                    restore.execute("SELECT set_config('app.tenant_id', '', false)");
                }
                con.commit();
                con.setAutoCommit(previewsAutoCommit);
            }
        });

        assertThat(seen).containsExactly(idA);
        assertThat(subjectOf(idA)).isEqualTo("Подмена");
        assertThat(subjectOf(idB)).isEqualTo("Только Б");
    }

    private UUID insertTenant(String name) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO tenants (id, name, active, created_at, updated_at)
                VALUES (?, ?, true, now(), now())
                """, id, name);
        return id;
    }

    private UUID insertRequest(UUID tenantId, String subject) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO requests
                    (id, tenant_id, subject, description, status, author_id, overdue, deleted,
                     version, created_at, updated_at)
                VALUES (?, ?, ?, 'Описание', 'NEW', ?, false, false, 0, now(), now())
                """, id, tenantId, subject, authorId);
        return id;
    }

    private String subjectOf(UUID id) {
        return jdbcTemplate.queryForObject(
                "SELECT subject FROM requests WHERE id = ?", String.class, id
        );
    }
}
