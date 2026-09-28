package ru.practicum.crm.user.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import ru.practicum.crm.user.domain.UserEntity;

public interface UserRepository extends Repository<UserEntity, UUID> {

    UserEntity save(UserEntity user);

    Optional<UserEntity> findById(UUID id);

    Optional<UserEntity> findByTenantIdAndEmail(UUID tenantId, String email);

    @Query(
            """
            SELECT u
            FROM UserEntity u
            WHERE u.tenantId = :tenantId
              AND u.deletedAt IS NULL
            """
    )
    List<UserEntity> findAllActiveByTenantId(@Param("tenantId") UUID tenantId);
}
