package ru.practicum.crm.request.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
import ru.practicum.crm.request.domain.RequestTransitions;
import ru.practicum.crm.request.repository.RequestRepository;
import ru.practicum.crm.request.repository.RequestStatusHistoryRepository;
import ru.practicum.crm.tenant.api.TenantContext;

/**
 * Смена статуса заявки в административном контуре (T-044, ТЗ §5.2).
 *
 * <p>Перевод и запись в историю — одна транзакция: история без перехода и переход без истории
 * невозможны. Если запись истории не удалась, откатывается и смена статуса.
 *
 * <p>Проверки идут до первого изменения, и любая из них отклоняет перевод, ничего не меняя:
 * <ol>
 *   <li>право {@code REQUEST_STATUS_CHANGE} — {@code @PreAuthorize}, без него 403;</li>
 *   <li>причина при отклонении — без неё ошибка валидации с указанием поля;</li>
 *   <li>заявка есть у арендатора и не удалена — иначе «не найдено»;</li>
 *   <li>версия та, что видел пользователь (T-045), — иначе 409 «данные устарели»;</li>
 *   <li>переход разрешён матрицей (T-043) — иначе 409.</li>
 * </ol>
 *
 * <p>В той же транзакции записывается письмо автору заявки о смене статуса (T-070,
 * {@link RequestStatusNotifier}): отклонённый или откатившийся перевод писем не порождает, а
 * каждый состоявшийся — ровно одно.
 */
@Service
public class RequestStatusService {

    private final RequestRepository requests;
    private final RequestStatusHistoryRepository history;
    private final RequestStatusNotifier notifier;
    private final CurrentUser currentUser;
    private final RequestSlaMetrics requestSlaMetrics;
    private final Clock clock;

    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification = "Контекст арендатора — Spring-бин, внедряется как зависимость. "
                    + "SpotBugs считает его изменяемым из-за метода setTenantId")
    private final TenantContext tenantContext;

    public RequestStatusService(RequestRepository requests, RequestStatusHistoryRepository history,
                                RequestSlaMetrics requestSlaMetrics, RequestStatusNotifier notifier,
                                CurrentUser currentUser, TenantContext tenantContext, Clock clock
    ) {
        this.requests = requests;
        this.history = history;
        this.notifier = notifier;
        this.currentUser = currentUser;
        this.tenantContext = tenantContext;
        this.requestSlaMetrics = requestSlaMetrics;
        this.clock = clock;
    }

    /**
     * Переводит заявку текущего арендатора в другой статус и записывает переход в историю.
     *
     * @return заявка с новым статусом и новой версией
     * @throws org.springframework.security.access.AccessDeniedException без права
     *     {@code REQUEST_STATUS_CHANGE} — ответ 403
     * @throws ApiException {@code VALIDATION_FAILED} при отклонении без причины,
     *     {@code INVALID_TRANSITION} при переходе, которого нет в матрице
     * @throws NotFoundException если заявки нет у арендатора или она удалена
     * @throws org.springframework.orm.ObjectOptimisticLockingFailureException если заявку уже
     *     изменили после того, как пользователь её открыл, — ответ 409
     */
    @PreAuthorize("hasAuthority('REQUEST_STATUS_CHANGE')")
    @Transactional
    public RequestStatusResponse changeStatus(UUID requestId, ChangeRequestStatusRequest command) {
        RequestStatus target = command.status();
        String comment = normalized(command.comment());
        if (RequestTransitions.requiresReason(target) && comment == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "Чтобы отклонить заявку, укажите причину.",
                    List.of(ValidationError.ofField("comment",
                            "обязателен при переводе в " + target)));
        }

        UUID tenantId = tenantContext.getCurrentTenantId();
        Request request = requests.findByIdAndTenantIdAndDeletedFalse(requestId, tenantId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.NOT_FOUND,
                        "Заявка не найдена."));
        request.requireVersion(command.version());
        RequestStatus from = request.getStatus();
        if (!RequestTransitions.isAllowed(from, target)) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION,
                    "Заявку нельзя перевести из статуса " + from + " в " + target + ".");
        }

        request.setStatus(target);
        notePassedDeadlines(request, from, clock.instant(), tenantId);
        history.save(new RequestStatusChange(tenantId, requestId, from, target,
                currentUser.id(), comment));
        Request saved = requests.saveAndFlush(request);
        notifier.statusChanged(saved, from);
        if (target == RequestStatus.DONE) {
            requestSlaMetrics.closed(tenantId, request.metDeadlines());
        }
        return new RequestStatusResponse(saved.getId(), saved.getStatus(), saved.getVersion());
    }

    /**
     * Детектор помечает просрочку раз в минтут. Срок который этого статуса перестает идти,
     * сверяется сразу. Первый ответ - пока заявка в {@code NEW}, решение - пока в работе.
     * */
    private void notePassedDeadlines(
            Request request, RequestStatus from, Instant now, UUID tenantId) {

        if (from == RequestStatus.NEW && passed(request.getFirstResponseDueAt(), now)
                && !request.isFirstResponseOverdue()) {
            request.setFirstResponseOverdue(true);
            requestSlaMetrics.firstResponseBreached(tenantId);
        }

        if (request.getStatus().isTerminal() && resolutionClockRunning(from)
                && passed(request.getResolutionDueAt(), now)
                && !request.isResolutionOverdue()) {
            request.setResolutionOverdue(true);
            requestSlaMetrics.resolutionBreached(tenantId);
        }
    }

    private boolean resolutionClockRunning(RequestStatus status) {
        return status == RequestStatus.NEW
                || status == RequestStatus.CONTACTED
                || status == RequestStatus.IN_PROGRESS;
    }

    private boolean passed(Instant dueAt, Instant now) {
        return dueAt != null && !now.isBefore(dueAt);
    }

    /** Комментарий из одних пробелов — то же, что его отсутствие. */
    private static String normalized(String comment) {
        return comment == null || comment.isBlank() ? null : comment.strip();
    }
}
