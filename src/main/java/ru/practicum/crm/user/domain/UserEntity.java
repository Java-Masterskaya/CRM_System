package ru.practicum.crm.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import ru.practicum.crm.common.model.TenantScopedEntity;

@Entity
@Table(name = "users")
public class UserEntity extends TenantScopedEntity {

    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "name", length = 255)
    private String name;

    @Column(name = "phone", length = 40)
    private String phone;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private UserStatus status;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    protected UserEntity() {
    }

    public UserEntity(
            UUID tenantId,
            String email,
            String passwordHash,
            UserStatus status
    ) {
        super(tenantId);
        this.email = email;
        this.passwordHash = passwordHash;
        this.status = status;
    }

    public UserEntity(UUID tenantId, String email, String name, String passwordHash,
            UserStatus status) {
        this(tenantId, email, passwordHash, status);
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public UserStatus getStatus() {
        return status;
    }

    public void setStatus(UserStatus status) {
        this.status = status;
    }

    public OffsetDateTime getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(OffsetDateTime deletedAt) {
        this.deletedAt = deletedAt;
    }

    public void block() {
        this.status = UserStatus.BLOCKED;
    }

    public void delete() {
        this.deletedAt = OffsetDateTime.now();
    }

    public boolean canLogIn() {
        return status == UserStatus.ACTIVE && deletedAt == null;
    }
}
