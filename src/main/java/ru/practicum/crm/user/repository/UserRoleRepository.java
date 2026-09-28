package ru.practicum.crm.user.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.Repository;
import ru.practicum.crm.user.domain.UserRoleEntity;
import ru.practicum.crm.user.domain.UserRoleId;

public interface UserRoleRepository extends Repository<UserRoleEntity, UserRoleId> {

    UserRoleEntity save(UserRoleEntity userRole);

    List<UserRoleEntity> findByIdUserId(UUID userId);
}
