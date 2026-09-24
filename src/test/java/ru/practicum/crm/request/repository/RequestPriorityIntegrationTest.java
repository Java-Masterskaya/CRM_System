package ru.practicum.crm.request.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.crm.request.domain.Request;
import ru.practicum.crm.request.domain.RequestPriority;
import ru.practicum.crm.request.domain.RequestStatus;
import ru.practicum.crm.request.domain.RequestType;

class RequestPriorityIntegrationTest extends BaseIntegrationTest {

    private static final String MIGRATION_VERSION = "202609232308";

    @Autowired
    private RequestRepository requests;

    @Autowired
    private RequestTypeRepository types;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID tenantId;

    @BeforeEach
    void createTenant() {
        tenantId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO tenants (id, name, active, created_at, updated_at)"
                + " VALUES (?, 'Арендатор', true, now(), now())", tenantId);
    }

    @Test
    void migration_whenApplied_isRecordedAsSuccessful() {
        Boolean success = jdbcTemplate.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = ?",
                Boolean.class, MIGRATION_VERSION);

        assertThat(success).isTrue();
    }

    @Test
    void newRequest_whenPriorityNotGiven_takesDefaultOfItsType() {
        RequestType type = saveType("Срочная выгрузка", RequestPriority.HIGH);

        UUID id = requests.save(newRequest("Без приоритета", type, null)).getId();

        Request saved = requests.findByIdAndTenantIdAndDeletedFalse(id, tenantId).orElseThrow();
        assertThat(saved.getPriority()).isEqualTo(RequestPriority.HIGH);
        assertThat(saved.getPriorityRank()).isEqualTo(RequestPriority.HIGH.getRank());
    }

    @Test
    void newRequest_whenNeitherClientNorTypeGavePriority_getsNormal() {
        RequestType type = saveType("Обычная выгрузка", null);

        UUID id = requests.save(newRequest("Без приоритета", type, null)).getId();

        assertThat(requests.findByIdAndTenantIdAndDeletedFalse(id, tenantId).orElseThrow()
                .getPriority()).isEqualTo(RequestPriority.NORMAL);
    }

    @Test
    void typeDefault_whenChangedAfterRequestCreated_leavesThatRequestAsItWas() {
        RequestType type = saveType("Выгрузка", RequestPriority.HIGH);
        UUID id = requests.save(newRequest("Создана при HIGH", type, null)).getId();

        type.describe(type.getName(), type.getDescription(), RequestPriority.LOW);
        types.save(type);

        assertThat(requests.findByIdAndTenantIdAndDeletedFalse(id, tenantId).orElseThrow()
                .getPriority()).isEqualTo(RequestPriority.HIGH);
    }

    @Test
    void sortByPriority_whenDescending_returnsMostUrgentFirst() {
        RequestType type = saveType("Выгрузка", null);
        requests.save(newRequest("Низкий", type, RequestPriority.LOW));
        requests.save(newRequest("Срочный", type, RequestPriority.URGENT));
        requests.save(newRequest("Обычный", type, RequestPriority.NORMAL));
        requests.save(newRequest("Высокий", type, RequestPriority.HIGH));

        assertThat(requests.findByTenantIdAndDeletedFalse(tenantId,
                        PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "priorityRank"))))
                .extracting(Request::getSubject)
                .containsExactly("Срочный", "Высокий", "Обычный", "Низкий");
    }

    @Test
    void priority_whenWrittenDirectlyOutsideAllowedSet_isRejectedByDatabase() {
        assertThatThrownBy(() -> jdbcTemplate.update("INSERT INTO requests (id, tenant_id, subject,"
                + " description, status, author_id, priority, created_at, updated_at)"
                + " VALUES (?, ?, 'тема', 'описание', 'NEW', ?, 'SUPER', now(), now())",
                UUID.randomUUID(), tenantId, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("requests_priority_check");
    }

    @Test
    void priorityRank_whenDivergesFromPriority_isRejectedByDatabase() {
        RequestType type = saveType("Выгрузка", null);
        UUID id = requests.save(newRequest("Срочный", type, RequestPriority.URGENT)).getId();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE requests SET priority_rank = 1 WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("requests_priority_check");
    }

    @Test
    void insert_whenPriorityOmittedOutsideCode_getsConsistentNormalPair() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO requests (id, tenant_id, subject, description, status,"
                + " author_id, created_at, updated_at)"
                + " VALUES (?, ?, 'тема', 'описание', 'NEW', ?, now(), now())",
                id, tenantId, UUID.randomUUID());

        String stored = jdbcTemplate.queryForObject(
                "SELECT priority || ':' || priority_rank FROM requests WHERE id = ?",
                String.class, id);

        assertThat(stored).isEqualTo("NORMAL:2");
    }

    @Test
    void typeDefaultPriority_whenWrittenDirectlyOutsideAllowedSet_isRejectedByDatabase() {
        RequestType type = saveType("Выгрузка", null);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE request_types SET default_priority = 'SUPER' WHERE id = ?",
                type.getId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("request_types_default_priority_check");
    }

    private RequestType saveType(String name, RequestPriority defaultPriority) {
        RequestType type = new RequestType(tenantId, name);
        type.describe(name, null, defaultPriority);
        return types.save(type);
    }

    /**
     * Так будет создавать заявку T-036: приоритет выбирается правилом {@link
     * RequestPriority#resolve} и копируется в заявку.
     */
    private Request newRequest(String subject, RequestType type, RequestPriority requested) {
        Request request = new Request(tenantId, UUID.randomUUID(), subject, "Описание",
                RequestStatus.NEW);
        request.setTypeId(type.getId());
        request.setPriority(RequestPriority.resolve(requested, type.getDefaultPriority()));
        return request;
    }
}
