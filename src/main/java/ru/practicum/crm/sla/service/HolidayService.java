package ru.practicum.crm.sla.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.common.error.ValidationError;
import ru.practicum.crm.common.pagination.PageRequests;
import ru.practicum.crm.sla.domain.Holiday;
import ru.practicum.crm.sla.repository.HolidayRepository;

/**
 * Справочник нерабочих дней арендатора (T-057): добавление нерабочих и рабочих дат, удаление и
 * просмотр. Календарь учитывает справочник в {@link WorkingCalendarService#calendar}.
 *
 * <p>Кто вправе менять справочник, решает вызывающий код: по ТЗ §6.3 это администратор
 * арендатора — права (T-030). Все операции работают в пределах арендатора.
 *
 * <p>Одна запись на дату гарантирована базой — уникальностью {@code holidays_date_unique}.
 * Сервис проверяет повтор заранее, а нарушение уникальности от одновременного запроса переводит
 * в ту же ошибку {@code ALREADY_EXISTS}.
 */
@Service
public class HolidayService {

    private static final String DATE_FIELD = "date";
    private static final String DESCRIPTION_FIELD = "description";
    private static final String START_FIELD = "start";
    private static final String END_FIELD = "end";
    private static final int DESCRIPTION_MAX_LENGTH = 255;

    private static final String DATE_CONSTRAINT = "holidays_date_unique";
    private static final String DATE_EXISTS = "Эта дата уже есть в справочнике.";

    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification = "Репозиторий — Spring-бин, внедряется как зависимость. SpotBugs "
                    + "считает его изменяемым из-за метода delete")
    private final HolidayRepository repository;

    public HolidayService(HolidayRepository repository) {
        this.repository = repository;
    }

    /**
     * Добавляет нерабочий день: праздник или корпоративный выходной. Дата станет нерабочей,
     * даже если по календарю она будняя.
     *
     * @throws ApiException {@code VALIDATION_FAILED} с перечнем полей, если не указана дата или
     *     описание; {@code ALREADY_EXISTS}, если дата уже есть в справочнике
     */
    @Transactional
    public Holiday createDayOff(UUID tenantId, LocalDate date, String description) {
        List<ValidationError> errors = new ArrayList<>();
        collectDateErrors(date, description, errors);
        rejectIfAny(errors);
        return store(Holiday.dayOff(tenantId, date, description));
    }

    /**
     * Добавляет рабочий день с этими часами: перенос, когда обычно выходной день становится
     * рабочим, или сокращённый день. Часы этой даты заменяют часы её дня недели из календаря.
     *
     * @throws ApiException {@code VALIDATION_FAILED} с перечнем полей, если не указана дата,
     *     описание или часы, или начало не раньше конца; {@code ALREADY_EXISTS}, если дата уже
     *     есть в справочнике
     */
    @Transactional
    public Holiday createWorkingDay(UUID tenantId, LocalDate date, String description,
            LocalTime start, LocalTime end) {
        List<ValidationError> errors = new ArrayList<>();
        collectDateErrors(date, description, errors);
        if (start == null) {
            errors.add(ValidationError.ofField(START_FIELD, "не должно быть пустым"));
        }
        if (end == null) {
            errors.add(ValidationError.ofField(END_FIELD, "не должно быть пустым"));
        }
        if (start != null && end != null && !start.isBefore(end)) {
            errors.add(ValidationError.ofField(END_FIELD, "должно быть позже начала"));
        }
        rejectIfAny(errors);
        return store(Holiday.workingDay(tenantId, date, description, start, end));
    }

    /**
     * Удаляет дату из справочника: дальше она снова следует календарю.
     *
     * @throws ApiException {@code NOT_FOUND}, если у арендатора такой записи нет
     */
    @Transactional
    public void delete(UUID tenantId, UUID id) {
        Holiday holiday = repository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,
                        "Дата не найдена в справочнике."));
        repository.delete(holiday);
    }

    /**
     * Страница справочника арендатора по порядку дат.
     *
     * @param pageable номер и размер страницы; ограничения — в
     *     {@link PageRequests#requireBounded}
     */
    @Transactional(readOnly = true)
    public Page<Holiday> holidays(UUID tenantId, Pageable pageable) {
        return repository.findByTenantIdOrderByDateAsc(tenantId,
                PageRequests.requireBounded(pageable));
    }

    /**
     * Сохраняет запись и переводит отказ базы в ошибку для вызывающего. Проверка заранее ловит
     * обычный повтор; сюда попадает повтор, созданный одновременным запросом.
     */
    private Holiday store(Holiday holiday) {
        if (repository.existsByTenantIdAndDate(holiday.getTenantId(), holiday.getDate())) {
            throw new ApiException(ErrorCode.ALREADY_EXISTS, DATE_EXISTS);
        }
        try {
            return repository.saveAndFlush(holiday);
        } catch (DataIntegrityViolationException ex) {
            String cause = String.valueOf(NestedExceptionUtils.getMostSpecificCause(ex)
                    .getMessage());
            if (cause.contains(DATE_CONSTRAINT)) {
                throw new ApiException(ErrorCode.ALREADY_EXISTS, DATE_EXISTS);
            }
            throw ex;
        }
    }

    /** Длина описания считается в символах — так же её считает база для {@code VARCHAR}. */
    private static void collectDateErrors(LocalDate date, String description,
            List<ValidationError> errors) {
        if (date == null) {
            errors.add(ValidationError.ofField(DATE_FIELD, "не должно быть пустым"));
        }
        if (description == null || description.isBlank()) {
            errors.add(ValidationError.ofField(DESCRIPTION_FIELD, "не должно быть пустым"));
        } else if (description.codePointCount(0, description.length())
                > DESCRIPTION_MAX_LENGTH) {
            errors.add(ValidationError.ofField(DESCRIPTION_FIELD,
                    "должно быть не длиннее " + DESCRIPTION_MAX_LENGTH + " символов"));
        }
    }

    private static void rejectIfAny(List<ValidationError> errors) {
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, null, errors);
        }
    }
}
