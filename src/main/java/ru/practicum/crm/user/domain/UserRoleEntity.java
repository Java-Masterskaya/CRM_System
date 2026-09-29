package ru.practicum.crm.user.domain;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "user_roles")
public class UserRoleEntity {

    @EmbeddedId
    private UserRoleId id;

    public UserRoleEntity() {
    }

    public UserRoleEntity(UserRoleId id) {
        this.id = copy(id);
    }

    public UserRoleId getId() {
        return copy(id);
    }

    public void setId(UserRoleId id) {
        this.id = copy(id);
    }

    private UserRoleId copy(UserRoleId source) {
        if (source == null) {
            return null;
        }
        return new UserRoleId(source.getUserId(), source.getRoleId());
    }
}
