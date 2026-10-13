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
}
