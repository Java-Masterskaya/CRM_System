package ru.practicum.crm.attachment.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.crm.attachment.domain.FileReference;
import ru.practicum.crm.attachment.domain.RequestAttachment;
import ru.practicum.crm.attachment.repository.RequestAttachmentRepository;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.common.error.ValidationError;
import ru.practicum.crm.common.pagination.PageRequests;

/**
 * Вложения заявок (T-078): хранение ссылок на объекты медиа-сервиса и чтение.
 *
 * <p>Существует ли объект в медиа-сервисе, здесь не проверяется — это делает привязка вложения
 * (T-080) через клиент медиа-сервиса (T-079). Кто вправе прикладывать и видеть вложения, тоже
 * решает вызывающий код: по ТЗ §6.6 заявка должна принадлежать текущему пользователю и
 * арендатору. Автор приходит параметром — его источником станет пользователь из токена (T-026).
 *
 * <p>Что заявка существует и принадлежит тому же арендатору, гарантирует база: внешний ключ
 * {@code request_attachments_request_fkey} не даст сохранить вложение к чужой или несуществующей
 * заявке.
 */
@Service
public class RequestAttachmentService {

    private final RequestAttachmentRepository repository;

    public RequestAttachmentService(RequestAttachmentRepository repository) {
        this.repository = repository;
    }

    /**
     * Привязывает файл из медиа-сервиса к заявке.
     *
     * @throws ApiException {@code VALIDATION_FAILED} с перечнем полей, если ссылка на файл
     *     заполнена неверно; {@code ALREADY_EXISTS}, если этот объект уже привязан к заявке
     */
    @Transactional
    public RequestAttachment attach(UUID tenantId, UUID requestId, UUID authorId,
            FileReference file) {
        validate(file);
        if (repository.existsByTenantIdAndRequestIdAndObjectId(tenantId, requestId,
                file.objectId())) {
            throw new ApiException(ErrorCode.ALREADY_EXISTS,
                    "Этот файл уже приложен к заявке.");
        }
        return repository.save(new RequestAttachment(tenantId, requestId, authorId, file));
    }

    /**
     * Страница вложений заявки арендатора в порядке добавления.
     *
     * @param pageable номер и размер страницы; ограничения — в
     *     {@link PageRequests#requireBounded}
     */
    @Transactional(readOnly = true)
    public Page<RequestAttachment> attachments(UUID tenantId, UUID requestId,
            Pageable pageable) {
        return repository.findByTenantIdAndRequestIdOrderByCreatedAtAscIdAsc(tenantId, requestId,
                PageRequests.requireBounded(pageable));
    }

    /** Собирает все ошибки сразу, чтобы вызывающий получил полный перечень полей. */
    private static void validate(FileReference file) {
        List<ValidationError> errors = new ArrayList<>();
        requireIdentifier(errors, "objectId", file.objectId());
        requireText(errors, "fileName", file.fileName());
        requireText(errors, "contentType", file.contentType());
        if (file.sizeBytes() < 0) {
            errors.add(ValidationError.ofField("sizeBytes", "не может быть отрицательным"));
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, null, errors);
        }
    }

    /**
     * Идентификатор объекта сравнивается с уже привязанными как есть, и {@code " abc"} оказался бы
     * другим объектом, чем {@code "abc"}. Пробелы по краям не обрезаются, а отклоняются:
     * идентификатор выдаёт медиа-сервис, и CRM его не меняет.
     */
    private static void requireIdentifier(List<ValidationError> errors, String field,
            String value) {
        if (value != null && !value.isBlank() && !value.equals(value.strip())) {
            errors.add(ValidationError.ofField(field, "не должно начинаться или заканчиваться"
                    + " пробелом"));
        } else {
            requireText(errors, field, value);
        }
    }

    /**
     * Длина считается в символах, а не в единицах {@code char}: так же её считает база для
     * {@code VARCHAR(255)}.
     */
    private static void requireText(List<ValidationError> errors, String field, String value) {
        if (value == null || value.isBlank()) {
            errors.add(ValidationError.ofField(field, "не должно быть пустым"));
        } else if (value.codePointCount(0, value.length()) > RequestAttachment.TEXT_MAX_LENGTH) {
            errors.add(ValidationError.ofField(field, "должно быть не длиннее "
                    + RequestAttachment.TEXT_MAX_LENGTH + " символов"));
        }
    }
}
