package ru.practicum.crm.security.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;
import ru.practicum.crm.security.domain.RefreshToken;

public interface RefreshTokenRepository extends Repository<RefreshToken, UUID> {

    RefreshToken save(RefreshToken refreshToken);

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    List<RefreshToken> findAllByFamilyId(UUID familyId);
}
