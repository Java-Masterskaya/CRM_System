package ru.practicum.crm.user.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.crm.user.domain.UserEntity;
import ru.practicum.crm.user.domain.UserStatus;

@Transactional
class UserRepositoryIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    private UUID tenantA;
    private UUID tenantB;

    @BeforeEach
    void setUpTenants() {
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();

        entityManager.createNativeQuery(
                        """
                        INSERT INTO tenants (id, name, active, created_at, updated_at)
                        VALUES
                            (:tenantA, 'Tenant A', true, NOW(), NOW()),
                            (:tenantB, 'Tenant B', true, NOW(), NOW())
                        """)
                .setParameter("tenantA", tenantA)
                .setParameter("tenantB", tenantB)
                .executeUpdate();

        entityManager.flush();
    }

    @Test
    @DisplayName("Один и тот же email может существовать у двух разных арендаторов")
    void sameEmailAllowedForDifferentTenants_returnsBothUsers() {
        String sharedEmail = "user@example.com";

        UserEntity userA =
                new UserEntity(tenantA, sharedEmail, "hash1", UserStatus.ACTIVE);
        UserEntity userB =
                new UserEntity(tenantB, sharedEmail, "hash2", UserStatus.ACTIVE);

        userRepository.save(userA);
        userRepository.save(userB);
        entityManager.flush();

        assertThat(userRepository.findByTenantIdAndEmail(tenantA, sharedEmail))
                .isPresent();
        assertThat(userRepository.findByTenantIdAndEmail(tenantB, sharedEmail))
                .isPresent();
    }

    @Test
    @DisplayName("Повторное создание с занятым email внутри арендатора отклоняется")
    void duplicateEmailInSameTenant_throwsException() {
        String email = "duplicate@example.com";

        UserEntity user1 =
                new UserEntity(tenantA, email, "hash1", UserStatus.ACTIVE);
        UserEntity user2 =
                new UserEntity(tenantA, email, "hash2", UserStatus.ACTIVE);

        userRepository.save(user1);
        entityManager.flush();

        userRepository.save(user2);

        assertThatThrownBy(() -> entityManager.flush())
                .isInstanceOf(ConstraintViolationException.class);
    }

    @Test
    @DisplayName("Email проверяется без учёта регистра")
    void duplicateEmailWithDifferentCase_throwsException() {
        UserEntity user1 =
                new UserEntity(
                        tenantA,
                        "User@Example.com",
                        "hash1",
                        UserStatus.ACTIVE);

        UserEntity user2 =
                new UserEntity(
                        tenantA,
                        "user@example.com",
                        "hash2",
                        UserStatus.ACTIVE);

        userRepository.save(user1);
        entityManager.flush();

        userRepository.save(user2);

        assertThatThrownBy(() -> entityManager.flush())
                .isInstanceOf(ConstraintViolationException.class);
    }

    @Test
    @DisplayName("Нельзя создать пользователя для несуществующего арендатора")
    void userWithUnknownTenant_throwsException() {
        UserEntity user =
                new UserEntity(
                        UUID.randomUUID(),
                        "user@example.com",
                        "hash",
                        UserStatus.ACTIVE);

        userRepository.save(user);

        assertThatThrownBy(() -> entityManager.flush())
                .isInstanceOf(ConstraintViolationException.class);
    }

    @Test
    @DisplayName("Активный запрос не возвращает мягко удалённых пользователей")
    void findAllActiveByTenantId_excludesDeletedUsers() {
        UserEntity activeUser =
                new UserEntity(
                        tenantA,
                        "active@example.com",
                        "hash",
                        UserStatus.ACTIVE);

        UserEntity blockedUser =
                new UserEntity(
                        tenantA,
                        "blocked@example.com",
                        "hash",
                        UserStatus.BLOCKED);

        UserEntity deletedUser =
                new UserEntity(
                        tenantA,
                        "deleted@example.com",
                        "hash",
                        UserStatus.ACTIVE);

        deletedUser.setDeletedAt(OffsetDateTime.now());

        userRepository.save(activeUser);
        userRepository.save(blockedUser);
        userRepository.save(deletedUser);
        entityManager.flush();

        List<UserEntity> users =
                userRepository.findAllActiveByTenantId(tenantA);

        assertThat(users)
                .extracting(UserEntity::getEmail)
                .containsExactlyInAnyOrder(
                        "active@example.com",
                        "blocked@example.com");
    }

    @Test
    @DisplayName("Заблокированный пользователь не может войти")
    void blockedUser_cannotLogIn() {
        UserEntity user =
                new UserEntity(
                        tenantA,
                        "blocked@example.com",
                        "hash",
                        UserStatus.ACTIVE);

        user.block();

        assertThat(user.canLogIn()).isFalse();
    }

    @Test
    @DisplayName("Заблокированный пользователь остаётся в списке пользователей арендатора")
    void blockedUser_remainsInTenantUsersList() {
        UserEntity user =
                new UserEntity(
                        tenantA,
                        "blocked@example.com",
                        "hash",
                        UserStatus.ACTIVE);

        user.block();

        userRepository.save(user);
        entityManager.flush();

        assertThat(userRepository.findAllActiveByTenantId(tenantA))
                .extracting(UserEntity::getEmail)
                .contains("blocked@example.com");
    }

    @Test
    @DisplayName("Удаление пользователя выполняется мягко")
    void deleteUser_marksUserAsDeleted() {
        UserEntity user =
                new UserEntity(
                        tenantA,
                        "deleted@example.com",
                        "hash",
                        UserStatus.ACTIVE);

        userRepository.save(user);
        entityManager.flush();

        user.delete();
        entityManager.flush();

        assertThat(user.getDeletedAt()).isNotNull();
        assertThat(userRepository.findById(user.getId(), tenantA))
                .isEmpty();

        assertThat(userRepository.findAllActiveByTenantId(tenantA))
                .extracting(UserEntity::getEmail)
                .doesNotContain("deleted@example.com");
    }

    @Test
    @DisplayName("Пользователь не находится через findById из другого арендатора")
    void findById_withAnotherTenant_returnsEmpty() {
        UserEntity user =
                new UserEntity(
                        tenantA,
                        "user@example.com",
                        "hash",
                        UserStatus.ACTIVE);

        userRepository.save(user);
        entityManager.flush();

        assertThat(userRepository.findById(user.getId(), tenantB))
                .isEmpty();

        assertThat(userRepository.findById(user.getId(), tenantA))
                .isPresent();
    }

    @Test
    @DisplayName("Поиск по email не возвращает мягко удалённого пользователя")
    void findByTenantIdAndEmail_excludesDeletedUser() {
        UserEntity user =
                new UserEntity(
                        tenantA,
                        "deleted@example.com",
                        "hash",
                        UserStatus.ACTIVE);

        userRepository.save(user);
        entityManager.flush();

        user.delete();
        entityManager.flush();

        assertThat(
                userRepository.findByTenantIdAndEmail(
                        tenantA,
                        "deleted@example.com"))
                .isEmpty();
    }

    @Test
    @DisplayName("После мягкого удаления email можно повторно использовать")
    void emailCanBeReused_afterSoftDelete() {
        String email = "reusable@example.com";

        UserEntity deletedUser =
                new UserEntity(
                        tenantA,
                        email,
                        "hash1",
                        UserStatus.ACTIVE);

        userRepository.save(deletedUser);
        entityManager.flush();

        deletedUser.delete();
        entityManager.flush();

        UserEntity newUser =
                new UserEntity(
                        tenantA,
                        email,
                        "hash2",
                        UserStatus.ACTIVE);

        userRepository.save(newUser);

        assertThatCode(() -> entityManager.flush())
                .doesNotThrowAnyException();
    }
}
