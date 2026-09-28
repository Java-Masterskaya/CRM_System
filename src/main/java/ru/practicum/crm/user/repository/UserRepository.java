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

    @Query(
            """
            SELECT u
            FROM UserEntity u
            WHERE u.id = :id
              AND u.tenantId = :tenantId
              AND u.deletedAt IS NULL
            """
    )
    Optional<UserEntity> findById(
            @Param("id") UUID id,
            @Param("tenantId") UUID tenantId
    );

    @Query(
            """
            SELECT u
            FROM UserEntity u
            WHERE u.tenantId = :tenantId
              AND LOWER(u.email) = LOWER(:email)
              AND u.deletedAt IS NULL
            """
    )
    Optional<UserEntity> findByTenantIdAndEmail(
            @Param("tenantId") UUID tenantId,
            @Param("email") String email
    );

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
