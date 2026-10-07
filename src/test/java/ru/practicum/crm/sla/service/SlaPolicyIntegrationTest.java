package ru.practicum.crm.sla.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.common.error.ValidationError;
import ru.practicum.crm.common.model.RequestPriority;
import ru.practicum.crm.common.pagination.PageRequests;
import ru.practicum.crm.sla.domain.SlaPolicy;
import ru.practicum.crm.sla.domain.SlaTerms;

/**
 * Политики сроков на реальной базе (T-055): хранение, выбор политики для пары, изоляция
 * арендаторов и ограничения самой базы.
 *
 * <p>Типы заявок создаются запросами SQL: пакет сроков не зависит от пакета заявок, и тесты эту
 * границу тоже не переходят.
 */
class SlaPolicyIntegrationTest extends BaseIntegrationTest {

    private static final String MIGRATION_VERSION = "202610022000";
    private static final PageRequest FIRST_PAGE = PageRequest.of(0, PageRequests.MAX_SIZE);
    private static final SlaTerms URGENT_TERMS = new SlaTerms(30, 240);
    private static final SlaTerms USUAL_TERMS = new SlaTerms(240, 2400);

    @Autowired
    private SlaPolicyService policies;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID tenantA;
    private UUID tenantB;
    private UUID typeA;
    private UUID typeB;

    @BeforeEach
    void setUp() {
        tenantA = insertTenant();
        tenantB = insertTenant();
        typeA = insertRequestType(tenantA);
        typeB = insertRequestType(tenantB);
    }

