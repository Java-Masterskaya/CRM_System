package ru.practicum.crm.sla.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ConstraintViolations;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.common.error.ValidationError;
import ru.practicum.crm.common.model.RequestPriority;
import ru.practicum.crm.common.pagination.PageRequests;
import ru.practicum.crm.sla.domain.SlaPolicy;
import ru.practicum.crm.sla.domain.SlaTerms;
import ru.practicum.crm.sla.repository.SlaPolicyRepository;

/**
 * Политики сроков арендатора (T-055): создание, изменение сроков, просмотр и выбор политики
 * для заявки.
 *
 * <p>Кто вправе управлять политиками, решает вызывающий код: по ТЗ §6.3 это только
 * администратор арендатора — права (T-030). Все операции работают в пределах арендатора.
 *
 * <p>Существует ли тип заявки у этого арендатора, проверяет база: пакет сроков не зависит от
 * пакета заявок, а внешний ключ {@code sla_policies_type_fkey} не даст сослаться на чужой или
 * несуществующий тип. Нарушение ограничений базы сервис превращает в те же ошибки, что и
 * собственные проверки.
 */
@Service
public class SlaPolicyService {

    private static final String TYPE_FIELD = "typeId";
    private static final String PRIORITY_FIELD = "priority";
    private static final String FIRST_RESPONSE_FIELD = "firstResponseMinutes";
    private static final String RESOLUTION_FIELD = "resolutionMinutes";

    private static final String TYPE_CONSTRAINT = "sla_policies_type_fkey";
    private static final String PAIR_CONSTRAINT = "sla_policies_pair_unique";
    private static final String DEFAULT_CONSTRAINT = "sla_policies_default_unique";

    private static final String PAIR_EXISTS = "Политика для этого типа и приоритета уже есть.";
    private static final String DEFAULT_EXISTS = "Политика по умолчанию уже есть.";

    private final SlaPolicyRepository repository;

    public SlaPolicyService(SlaPolicyRepository repository) {
        this.repository = repository;
    }

    /**
     * Создаёт политику для пары «тип заявки + приоритет».
     *
     * @throws ApiException {@code VALIDATION_FAILED} с перечнем полей, если не указан тип или
     *     приоритет, тип не найден у арендатора или сроки заданы неверно;
     *     {@code ALREADY_EXISTS}, если политика для этой пары уже есть
     */
    @Transactional
    public SlaPolicy create(UUID tenantId, UUID typeId, RequestPriority priority,
            SlaTerms terms) {
        List<ValidationError> errors = new ArrayList<>();
        if (typeId == null) {
            errors.add(ValidationError.ofField(TYPE_FIELD, "не должно быть пустым"));
        }
        if (priority == null) {
            errors.add(ValidationError.ofField(PRIORITY_FIELD, "не должно быть пустым"));
        }
        collectTermErrors(terms, errors);
        rejectIfAny(errors);
        if (repository.findByTenantIdAndTypeIdAndPriority(tenantId, typeId, priority)
                .isPresent()) {
            throw new ApiException(ErrorCode.ALREADY_EXISTS, PAIR_EXISTS);
        }
        return store(SlaPolicy.forPair(tenantId, typeId, priority, terms));
    }

    /**
     * Создаёт политику по умолчанию — её сроки применяются, когда для пары своей политики нет.
     *
     * @throws ApiException {@code VALIDATION_FAILED}, если сроки заданы неверно;
     *     {@code ALREADY_EXISTS}, если политика по умолчанию у арендатора уже есть
     */
    @Transactional
    public SlaPolicy createDefault(UUID tenantId, SlaTerms terms) {
        List<ValidationError> errors = new ArrayList<>();
        collectTermErrors(terms, errors);
        rejectIfAny(errors);
        if (repository.findByTenantIdAndTypeIdIsNull(tenantId).isPresent()) {
            throw new ApiException(ErrorCode.ALREADY_EXISTS, DEFAULT_EXISTS);
        }
        return store(SlaPolicy.byDefault(tenantId, terms));
    }

