package ru.practicum.crm.security.repository;

import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.crm.security.domain.RefreshToken;

public interface RefreshTokenRepository extends Repository<RefreshToken, UUID> {

    RefreshToken save(RefreshToken refreshToken);

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select token from RefreshToken token where token.tokenHash = :tokenHash")
    Optional<RefreshToken> findByTokenHashForUpdate(
            @Param("tokenHash") String tokenHash
    );

    List<RefreshToken> findAllByFamilyId(UUID familyId);

    /**
     * Блокирует все токены цепочки входа до конца транзакции — перед её отзывом (T-028).
     *
     * <p>Отдельный запрос, а не часть отзыва: если в это же время законный обмен выдаёт новый
     * токен цепочки, запрос дождётся конца того обмена. Следующий запрос — сам отзыв — увидит
     * уже и новый токен: в PostgreSQL каждый запрос видит данные на момент своего начала.
     * Отзыв одним {@code UPDATE} дождался бы того обмена, но выданный им токен пропустил бы.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(nativeQuery = true,
            value = "SELECT id FROM refresh_tokens WHERE family_id = :familyId FOR UPDATE")
    List<UUID> lockFamily(@Param("familyId") UUID familyId);

    /**
     * Отзывает ещё не отозванные токены цепочки входа. Вызывать после {@link #lockFamily}.
     *
     * @return сколько токенов отозвано
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Modifying
    @Query(
            """
            update RefreshToken token set token.revokedAt = :revokedAt
            where token.familyId = :familyId and token.revokedAt is null
            """)
    int revokeFamily(@Param("familyId") UUID familyId,
            @Param("revokedAt") OffsetDateTime revokedAt);

    /**
     * Блокирует все токены пользователя до конца транзакции — перед их отзывом (T-029). Зачем
     * отдельным запросом, сказано в {@link #lockFamily}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(nativeQuery = true,
            value = "SELECT id FROM refresh_tokens WHERE user_id = :userId FOR UPDATE")
    List<UUID> lockAllOfUser(@Param("userId") UUID userId);

    /**
     * Отзывает ещё не отозванные токены пользователя на всех его входах. Вызывать после
     * {@link #lockAllOfUser}.
     *
     * @return сколько токенов отозвано
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Modifying
    @Query(
            """
            update RefreshToken token set token.revokedAt = :revokedAt
            where token.userId = :userId and token.revokedAt is null
            """)
    int revokeAllOfUser(@Param("userId") UUID userId,
            @Param("revokedAt") OffsetDateTime revokedAt);

    /**
     * Удаляет порцию истёкших токенов (T-029). Строки, которые держит идущий обмен или отзыв,
     * пропускаются ({@code SKIP LOCKED}) — их удалит следующий проход. Выборка идёт по индексу
     * {@code idx_refresh_tokens_expires_at}.
     *
     * @return сколько токенов удалено
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Modifying
    @Query(nativeQuery = true, value =
            """
            DELETE FROM refresh_tokens
            WHERE id IN (
                SELECT id FROM refresh_tokens
                WHERE expires_at <= :now
                LIMIT :limit
                FOR UPDATE SKIP LOCKED)
            """)
    int purgeExpired(@Param("now") OffsetDateTime now, @Param("limit") int limit);

    /**
     * Удаляет порцию отозванных токенов (T-029). Остальное — как в {@link #purgeExpired};
     * индекс — {@code idx_refresh_tokens_revoked}.
     *
     * <p>Использованные, но не отозванные и не истёкшие токены не удаляются: по ним обмен узнаёт
     * повторное предъявление украденного токена (T-028).
     *
     * @return сколько токенов удалено
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Modifying
    @Query(nativeQuery = true, value =
            """
            DELETE FROM refresh_tokens
            WHERE id IN (
                SELECT id FROM refresh_tokens
                WHERE revoked_at IS NOT NULL
                LIMIT :limit
                FOR UPDATE SKIP LOCKED)
            """)
    int purgeRevoked(@Param("limit") int limit);
}
