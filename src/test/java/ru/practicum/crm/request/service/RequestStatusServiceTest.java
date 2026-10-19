package ru.practicum.crm.request.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.common.error.NotFoundException;
import ru.practicum.crm.common.error.ValidationError;
import ru.practicum.crm.common.security.CurrentUser;
import ru.practicum.crm.request.api.dto.ChangeRequestStatusRequest;
import ru.practicum.crm.request.api.dto.RequestStatusResponse;
import ru.practicum.crm.request.domain.Request;
import ru.practicum.crm.request.domain.RequestStatus;
import ru.practicum.crm.request.domain.RequestStatusChange;
import ru.practicum.crm.request.repository.RequestRepository;
import ru.practicum.crm.request.repository.RequestStatusHistoryRepository;
import ru.practicum.crm.tenant.api.TenantContext;

/**
 * Смена статуса без базы: порядок проверок и что записывается в историю. Право на операцию
 * ({@code @PreAuthorize}), одну транзакцию и откат проверяет {@code RequestStatusIntegrationTest}.
 */
class RequestStatusServiceTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID REQUEST_ID = UUID.randomUUID();
    private static final UUID OPERATOR_ID = UUID.randomUUID();
    private static final long VERSION = 3;

    private final RequestRepository requests = mock(RequestRepository.class);
    private final RequestStatusHistoryRepository history =
            mock(RequestStatusHistoryRepository.class);
    private final RequestStatusNotifier notifier = mock(RequestStatusNotifier.class);
    private final CurrentUser currentUser = mock(CurrentUser.class);
    private final TenantContext tenantContext = mock(TenantContext.class);
    private final RequestStatusService service =
            new RequestStatusService(requests, history, notifier, currentUser, tenantContext);

    @BeforeEach
    void setUp() {
        when(tenantContext.getCurrentTenantId()).thenReturn(TENANT_ID);
        when(currentUser.id()).thenReturn(OPERATOR_ID);
        // Как Hibernate при записи: версия изменённой заявки растёт на единицу.
        when(requests.saveAndFlush(any())).thenAnswer(call -> {
            Request saved = call.getArgument(0);
            ReflectionTestUtils.setField(saved, "version", saved.getVersion() + 1);
            return saved;
        });
    }

    /** DoD: валидный переход меняет статус и добавляет ровно одну запись в историю. */
    @Test
    void changeStatus_allowedTransition_changesStatusAndRecordsItOnce() {
        Request request = existing(RequestStatus.IN_PROGRESS);

        RequestStatusResponse response = service.changeStatus(REQUEST_ID,
                command(RequestStatus.DONE, "  Отчёт отправлен клиенту  ", VERSION));

        assertThat(response).isEqualTo(
                new RequestStatusResponse(REQUEST_ID, RequestStatus.DONE, VERSION + 1));
        assertThat(request.getStatus()).isEqualTo(RequestStatus.DONE);
        RequestStatusChange change = recordedChange();
        assertThat(change.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(change.getRequestId()).isEqualTo(REQUEST_ID);
        assertThat(change.getFromStatus()).isEqualTo(RequestStatus.IN_PROGRESS);
        assertThat(change.getToStatus()).isEqualTo(RequestStatus.DONE);
        assertThat(change.getAuthorId()).isEqualTo(OPERATOR_ID);
        assertThat(change.getComment()).isEqualTo("Отчёт отправлен клиенту");
    }

    /**
     * T-070: состоявшийся переход — ровно одно письмо автору, о переходе из прежнего статуса в
     * новый, и уже после записи заявки — в той же транзакции.
     */
    @Test
    void changeStatus_allowedTransition_notifiesAuthorOnceAfterSaving() {
        Request request = existing(RequestStatus.IN_PROGRESS);

        service.changeStatus(REQUEST_ID, command(RequestStatus.ON_HOLD, null, VERSION));

        InOrder order = inOrder(requests, notifier);
        order.verify(requests).saveAndFlush(request);
        order.verify(notifier).statusChanged(request, RequestStatus.IN_PROGRESS);
        assertThat(request.getStatus()).isEqualTo(RequestStatus.ON_HOLD);
        verifyNoMoreInteractions(notifier);
    }

    /** Комментарий необязателен; из одних пробелов — то же, что его нет. */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void changeStatus_withoutComment_recordsTransitionWithoutComment(String comment) {
        existing(RequestStatus.NEW);

        service.changeStatus(REQUEST_ID, command(RequestStatus.CONTACTED, comment, VERSION));

        assertThat(recordedChange().getComment()).isNull();
    }

    /**
     * DoD: невалидный переход — конфликт, статус и история не меняются. Среди примеров — шаг
     * через статус, выход из ON_HOLD не в работу, выход из терминального статуса и «переход» в
     * тот же статус.
     */
    @ParameterizedTest
    @CsvSource({"NEW, DONE", "NEW, IN_PROGRESS", "ON_HOLD, DONE", "DONE, IN_PROGRESS",
        "CANCELLED, NEW", "REJECTED, CONTACTED", "IN_PROGRESS, IN_PROGRESS"})
    void changeStatus_transitionNotInMatrix_isConflictAndChangesNothing(RequestStatus from,
            RequestStatus to) {
        Request request = existing(from);

        assertThatThrownBy(() -> service.changeStatus(REQUEST_ID, command(to, "Причина", VERSION)))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_TRANSITION);
                    assertThat(ex.getDetail()).contains(from.name(), to.name());
                });

        assertThat(request.getStatus()).isEqualTo(from);
        verifyNothingSaved();
    }

    /**
     * DoD: переход в REJECTED без причины — ошибка валидации с указанием поля. Заявка даже не
     * читается: запрос отклонён по самим входным данным.
     */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void changeStatus_toRejectedWithoutReason_isValidationErrorOnComment(String comment) {
        assertThatThrownBy(() -> service.changeStatus(REQUEST_ID,
                command(RequestStatus.REJECTED, comment, VERSION)))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(ex.getErrors()).extracting(ValidationError::pointer)
                            .containsExactly("#/comment");
                });

        verifyNoInteractions(requests, history, notifier);
    }

    @Test
    void changeStatus_toRejectedWithReason_recordsReason() {
        existing(RequestStatus.CONTACTED);

        service.changeStatus(REQUEST_ID,
                command(RequestStatus.REJECTED, "Данные уже выгружались", VERSION));

        assertThat(recordedChange().getComment()).isEqualTo("Данные уже выгружались");
    }

    /** Заявку изменили после того, как пользователь её открыл (T-045): ничего не меняем. */
    @Test
    void changeStatus_withStaleVersion_isRejectedAndChangesNothing() {
        Request request = existing(RequestStatus.IN_PROGRESS);

        assertThatThrownBy(() -> service.changeStatus(REQUEST_ID,
                command(RequestStatus.DONE, null, VERSION - 1)))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        assertThat(request.getStatus()).isEqualTo(RequestStatus.IN_PROGRESS);
        verifyNothingSaved();
    }

    /** Заявка ищется у арендатора из контекста: чужая или удалённая — «не найдено». */
    @Test
    void changeStatus_whenTenantHasNoSuchRequest_isNotFound() {
        when(requests.findByIdAndTenantIdAndDeletedFalse(REQUEST_ID, TENANT_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.changeStatus(REQUEST_ID,
                command(RequestStatus.CONTACTED, null, VERSION)))
                .isInstanceOf(NotFoundException.class);

        verifyNothingSaved();
    }

    private Request existing(RequestStatus status) {
        Request request = new Request(TENANT_ID, UUID.randomUUID(), "Выгрузка за квартал",
                "Нужен отчёт", status);
        ReflectionTestUtils.setField(request, "id", REQUEST_ID);
        ReflectionTestUtils.setField(request, "version", VERSION);
        when(requests.findByIdAndTenantIdAndDeletedFalse(REQUEST_ID, TENANT_ID))
                .thenReturn(Optional.of(request));
        return request;
    }

    private static ChangeRequestStatusRequest command(RequestStatus status, String comment,
            long version) {
        return new ChangeRequestStatusRequest(status, comment, version);
    }

    private RequestStatusChange recordedChange() {
        ArgumentCaptor<RequestStatusChange> change =
                ArgumentCaptor.forClass(RequestStatusChange.class);
        verify(history).save(change.capture());
        return change.getValue();
    }

    /** Отклонённый перевод не меняет ни заявку, ни историю и письма не порождает. */
    private void verifyNothingSaved() {
        verify(history, never()).save(any());
        verify(requests, never()).saveAndFlush(any());
        verifyNoInteractions(notifier);
    }
}
