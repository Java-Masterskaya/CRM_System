package ru.practicum.crm.attachment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import ru.practicum.crm.common.model.TenantScopedEntity;

/**
 * Вложение заявки (T-078, ТЗ §4.2 и §6.6): ссылка на объект внешнего медиа-сервиса. Самого файла
 * здесь нет — только идентификатор объекта и то, что показывают в списке вложений: имя, размер и
 * тип. Когда приложено — время создания записи ({@link #getCreatedAt()}).
 *
 * <p>Сеттеров нет: вложение не меняется, а отвязка — отдельная задача (T-081).
 */
@Entity
@Table(name = "request_attachments")
@Getter
public class RequestAttachment extends TenantScopedEntity {

    /** Наибольшая длина идентификатора объекта, имени файла и типа содержимого в символах. */
    public static final int TEXT_MAX_LENGTH = 255;

    @Column(name = "request_id", nullable = false, updatable = false)
    private UUID requestId;

    @Column(name = "author_id", nullable = false, updatable = false)
    private UUID authorId;

    @Column(name = "object_id", nullable = false, updatable = false, length = TEXT_MAX_LENGTH)
    private String objectId;

    @Column(name = "file_name", nullable = false, updatable = false, length = TEXT_MAX_LENGTH)
    private String fileName;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @Column(name = "content_type", nullable = false, updatable = false, length = TEXT_MAX_LENGTH)
    private String contentType;

    protected RequestAttachment() {
        // Конструктор без аргументов нужен Hibernate; прикладной код использует конструктор ниже.
    }

    public RequestAttachment(UUID tenantId, UUID requestId, UUID authorId, FileReference file) {
        super(tenantId);
        this.requestId = requestId;
        this.authorId = authorId;
        this.objectId = file.objectId();
        this.fileName = file.fileName();
        this.sizeBytes = file.sizeBytes();
        this.contentType = file.contentType();
    }
}
