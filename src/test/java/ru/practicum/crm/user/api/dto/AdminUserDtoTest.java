package ru.practicum.crm.user.api.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class AdminUserDtoTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    @ParameterizedTest
    @MethodSource("invalidCreationRoles")
    void givenInvalidRoles_whenValidatingCreation_thenRejectsRequest(List<String> roles) {
        AdminUserCreateRequest request = new AdminUserCreateRequest(
                "operator@example.com", "Оператор", "StrongPassword1!", roles);

        assertThat(validator.validate(request)).isNotEmpty();
    }

    static Stream<List<String>> invalidCreationRoles() {
        return Stream.of(null, List.of(), List.of("CLIENT"), List.of("UNKNOWN"),
                List.of("ADMIN", "ADMIN"), Arrays.asList("OPERATOR", null));
    }

    @Test
    void givenOperatorAndAdmin_whenValidatingCreation_thenAcceptsRequest() {
        AdminUserCreateRequest request = new AdminUserCreateRequest(
                " operator@example.com ", " Оператор ", "StrongPassword1!",
                List.of("OPERATOR", "ADMIN"));

        assertThat(validator.validate(request)).isEmpty();
        assertThat(request.email()).isEqualTo("operator@example.com");
        assertThat(request.name()).isEqualTo("Оператор");
        assertThat(request.toString()).doesNotContain(request.temporaryPassword());
    }

    @Test
    void givenInvalidUpdates_whenValidating_thenRejectsMissingStatusAndDuplicateRoles() {
        assertThat(validator.validate(new AdminUserStatusUpdateRequest(null))).isNotEmpty();
        assertThat(validator.validate(new AdminUserRolesUpdateRequest(
                List.of("ADMIN", "ADMIN")))).isNotEmpty();
    }

    @Test
    void givenClientAndAdmin_whenValidatingRoleUpdate_thenAcceptsCombination() {
        assertThat(validator.validate(new AdminUserRolesUpdateRequest(
                List.of("CLIENT", "ADMIN")))).isEmpty();
    }

    @Test
    void givenMutableRoles_whenCreatingRequests_thenProtectsRoleLists() {
        List<String> roles = new ArrayList<>(List.of("OPERATOR"));
        AdminUserCreateRequest creation = new AdminUserCreateRequest(
                "operator@example.com", "Оператор", "StrongPassword1!", roles);
        AdminUserRolesUpdateRequest update = new AdminUserRolesUpdateRequest(roles);
        roles.clear();

        assertThat(creation.roles()).containsExactly("OPERATOR");
        assertThat(update.roles()).containsExactly("OPERATOR");
        assertThatThrownBy(() -> creation.roles().add("ADMIN"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> update.roles().add("ADMIN"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void givenUserResponse_whenSerializing_thenContainsOnlyPublicFields() throws Exception {
        List<String> roles = new ArrayList<>(List.of("OPERATOR", "ADMIN"));
        AdminUserResponse response = new AdminUserResponse(UUID.randomUUID(),
                "operator@example.com", "Оператор", UserAccountStatus.ACTIVE, roles);
        roles.clear();

        JsonNode json = new ObjectMapper().valueToTree(response);

        assertThat(json.size()).isEqualTo(5);
        assertThat(json.has("id")).isTrue();
        assertThat(json.has("email")).isTrue();
        assertThat(json.has("name")).isTrue();
        assertThat(json.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(json.path("roles").size()).isEqualTo(2);
        assertThat(response.roles()).containsExactly("ADMIN", "OPERATOR");
        assertThatThrownBy(() -> response.roles().add("CLIENT"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
