package ru.practicum.crm.sla.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.common.error.ValidationError;
import ru.practicum.crm.common.model.RequestPriority;
import ru.practicum.crm.common.pagination.PageRequests;
import ru.practicum.crm.sla.domain.SlaPolicy;
import ru.practicum.crm.sla.domain.SlaTerms;
import ru.practicum.crm.sla.repository.SlaPolicyRepository;

/**
 * Сервис без базы: репозиторий — заглушка; хранение и ограничения базы проверяет
 * интеграционный тест.
 */
class SlaPolicyServiceTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID TYPE_ID = UUID.randomUUID();
    private static final SlaTerms FOUR_HOURS_AND_FIVE_DAYS = new SlaTerms(240, 2400);

    private final SlaPolicyRepository repository = mock(SlaPolicyRepository.class);
    private final SlaPolicyService service = new SlaPolicyService(repository);

    @Test
    void create_whenPairIsFree_savesPolicyForTypeAndPriority() {
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        SlaPolicy saved = service.create(TENANT_ID, TYPE_ID, RequestPriority.HIGH,
                FOUR_HOURS_AND_FIVE_DAYS);

        verify(repository).saveAndFlush(saved);
        assertThat(saved.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(saved.getTypeId()).isEqualTo(TYPE_ID);
        assertThat(saved.getPriority()).isEqualTo(RequestPriority.HIGH);
        assertThat(saved.getTerms()).isEqualTo(FOUR_HOURS_AND_FIVE_DAYS);
        assertThat(saved.isDefault()).isFalse();
    }

    @Test
    void create_whenPolicyForPairExists_isRejectedAndNothingSaved() {
        when(repository.findByTenantIdAndTypeIdAndPriority(TENANT_ID, TYPE_ID,
                RequestPriority.HIGH)).thenReturn(Optional.of(pairPolicy(RequestPriority.HIGH)));

        ApiException thrown = catchThrowableOfType(() -> service.create(TENANT_ID, TYPE_ID,
                RequestPriority.HIGH, FOUR_HOURS_AND_FIVE_DAYS), ApiException.class);

        assertThat(thrown.getErrorCode()).isEqualTo(ErrorCode.ALREADY_EXISTS);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void create_whenFirstResponseLongerThanResolution_isRejectedWithThatField() {
        ApiException thrown = catchThrowableOfType(() -> service.create(TENANT_ID, TYPE_ID,
                RequestPriority.HIGH, new SlaTerms(600, 480)), ApiException.class);

        assertThat(thrown.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(thrown.getErrors()).containsExactly(ValidationError.ofField(
                "firstResponseMinutes", "не может быть больше срока решения"));
        verifyNoInteractions(repository);
    }

    @Test
    void create_whenTypePriorityAndTermsAreAllWrong_isRejectedWithEveryField() {
        ApiException thrown = catchThrowableOfType(
                () -> service.create(TENANT_ID, null, null, new SlaTerms(0, -5)),
                ApiException.class);

        assertThat(thrown.getErrors()).containsExactly(
                ValidationError.ofField("typeId", "не должно быть пустым"),
                ValidationError.ofField("priority", "не должно быть пустым"),
                ValidationError.ofField("firstResponseMinutes", "должно быть больше нуля"),
                ValidationError.ofField("resolutionMinutes", "должно быть больше нуля"));
        verifyNoInteractions(repository);
    }

    @Test
    void create_whenFirstResponseEqualsResolution_isAccepted() {
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(service.create(TENANT_ID, TYPE_ID, RequestPriority.URGENT,
                new SlaTerms(60, 60)).getTerms()).isEqualTo(new SlaTerms(60, 60));
    }

    @Test
    void create_whenDatabaseRejectsTypeOfAnotherTenant_reportsTypeFieldAsNotFound() {
        when(repository.saveAndFlush(any())).thenThrow(violationOf("sla_policies_type_fkey"));

        ApiException thrown = catchThrowableOfType(() -> service.create(TENANT_ID, TYPE_ID,
                RequestPriority.HIGH, FOUR_HOURS_AND_FIVE_DAYS), ApiException.class);

        assertThat(thrown.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(thrown.getErrors()).containsExactly(
                ValidationError.ofField("typeId", "тип заявки не найден"));
    }

    @Test
    void create_whenDatabaseRejectsConcurrentDuplicate_reportsAlreadyExists() {
        when(repository.saveAndFlush(any())).thenThrow(violationOf("sla_policies_pair_unique"));

        ApiException thrown = catchThrowableOfType(() -> service.create(TENANT_ID, TYPE_ID,
                RequestPriority.HIGH, FOUR_HOURS_AND_FIVE_DAYS), ApiException.class);

        assertThat(thrown.getErrorCode()).isEqualTo(ErrorCode.ALREADY_EXISTS);
    }

    @Test
    void create_whenDatabaseRejectsForUnknownReason_passesErrorThrough() {
        DataIntegrityViolationException unknown = violationOf("some_other_constraint");
        when(repository.saveAndFlush(any())).thenThrow(unknown);

        assertThatThrownBy(() -> service.create(TENANT_ID, TYPE_ID, RequestPriority.HIGH,
                FOUR_HOURS_AND_FIVE_DAYS)).isSameAs(unknown);
    }

    @Test
    void createDefault_whenTenantHasNone_savesPolicyWithoutTypeAndPriority() {
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        SlaPolicy saved = service.createDefault(TENANT_ID, FOUR_HOURS_AND_FIVE_DAYS);

        assertThat(saved.isDefault()).isTrue();
        assertThat(saved.getTypeId()).isNull();
        assertThat(saved.getPriority()).isNull();
        assertThat(saved.getTerms()).isEqualTo(FOUR_HOURS_AND_FIVE_DAYS);
    }

    @Test
    void createDefault_whenTenantAlreadyHasOne_isRejectedAndNothingSaved() {
        when(repository.findByTenantIdAndTypeIdIsNull(TENANT_ID))
                .thenReturn(Optional.of(defaultPolicy()));

        ApiException thrown = catchThrowableOfType(
                () -> service.createDefault(TENANT_ID, FOUR_HOURS_AND_FIVE_DAYS),
                ApiException.class);

        assertThat(thrown.getErrorCode()).isEqualTo(ErrorCode.ALREADY_EXISTS);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void changeTerms_whenPolicyBelongsToTenant_replacesBothTerms() {
        SlaPolicy policy = pairPolicy(RequestPriority.HIGH);
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndTenantId(id, TENANT_ID)).thenReturn(Optional.of(policy));

        SlaPolicy changed = service.changeTerms(TENANT_ID, id, new SlaTerms(120, 960));

        assertThat(changed).isSameAs(policy);
        assertThat(policy.getTerms()).isEqualTo(new SlaTerms(120, 960));
        assertThat(policy.getPriority()).as("пара не меняется").isEqualTo(RequestPriority.HIGH);
    }

    @Test
    void changeTerms_whenPolicyIsNotOfThisTenant_isRejectedAsNotFound() {
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndTenantId(id, TENANT_ID)).thenReturn(Optional.empty());

        ApiException thrown = catchThrowableOfType(
                () -> service.changeTerms(TENANT_ID, id, FOUR_HOURS_AND_FIVE_DAYS),
                ApiException.class);

        assertThat(thrown.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void changeTerms_whenTermsInvalid_isRejectedWithoutReadingPolicy() {
        ApiException thrown = catchThrowableOfType(() -> service.changeTerms(TENANT_ID,
                UUID.randomUUID(), new SlaTerms(600, 480)), ApiException.class);

        assertThat(thrown.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        verifyNoInteractions(repository);
    }

    @Test
    void resolve_whenPairHasOwnPolicy_returnsItAndDoesNotLookForDefault() {
        SlaPolicy exact = pairPolicy(RequestPriority.URGENT);
        when(repository.findByTenantIdAndTypeIdAndPriority(TENANT_ID, TYPE_ID,
                RequestPriority.URGENT)).thenReturn(Optional.of(exact));

        assertThat(service.resolve(TENANT_ID, TYPE_ID, RequestPriority.URGENT)).containsSame(exact);
        verify(repository, never()).findByTenantIdAndTypeIdIsNull(any());
    }

    @Test
    void resolve_whenPairHasNoPolicy_fallsBackToDefault() {
        SlaPolicy byDefault = defaultPolicy();
        when(repository.findByTenantIdAndTypeIdAndPriority(TENANT_ID, TYPE_ID,
                RequestPriority.LOW)).thenReturn(Optional.empty());
        when(repository.findByTenantIdAndTypeIdIsNull(TENANT_ID))
                .thenReturn(Optional.of(byDefault));

        assertThat(service.resolve(TENANT_ID, TYPE_ID, RequestPriority.LOW))
                .containsSame(byDefault);
    }

    @Test
    void resolve_whenRequestHasNoType_usesDefaultWithoutLookingForPair() {
        SlaPolicy byDefault = defaultPolicy();
        when(repository.findByTenantIdAndTypeIdIsNull(TENANT_ID))
                .thenReturn(Optional.of(byDefault));

        assertThat(service.resolve(TENANT_ID, null, RequestPriority.NORMAL))
                .containsSame(byDefault);
        verify(repository, never()).findByTenantIdAndTypeIdAndPriority(any(), any(), any());
    }

    @Test
    void resolve_whenNeitherPairNorDefaultPolicyExists_returnsNothing() {
        when(repository.findByTenantIdAndTypeIdAndPriority(TENANT_ID, TYPE_ID,
                RequestPriority.LOW)).thenReturn(Optional.empty());
        when(repository.findByTenantIdAndTypeIdIsNull(TENANT_ID)).thenReturn(Optional.empty());

        assertThat(service.resolve(TENANT_ID, TYPE_ID, RequestPriority.LOW)).isEmpty();
    }

    @Test
    void policies_whenPageLargerThanLimit_areRejectedWithoutReading() {
        ApiException thrown = catchThrowableOfType(() -> service.policies(TENANT_ID,
                PageRequest.of(0, PageRequests.MAX_SIZE + 1)), ApiException.class);

        assertThat(thrown.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        verifyNoInteractions(repository);
    }

    private static SlaPolicy pairPolicy(RequestPriority priority) {
        return SlaPolicy.forPair(TENANT_ID, TYPE_ID, priority, FOUR_HOURS_AND_FIVE_DAYS);
    }

    private static SlaPolicy defaultPolicy() {
        return SlaPolicy.byDefault(TENANT_ID, FOUR_HOURS_AND_FIVE_DAYS);
    }

    /**
     * Отказ базы, как его видит сервис: Spring кладёт причиной исключение Hibernate с именем
     * нарушенного ограничения.
     */
    private static DataIntegrityViolationException violationOf(String constraint) {
        return new DataIntegrityViolationException("could not execute statement",
                new ConstraintViolationException("could not execute statement",
                        new SQLException("violates constraint"), constraint));
    }
}
