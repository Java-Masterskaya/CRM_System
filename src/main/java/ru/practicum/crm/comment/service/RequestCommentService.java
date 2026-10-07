package ru.practicum.crm.comment.service;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.crm.comment.domain.RequestComment;
import ru.practicum.crm.comment.repository.RequestCommentRepository;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.common.error.ValidationError;
import ru.practicum.crm.common.pagination.PageRequests;

/**
 * Комментарии заявок (T-074): хранение и чтение.
 *
 * <p>Кто вправе писать и читать комментарии, решает вызывающий код: клиентский контур (T-075)
 * показывает только видимые клиенту комментарии своей заявки — {@link #commentsForClient},
 * команда (T-076) видит все — {@link #commentsForTeam}. Методы названы по тому, кому отдают
 * список, чтобы выбор был явным. Автор приходит параметром — его источником станет пользователь
 * из токена (T-026).
 *
 * <p>Что заявка существует и принадлежит тому же арендатору, гарантирует база: внешний ключ
 * {@code request_comments_request_fkey} не даст сохранить комментарий к чужой или несуществующей
 * заявке. Доступ пользователя к самой заявке проверяют T-075 и T-076.
 */
@Service
public class RequestCommentService {

    private static final String TEXT_FIELD = "text";

    private final RequestCommentRepository repository;

    public RequestCommentService(RequestCommentRepository repository) {
        this.repository = repository;
    }

    /**
     * Сохраняет комментарий к заявке.
     *
     * @param visibleToClient {@code true} — комментарий виден клиенту, {@code false} — только
     *     команде
     * @throws ApiException {@code VALIDATION_FAILED} с полем {@code text}, если текст пуст или
     *     длиннее {@link RequestComment#TEXT_MAX_LENGTH} символов
     */
    @Transactional
    public RequestComment add(UUID tenantId, UUID requestId, UUID authorId, String text,
            boolean visibleToClient) {
        validate(text);
        return repository.save(new RequestComment(tenantId, requestId, authorId, text,
                visibleToClient));
    }

    /**
     * Страница комментариев заявки для команды — все, включая внутренние, в порядке написания.
     * Клиенту этот список отдавать нельзя: для него есть {@link #commentsForClient}.
     *
     * @param pageable номер и размер страницы; ограничения — в
     *     {@link PageRequests#requireBounded}
     */
    @Transactional(readOnly = true)
    public Page<RequestComment> commentsForTeam(UUID tenantId, UUID requestId,
            Pageable pageable) {
        return repository.findByTenantIdAndRequestIdOrderByCreatedAtAscIdAsc(tenantId, requestId,
                PageRequests.requireBounded(pageable));
    }

    /**
     * Страница комментариев заявки для клиента — только видимые клиенту, в порядке написания.
     * Внутренние комментарии сюда не попадают (SPEC §5.2).
     *
     * @param pageable номер и размер страницы; ограничения — в
     *     {@link PageRequests#requireBounded}
     */
    @Transactional(readOnly = true)
    public Page<RequestComment> commentsForClient(UUID tenantId, UUID requestId,
            Pageable pageable) {
        return repository
                .findByTenantIdAndRequestIdAndVisibleToClientTrueOrderByCreatedAtAscIdAsc(
                        tenantId, requestId, PageRequests.requireBounded(pageable));
    }

    /**
     * Длина считается в символах, а не в единицах {@code char}: так же её считает
     * {@code char_length} в ограничении базы, и символ вне основной плоскости Юникода (например,
     * эмодзи) засчитывается за один.
     */
    private static void validate(String text) {
        if (text == null || text.isBlank()) {
            throw invalidText("не должно быть пустым");
        }
        if (text.codePointCount(0, text.length()) > RequestComment.TEXT_MAX_LENGTH) {
            throw invalidText("должно быть не длиннее " + RequestComment.TEXT_MAX_LENGTH
                    + " символов");
        }
    }

    private static ApiException invalidText(String detail) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, null,
                List.of(ValidationError.ofField(TEXT_FIELD, detail)));
    }
}
