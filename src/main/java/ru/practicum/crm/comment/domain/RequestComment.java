package ru.practicum.crm.comment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import ru.practicum.crm.common.model.TenantScopedEntity;

/**
 * Комментарий к заявке (T-074, ТЗ §4.2): текст, автор и признак видимости клиенту. Когда написан —
 * время создания записи ({@link #getCreatedAt()}).
 *
 * <p>Внутренний комментарий ({@code visibleToClient = false}) видит только команда; клиентский
 * контур таких комментариев не отдаёт (SPEC §5.2).
 *
 * <p>Сеттеров нет: изменение и удаление комментария в эту задачу не входят.
 */
@Entity
@Table(name = "request_comments")
@Getter
public class RequestComment extends TenantScopedEntity {

    /** Наибольшая длина текста в символах; то же ограничение стоит в базе. */
    public static final int TEXT_MAX_LENGTH = 4000;

    @Column(name = "request_id", nullable = false, updatable = false)
    private UUID requestId;

    @Column(name = "author_id", nullable = false, updatable = false)
    private UUID authorId;

    @Column(name = "text", nullable = false, updatable = false)
    private String text;

    @Column(name = "visible_to_client", nullable = false, updatable = false)
    private boolean visibleToClient;

    protected RequestComment() {
        // Конструктор без аргументов нужен Hibernate; прикладной код использует конструктор ниже.
    }

    public RequestComment(UUID tenantId, UUID requestId, UUID authorId, String text,
            boolean visibleToClient) {
        super(tenantId);
        this.requestId = requestId;
        this.authorId = authorId;
        this.text = text;
        this.visibleToClient = visibleToClient;
    }
}
