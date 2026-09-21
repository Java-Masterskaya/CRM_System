package ru.practicum.crm.request.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.crm.request.domain.Request;
import ru.practicum.crm.request.domain.RequestStatus;
import ru.practicum.crm.request.domain.RequestType;

class RequestTypeRepositoryIntegrationTest extends BaseIntegrationTest {

    private static final String MIGRATION_VERSION = "202609211034";

    @Autowired
    private RequestTypeRepository repository;

    @Autowired
    private RequestRepository requestRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID tenantA;
    private UUID tenantB;

    @BeforeEach
    void createTenants() {
        tenantA = createTenant("Арендатор А");
        tenantB = createTenant("Арендатор Б");
    }

    @Test
    void migration_whenApplied_isRecordedAsSuccessful() {
        Boolean success = jdbcTemplate.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = ?",
                Boolean.class, MIGRATION_VERSION);

        assertThat(success).isTrue();
    }

    @Test
    void save_whenTypeFilled_readsBackEveryField() {
        RequestType type = new RequestType(tenantA, "Выгрузка данных");
        type.describe("Выгрузка данных", "Отчёты и витрины", "HIGH");

        UUID id = repository.save(type).getId();
        RequestType saved = repository.findByIdAndTenantId(id, tenantA).orElseThrow();

        assertThat(saved.getTenantId()).isEqualTo(tenantA);
        assertThat(saved.getName()).isEqualTo("Выгрузка данных");
        assertThat(saved.getDescription()).isEqualTo("Отчёты и витрины");
        assertThat(saved.getDefaultPriority()).isEqualTo("HIGH");
        assertThat(saved.isActive()).isTrue();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    void findById_whenTypeBelongsToAnotherTenant_returnsNothing() {
        UUID id = repository.save(new RequestType(tenantA, "Выгрузка")).getId();

        assertThat(repository.findByIdAndTenantId(id, tenantB)).isEmpty();
        assertThat(repository.findByIdAndTenantId(id, tenantA)).isPresent();
    }

    @Test
    void list_whenAnotherTenantHasTypes_returnsOnlyOwnSortedByName() {
        repository.save(new RequestType(tenantA, "Импорт"));
        repository.save(new RequestType(tenantA, "Выгрузка"));
        repository.save(new RequestType(tenantB, "Чужой тип"));

        assertThat(repository.findByTenantIdOrderByNameAsc(tenantA))
                .extracting(RequestType::getName)
                .containsExactly("Выгрузка", "Импорт");
    }

    @Test
    void listActive_whenTypeDisabled_skipsItButKeepsItInFullList() {
        RequestType disabled = repository.save(new RequestType(tenantA, "Старый тип"));
        disabled.deactivate();
        repository.save(disabled);
        repository.save(new RequestType(tenantA, "Действующий тип"));

        assertThat(repository.findByTenantIdAndActiveTrueOrderByNameAsc(tenantA))
                .extracting(RequestType::getName)
                .containsExactly("Действующий тип");
        assertThat(repository.findByTenantIdOrderByNameAsc(tenantA))
                .extracting(RequestType::getName)
                .containsExactly("Действующий тип", "Старый тип");
    }

    @Test
    void save_whenNameRepeatedInsideTenant_isRejectedByDatabase() {
        repository.save(new RequestType(tenantA, "Выгрузка"));

        assertThatThrownBy(() -> repository.save(new RequestType(tenantA, "Выгрузка")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("request_types_name_unique");
    }

    @Test
    void save_whenSameNameUsedByAnotherTenant_isAllowed() {
        repository.save(new RequestType(tenantA, "Выгрузка"));

        RequestType foreign = repository.save(new RequestType(tenantB, "Выгрузка"));

        assertThat(repository.findByIdAndTenantId(foreign.getId(), tenantB)).isPresent();
    }

    @Test
    void delete_whenTypeIsUsedByRequest_isRejectedByForeignKey() {
        UUID typeId = repository.save(new RequestType(tenantA, "Выгрузка")).getId();
        Request request = new Request(tenantA, UUID.randomUUID(), "Тема", "Описание",
                RequestStatus.NEW);
        request.setTypeId(typeId);
        requestRepository.save(request);

        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM request_types WHERE id = ?",
                typeId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("requests_type_id_fkey");
    }

    @Test
    void request_whenTypeDoesNotExist_isRejectedByForeignKey() {
        Request request = new Request(tenantA, UUID.randomUUID(), "Тема", "Описание",
                RequestStatus.NEW);
        request.setTypeId(UUID.randomUUID());

        assertThatThrownBy(() -> requestRepository.save(request))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("requests_type_id_fkey");
    }

    @Test
    void request_whenItsTypeDisabledAfterCreation_isStillReadable() {
        RequestType type = repository.save(new RequestType(tenantA, "Выгрузка"));
        Request request = new Request(tenantA, UUID.randomUUID(), "Тема", "Описание",
                RequestStatus.NEW);
        request.setTypeId(type.getId());
        UUID requestId = requestRepository.save(request).getId();

        type.deactivate();
        repository.save(type);

        assertThat(requestRepository.findByIdAndTenantIdAndDeletedFalse(requestId, tenantA))
                .get()
                .extracting(Request::getTypeId)
                .isEqualTo(type.getId());
    }

    @Test
    void insertWithUnknownTenant_whenWrittenDirectlyToDatabase_isRejected() {
        String insertWithUnknownTenant =
                """
                INSERT INTO request_types (id, tenant_id, name, active, created_at, updated_at)
                VALUES (?, ?, 'Выгрузка', true, now(), now())
                """;

        assertThatThrownBy(() -> jdbcTemplate.update(insertWithUnknownTenant, UUID.randomUUID(),
                UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID createTenant(String name) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO tenants (id, name, active, created_at, updated_at)"
                + " VALUES (?, ?, true, now(), now())", id, name);
        return id;
    }
}