    @Test
    void migration_whenApplied_isRecordedAsSuccessful() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = ?",
                Boolean.class, MIGRATION_VERSION)).isTrue();
    }

    @Test
    void create_storesPolicyForTypeAndPriorityWithBothTerms() {
        UUID id = policies.create(tenantA, typeA, RequestPriority.URGENT, URGENT_TERMS).getId();

        assertThat(policies.policies(tenantA, FIRST_PAGE).getContent()).singleElement()
                .satisfies(stored -> {
                    assertThat(stored.getId()).isEqualTo(id);
                    assertThat(stored.getTypeId()).isEqualTo(typeA);
                    assertThat(stored.getPriority()).isEqualTo(RequestPriority.URGENT);
                    assertThat(stored.getTerms()).isEqualTo(URGENT_TERMS);
                });
    }

    @Test
    void create_whenPairAlreadyHasPolicy_isRejectedAndStoredOnce() {
        policies.create(tenantA, typeA, RequestPriority.HIGH, USUAL_TERMS);

        ApiException thrown = catchThrowableOfType(
                () -> policies.create(tenantA, typeA, RequestPriority.HIGH, URGENT_TERMS),
                ApiException.class);

        assertThat(thrown.getErrorCode()).isEqualTo(ErrorCode.ALREADY_EXISTS);
        assertThat(policies.policies(tenantA, FIRST_PAGE).getContent()).singleElement()
                .extracting(SlaPolicy::getTerms).isEqualTo(USUAL_TERMS);
    }

    @Test
    void create_forSameTypeAndAnotherPriority_isAllowed() {
        policies.create(tenantA, typeA, RequestPriority.HIGH, USUAL_TERMS);
        policies.create(tenantA, typeA, RequestPriority.URGENT, URGENT_TERMS);

        assertThat(policies.policies(tenantA, FIRST_PAGE).getContent())
                .extracting(SlaPolicy::getPriority)
                .containsExactly(RequestPriority.HIGH, RequestPriority.URGENT);
    }

    @Test
    void create_withTypeOfAnotherTenant_isRejectedAsUnknownTypeAndNothingStored() {
        ApiException thrown = catchThrowableOfType(
                () -> policies.create(tenantA, typeB, RequestPriority.HIGH, USUAL_TERMS),
                ApiException.class);

        assertThat(thrown.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(thrown.getErrors()).containsExactly(
                ValidationError.ofField("typeId", "тип заявки не найден"));
        assertThat(policies.policies(tenantA, FIRST_PAGE).getContent()).isEmpty();
    }

    @Test
    void createDefault_whenTenantAlreadyHasDefault_isRejected() {
        policies.createDefault(tenantA, USUAL_TERMS);

        ApiException thrown = catchThrowableOfType(
                () -> policies.createDefault(tenantA, URGENT_TERMS), ApiException.class);

        assertThat(thrown.getErrorCode()).isEqualTo(ErrorCode.ALREADY_EXISTS);
    }

    @Test
    void resolve_prefersPolicyOfPairAndFallsBackToDefaultOfSameTenant() {
        policies.create(tenantA, typeA, RequestPriority.URGENT, URGENT_TERMS);
        policies.createDefault(tenantA, USUAL_TERMS);
        policies.createDefault(tenantB, new SlaTerms(1, 1));

        assertThat(policies.resolve(tenantA, typeA, RequestPriority.URGENT))
                .map(SlaPolicy::getTerms).contains(URGENT_TERMS);
        assertThat(policies.resolve(tenantA, typeA, RequestPriority.LOW))
                .as("для пары своей политики нет — берётся политика по умолчанию")
                .map(SlaPolicy::getTerms).contains(USUAL_TERMS);
        assertThat(policies.resolve(tenantA, null, RequestPriority.NORMAL))
                .as("заявка без типа").map(SlaPolicy::getTerms).contains(USUAL_TERMS);
    }

    @Test
    void resolve_whenTenantHasNoPolicies_returnsNothing() {
        policies.createDefault(tenantB, USUAL_TERMS);

        assertThat(policies.resolve(tenantA, typeA, RequestPriority.HIGH)).isEmpty();
    }

    @Test
    void changeTerms_updatesTermsAndKeepsPair() {
        UUID id = policies.create(tenantA, typeA, RequestPriority.HIGH, USUAL_TERMS).getId();

        policies.changeTerms(tenantA, id, URGENT_TERMS);

        assertThat(policies.resolve(tenantA, typeA, RequestPriority.HIGH)).hasValueSatisfying(
                changed -> {
                    assertThat(changed.getId()).isEqualTo(id);
                    assertThat(changed.getTerms()).isEqualTo(URGENT_TERMS);
                });
    }

    @Test
    void policy_ofAnotherTenant_isNeitherListedNorChangeable() {
        UUID id = policies.create(tenantA, typeA, RequestPriority.HIGH, USUAL_TERMS).getId();

        ApiException thrown = catchThrowableOfType(
                () -> policies.changeTerms(tenantB, id, URGENT_TERMS), ApiException.class);

        assertThat(thrown.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(policies.policies(tenantB, FIRST_PAGE).getContent()).isEmpty();
        assertThat(policies.policies(tenantA, FIRST_PAGE).getContent()).singleElement()
                .extracting(SlaPolicy::getTerms).isEqualTo(USUAL_TERMS);
    }

    @Test
    void duplicatePair_insertedPastService_isRejectedByDatabase() {
        policies.create(tenantA, typeA, RequestPriority.HIGH, USUAL_TERMS);

        assertThatThrownBy(() -> insertPolicy(tenantA, typeA, "HIGH", 60, 120))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("sla_policies_pair_unique");
    }

    @Test
    void secondDefault_insertedPastService_isRejectedByDatabase() {
        policies.createDefault(tenantA, USUAL_TERMS);

        assertThatThrownBy(() -> insertPolicy(tenantA, null, null, 60, 120))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("sla_policies_default_unique");
    }

    @Test
    void invalidRows_insertedPastService_areRejectedByDatabaseChecks() {
        assertThatThrownBy(() -> insertPolicy(tenantA, typeA, "HIGH", 600, 480))
                .as("срок первого ответа больше срока решения")
                .hasMessageContaining("sla_policies_order_check");
        assertThatThrownBy(() -> insertPolicy(tenantA, typeA, "HIGH", 0, 480))
                .as("неположительный срок")
                .hasMessageContaining("sla_policies_positive_check");
        assertThatThrownBy(() -> insertPolicy(tenantA, typeA, null, 60, 480))
                .as("тип без приоритета")
                .hasMessageContaining("sla_policies_scope_check");
        assertThatThrownBy(() -> insertPolicy(tenantA, typeA, "ASAP", 60, 480))
                .as("приоритет не из набора")
                .hasMessageContaining("sla_policies_priority_check");
    }

    private void insertPolicy(UUID tenantId, UUID typeId, String priority, int firstResponse,
            int resolution) {
        jdbcTemplate.update("INSERT INTO sla_policies (id, tenant_id, type_id, priority,"
                + " first_response_minutes, resolution_minutes, created_at, updated_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, now(), now())",
                UUID.randomUUID(), tenantId, typeId, priority, firstResponse, resolution);
    }

    private UUID insertTenant() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO tenants (id, name, active, created_at, updated_at)"
                + " VALUES (?, 'Арендатор', true, now(), now())", id);
        return id;
    }

    private UUID insertRequestType(UUID tenantId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO request_types (id, tenant_id, name, active, created_at,"
                + " updated_at) VALUES (?, ?, 'Выгрузка', true, now(), now())", id, tenantId);
        return id;
    }
}