    /**
     * Меняет сроки политики. Тип и приоритет политики не меняются: для другой пары создаётся
     * другая политика.
     *
     * @throws ApiException {@code NOT_FOUND}, если у арендатора такой политики нет;
     *     {@code VALIDATION_FAILED}, если сроки заданы неверно
     */
    @Transactional
    public SlaPolicy changeTerms(UUID tenantId, UUID id, SlaTerms terms) {
        List<ValidationError> errors = new ArrayList<>();
        collectTermErrors(terms, errors);
        rejectIfAny(errors);
        SlaPolicy policy = repository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,
                        "Политика сроков не найдена."));
        policy.changeTerms(terms);
        return policy;
    }

    /**
     * Страница политик арендатора в порядке создания.
     *
     * @param pageable номер и размер страницы; ограничения — в
     *     {@link PageRequests#requireBounded}
     */
    @Transactional(readOnly = true)
    public Page<SlaPolicy> policies(UUID tenantId, Pageable pageable) {
        return repository.findByTenantIdOrderByCreatedAtAscIdAsc(tenantId,
                PageRequests.requireBounded(pageable));
    }

    /**
     * Политика для заявки с таким типом и приоритетом: своя политика пары, а если её нет —
     * политика по умолчанию. Результат однозначен: на пару и на умолчание база допускает по одной
     * политике.
     *
     * @param typeId тип заявки; у заявки без типа применяется политика по умолчанию
     * @return пусто, если нет ни политики пары, ни политики по умолчанию
     */
    @Transactional(readOnly = true)
    public Optional<SlaPolicy> resolve(UUID tenantId, UUID typeId, RequestPriority priority) {
        if (typeId != null && priority != null) {
            Optional<SlaPolicy> exact =
                    repository.findByTenantIdAndTypeIdAndPriority(tenantId, typeId, priority);
            if (exact.isPresent()) {
                return exact;
            }
        }
        return repository.findByTenantIdAndTypeIdIsNull(tenantId);
    }

    /**
     * Сохраняет политику и переводит отказ базы в ошибку для вызывающего. Проверки выше ловят
     * обычные случаи; сюда попадает то, что видит только база: тип заявки другого арендатора
     * и повтор, созданный одновременным запросом.
     */
    private SlaPolicy store(SlaPolicy policy) {
        try {
            return repository.saveAndFlush(policy);
        } catch (DataIntegrityViolationException ex) {
            String constraint = ConstraintViolations.nameOf(ex);
            if (TYPE_CONSTRAINT.equals(constraint)) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, null,
                        List.of(ValidationError.ofField(TYPE_FIELD, "тип заявки не найден")));
            }
            if (PAIR_CONSTRAINT.equals(constraint)) {
                throw new ApiException(ErrorCode.ALREADY_EXISTS, PAIR_EXISTS);
            }
            if (DEFAULT_CONSTRAINT.equals(constraint)) {
                throw new ApiException(ErrorCode.ALREADY_EXISTS, DEFAULT_EXISTS);
            }
            throw ex;
        }
    }

    private static void collectTermErrors(SlaTerms terms, List<ValidationError> errors) {
        if (terms.firstResponseMinutes() <= 0) {
            errors.add(ValidationError.ofField(FIRST_RESPONSE_FIELD, "должно быть больше нуля"));
        }
        if (terms.resolutionMinutes() <= 0) {
            errors.add(ValidationError.ofField(RESOLUTION_FIELD, "должно быть больше нуля"));
        }
        if (terms.firstResponseMinutes() > 0 && terms.resolutionMinutes() > 0
                && terms.firstResponseMinutes() > terms.resolutionMinutes()) {
            errors.add(ValidationError.ofField(FIRST_RESPONSE_FIELD,
                    "не может быть больше срока решения"));
        }
    }

    private static void rejectIfAny(List<ValidationError> errors) {
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, null, errors);
        }
    }
}
