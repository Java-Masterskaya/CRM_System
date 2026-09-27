package ru.practicum.crm.user.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.practicum.crm.user.domain.UserEntity;

@Repository
public interface UserRepository extends JpaRepository<UserEntity, UUID> {

    Optional<UserEntity> findByTenantIdAndEmail(UUID tenantId, String email);

    @Query(
            """
                SELECT u FROM UserEntity u
                WHERE u.tenantId = :tenantId
                  AND u.deletedAt IS NULL
            """
    )
    List<UserEntity> findAllActiveByTenantId(@Param("tenantId") UUID tenantId);
}
