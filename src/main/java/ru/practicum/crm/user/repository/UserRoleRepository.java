package ru.practicum.crm.user.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import ru.practicum.crm.user.domain.UserRoleEntity;
import ru.practicum.crm.user.domain.UserRoleId;

public interface UserRoleRepository
        extends Repository<UserRoleEntity, UserRoleId> {

    UserRoleEntity save(UserRoleEntity userRole);

    @Query("select ur from UserRoleEntity ur where ur.id.userId = :userId")
    List<UserRoleEntity> findByUserId(@Param("userId") UUID userId);
}
