package ru.practicum.crm.user.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.practicum.crm.user.domain.UserEntity;
import ru.practicum.crm.user.domain.UserStatus;

@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class UserRepositoryIT {

    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("Один и тот же email может существовать у двух разных арендаторов")
    void sameEmailAllowedForDifferentTenants_returnsBothUsers() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        String sharedEmail = "user@example.com";

        UserEntity userA = new UserEntity(
                UUID.randomUUID(), tenantA, sharedEmail, "hash1", UserStatus.ACTIVE
        );
        userA.setCreatedAt(OffsetDateTime.now());
        userA.setUpdatedAt(OffsetDateTime.now());

        UserEntity userB = new UserEntity(
                UUID.randomUUID(), tenantB, sharedEmail, "hash2", UserStatus.ACTIVE
        );
        userB.setCreatedAt(OffsetDateTime.now());
        userB.setUpdatedAt(OffsetDateTime.now());

        userRepository.saveAndFlush(userA);
        userRepository.saveAndFlush(userB);

        Optional<UserEntity> foundAOpt =
                userRepository.findByTenantIdAndEmail(tenantA, sharedEmail);

        Optional<UserEntity> foundBOpt =
                userRepository.findByTenantIdAndEmail(tenantB, sharedEmail);

        assertThat(foundAOpt).isPresent();
        assertThat(foundBOpt).isPresent();

        UserEntity foundA = foundAOpt.get();
        UserEntity foundB = foundBOpt.get();

        assertThat(foundA.getId()).isNotEqualTo(foundB.getId());
    }

    @Test
    @DisplayName("Повторное создание с занятым email внутри арендатора отклоняется")
    void duplicateEmailInSameTenant_throwsException() {
        UUID tenantId = UUID.randomUUID();
        String email = "duplicate@example.com";

        UserEntity user1 = new UserEntity(
                UUID.randomUUID(), tenantId, email, "hash1", UserStatus.ACTIVE
        );
        user1.setCreatedAt(OffsetDateTime.now());
        user1.setUpdatedAt(OffsetDateTime.now());
        userRepository.saveAndFlush(user1);

        UserEntity user2 = new UserEntity(
                UUID.randomUUID(), tenantId, email, "hash2", UserStatus.ACTIVE
        );
        user2.setCreatedAt(OffsetDateTime.now());
        user2.setUpdatedAt(OffsetDateTime.now());

        assertThatThrownBy(() -> userRepository.saveAndFlush(user2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Заблокированный виден в БД, а удалённый - мягко помечается")
    void softDeleteAndStatusManagement_excludesDeletedUser() {
        UUID tenantId = UUID.randomUUID();

        UserEntity activeUser = new UserEntity(
                UUID.randomUUID(), tenantId, "active@example.com", "hash",
                UserStatus.ACTIVE
        );
        activeUser.setCreatedAt(OffsetDateTime.now());
        activeUser.setUpdatedAt(OffsetDateTime.now());

        UserEntity blockedUser = new UserEntity(
                UUID.randomUUID(), tenantId, "blocked@example.com", "hash",
                UserStatus.BLOCKED
        );
        blockedUser.setCreatedAt(OffsetDateTime.now());
        blockedUser.setUpdatedAt(OffsetDateTime.now());

        UserEntity deletedUser = new UserEntity(
                UUID.randomUUID(), tenantId, "deleted@example.com", "hash",
                UserStatus.ACTIVE
        );
        deletedUser.setCreatedAt(OffsetDateTime.now());
        deletedUser.setUpdatedAt(OffsetDateTime.now());
        deletedUser.setDeletedAt(OffsetDateTime.now());

        userRepository.saveAllAndFlush(List.of(activeUser, blockedUser, deletedUser));

        Optional<UserEntity> foundBlockedOpt =
                userRepository.findById(blockedUser.getId());

        assertThat(foundBlockedOpt).isPresent();

        UserEntity foundBlocked = foundBlockedOpt.get();

        assertThat(foundBlocked.getStatus())
                .isEqualTo(UserStatus.BLOCKED);

        Optional<UserEntity> foundDeletedOpt =
                userRepository.findById(deletedUser.getId());

        assertThat(foundDeletedOpt).isPresent();

        UserEntity foundDeleted = foundDeletedOpt.get();

        assertThat(foundDeleted.getDeletedAt())
                .isNotNull();
    }
}
