package ru.practicum.crm.request.service;

import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ConstraintViolations;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.common.error.ValidationError;
import ru.practicum.crm.common.model.RequestPriority;
import ru.practicum.crm.common.pagination.PageRequests;
import ru.practicum.crm.request.domain.RequestType;
import ru.practicum.crm.request.repository.RequestTypeRepository;

/**
 * Управление справочником типов заявок.
 *
 * <p>Управлять справочником может только носитель права {@code REQUEST_TYPE_MANAGE}, читать весь
 * справочник — ещё и тот, кто видит все заявки арендатора ({@code REQUEST_READ_ALL}): оператору
 * нужны названия типов в реестре и при правке заявки. Список действующих типов для новой заявки
 * открыт тому, кто вправе её создать. Права проверяет {@code @PreAuthorize}; без права — 403.
 *
 * <p>{@link #requireActive} права не проверяет: его вызывают создание и правка заявки, у которых
 * свои права.
 */
@Service
public class RequestTypeService {

    private static final String TYPE_FIELD = "typeId";
    private static final String NAME_CONSTRAINT = "request_types_name_unique";
    private static final String NAME_TAKEN = "Тип заявки с таким названием уже есть.";

    /** Порядок списков: по названию, при равных названиях — по {@code id}. */
    static final Sort NAME_ORDER = Sort.by(Sort.Order.asc("name"),
            Sort.Order.asc(PageRequests.TIE_BREAKER_FIELD));

    private final RequestTypeRepository repository;

    public RequestTypeService(RequestTypeRepository repository) {
        this.repository = repository;
    }

    @PreAuthorize("hasAuthority('REQUEST_TYPE_MANAGE')")
    @Transactional
    public RequestType create(UUID tenantId, String name, String description,
            RequestPriority defaultPriority) {
        requireNameIsFree(tenantId, name);
        RequestType type = new RequestType(tenantId, name);
        type.describe(name, description, defaultPriority);
        return store(type);
    }

    @PreAuthorize("hasAuthority('REQUEST_TYPE_MANAGE')")
    @Transactional
    public RequestType update(UUID tenantId, UUID id, String name, String description,
            RequestPriority defaultPriority) {
        RequestType type = require(tenantId, id);
        // Смена одного регистра («ремонт» → «Ремонт») — тот же тип: проверка на занятость
        // нашла бы его самого и отказала бы.
        if (!type.getName().equalsIgnoreCase(name)) {
            requireNameIsFree(tenantId, name);
        }
        type.describe(name, description, defaultPriority);
        return store(type);
    }

    /**
     * Отключает тип: он пропадает из списка для новых заявок, но остаётся читаемым у заявок,
     * которые на него ссылаются. Удаления у справочника нет — этим и закрыт запрет удалять
     * тип, на который есть ссылки.
     */
    @PreAuthorize("hasAuthority('REQUEST_TYPE_MANAGE')")
    @Transactional
    public RequestType deactivate(UUID tenantId, UUID id) {
        RequestType type = require(tenantId, id);
        type.deactivate();
        return repository.save(type);
    }

    @PreAuthorize("hasAuthority('REQUEST_TYPE_MANAGE')")
    @Transactional
    public RequestType activate(UUID tenantId, UUID id) {
        RequestType type = require(tenantId, id);
        type.activate();
        return repository.save(type);
    }

    /**
     * Весь справочник арендатора, вместе с отключёнными типами.
     *
     * @param pageable номер и размер страницы; ограничения — в
     *     {@link PageRequests#requireBounded}, порядок всегда {@link #NAME_ORDER}
     */
    @PreAuthorize("hasAnyAuthority('REQUEST_TYPE_MANAGE', 'REQUEST_READ_ALL')")
    @Transactional(readOnly = true)
    public Page<RequestType> types(UUID tenantId, Pageable pageable) {
        return repository.findByTenantId(tenantId, ordered(pageable));
    }

    /** Действующие типы — те, что можно выбрать для новой заявки. */
    @PreAuthorize("hasAuthority('REQUEST_CREATE')")
    @Transactional(readOnly = true)
    public Page<RequestType> activeTypes(UUID tenantId, Pageable pageable) {
        return repository.findByTenantIdAndActiveTrue(tenantId, ordered(pageable));
    }

    /**
     * Возвращает тип, пригодный для новой заявки. Понадобится T-036 (#37): создание заявки с
     * отключённым или чужим типом должно отклоняться до записи в базу.
     */
    @Transactional(readOnly = true)
    public RequestType requireActive(UUID tenantId, UUID id) {
        RequestType type = require(tenantId, id);
        if (!type.isActive()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, null,
                    List.of(ValidationError.ofField(TYPE_FIELD, "тип заявки отключён")));
        }
        return type;
    }

    private RequestType require(UUID tenantId, UUID id) {
        return repository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Тип заявки не найден."));
    }

    private void requireNameIsFree(UUID tenantId, String name) {
        if (repository.existsByTenantIdAndNameIgnoreCase(tenantId, name)) {
            throw new ApiException(ErrorCode.ALREADY_EXISTS, NAME_TAKEN);
        }
    }

    /**
     * Сохраняет тип сразу, а не при фиксации транзакции: проверка названия выше не видит тип,
     * который в этот же момент создаёт параллельный запрос, и повтор ловит только уникальный
     * индекс базы. Его отказ становится тем же ответом, что и у проверки, а не ошибкой 500.
     */
    private RequestType store(RequestType type) {
        try {
            return repository.saveAndFlush(type);
        } catch (DataIntegrityViolationException ex) {
            if (NAME_CONSTRAINT.equals(ConstraintViolations.nameOf(ex))) {
                throw new ApiException(ErrorCode.ALREADY_EXISTS, NAME_TAKEN);
            }
            throw ex;
        }
    }

    private static Pageable ordered(Pageable pageable) {
        Pageable bounded = PageRequests.requireBounded(pageable);
        return PageRequest.of(bounded.getPageNumber(), bounded.getPageSize(), NAME_ORDER);
    }
}
