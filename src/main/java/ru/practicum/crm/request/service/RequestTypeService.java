package ru.practicum.crm.request.service;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.common.error.ValidationError;
import ru.practicum.crm.request.domain.RequestType;
import ru.practicum.crm.request.repository.RequestTypeRepository;

/**
 * Управление справочником типов заявок.
 *
 * <p>Арендатор приходит параметром, а не из запроса: его источником станет контекст
 * арендатора из T-014 (#15), когда тот появится. Проверка прав администратора — T-030 (#31);
 * здесь её нет, и вызывающий код обязан выполнить её сам.
 */
@Service
public class RequestTypeService {

    private static final String TYPE_FIELD = "typeId";

    private final RequestTypeRepository repository;

    public RequestTypeService(RequestTypeRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public RequestType create(UUID tenantId, String name, String description,
            String defaultPriority) {
        requireNameIsFree(tenantId, name);
        RequestType type = new RequestType(tenantId, name);
        type.describe(name, description, defaultPriority);
        return repository.save(type);
    }

    @Transactional
    public RequestType update(UUID tenantId, UUID id, String name, String description,
            String defaultPriority) {
        RequestType type = require(tenantId, id);
        // Смена одного регистра («ремонт» → «Ремонт») — тот же тип: проверка на занятость
        // нашла бы его самого и отказала бы.
        if (!type.getName().equalsIgnoreCase(name)) {
            requireNameIsFree(tenantId, name);
        }
        type.describe(name, description, defaultPriority);
        return repository.save(type);
    }

    /**
     * Отключает тип: он пропадает из списка для новых заявок, но остаётся читаемым у заявок,
     * которые на него ссылаются. Удаления у справочника нет — этим и закрыт запрет удалять
     * тип, на который есть ссылки.
     */
    @Transactional
    public RequestType deactivate(UUID tenantId, UUID id) {
        RequestType type = require(tenantId, id);
        type.deactivate();
        return repository.save(type);
    }

    @Transactional
    public RequestType activate(UUID tenantId, UUID id) {
        RequestType type = require(tenantId, id);
        type.activate();
        return repository.save(type);
    }

    @Transactional(readOnly = true)
    public List<RequestType> listAll(UUID tenantId) {
        return repository.findByTenantIdOrderByNameAsc(tenantId);
    }

    @Transactional(readOnly = true)
    public List<RequestType> listActive(UUID tenantId) {
        return repository.findByTenantIdAndActiveTrueOrderByNameAsc(tenantId);
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
            throw new ApiException(ErrorCode.ALREADY_EXISTS,
                    "Тип заявки с таким названием уже есть.");
        }
    }
}
