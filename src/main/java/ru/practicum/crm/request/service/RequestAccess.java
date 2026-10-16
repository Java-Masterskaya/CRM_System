package ru.practicum.crm.request.service;

import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.common.error.NotFoundException;
import ru.practicum.crm.common.security.CurrentUser;
import ru.practicum.crm.request.domain.Request;
import ru.practicum.crm.request.repository.RequestRepository;
import ru.practicum.crm.security.api.PermissionCode;

/**
 * Доступ к заявке с учётом владения (T-030, ТЗ §6.2–6.3).
 *
 * <p>Два шага, и они не смешиваются. Право на чтение заявок вообще проверяет
 * {@code @PreAuthorize}: без него — 403. Какую именно заявку пользователь видит, решает уже
 * бизнес-логика: с правом {@code REQUEST_READ_ALL} — любую заявку арендатора, с
 * {@code REQUEST_READ_OWN} — только свою.
 *
 * <p>Пользуются карточки заявки в клиентском и административном контурах (T-038, T-041) и
 * операции над своей заявкой (T-039).
 */
@Service
public class RequestAccess {

    private final RequestRepository repository;
    private final CurrentUser currentUser;

    public RequestAccess(RequestRepository repository, CurrentUser currentUser) {
        this.repository = repository;
        this.currentUser = currentUser;
    }

    /**
     * Заявка, которую текущий пользователь вправе читать. Чужая заявка — «не найдено», а не
     * «запрещено»: иначе по разнице ответов можно было бы узнать, что заявка с таким номером
     * существует.
     *
     * @throws org.springframework.security.access.AccessDeniedException если нет ни права
     *     читать все заявки, ни права читать свои — ответ 403
     * @throws NotFoundException если заявки нет у арендатора, она удалена или она чужая
     */
    @PreAuthorize("hasAnyAuthority('REQUEST_READ_ALL', 'REQUEST_READ_OWN')")
    @Transactional(readOnly = true)
    public Request readable(UUID tenantId, UUID requestId) {
        Request request = repository.findByIdAndTenantIdAndDeletedFalse(requestId, tenantId)
                .orElseThrow(RequestAccess::notFound);
        if (currentUser.hasPermission(PermissionCode.REQUEST_READ_ALL.name())
                || request.getAuthorId().equals(currentUser.id())) {
            return request;
        }
        throw notFound();
    }

    private static NotFoundException notFound() {
        return new NotFoundException(ErrorCode.NOT_FOUND, "Заявка не найдена.");
    }
}
