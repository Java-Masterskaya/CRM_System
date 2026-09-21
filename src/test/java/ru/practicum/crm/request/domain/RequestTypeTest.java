package ru.practicum.crm.request.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RequestTypeTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private RequestType type;

    @BeforeEach
    void setUp() {
        type = new RequestType(TENANT_ID, "Выгрузка данных");
    }

    @Test
    void newType_whenCreated_belongsToTenantAndIsActive() {
        assertThat(type.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(type.getName()).isEqualTo("Выгрузка данных");
        assertThat(type.isActive()).isTrue();
        assertThat(type.getDescription()).isNull();
        assertThat(type.getDefaultPriority()).isNull();
    }

    @Test
    void describe_whenCalled_replacesNameDescriptionAndDefaultPriority() {
        type.describe("Импорт данных", "Загрузка файлов клиента", "HIGH");

        assertThat(type.getName()).isEqualTo("Импорт данных");
        assertThat(type.getDescription()).isEqualTo("Загрузка файлов клиента");
        assertThat(type.getDefaultPriority()).isEqualTo("HIGH");
    }

    @Test
    void describe_whenOptionalFieldsOmitted_clearsThem() {
        type.describe("Импорт данных", "Загрузка файлов", "HIGH");

        type.describe("Импорт данных", null, null);

        assertThat(type.getDescription()).isNull();
        assertThat(type.getDefaultPriority()).isNull();
    }

    @Test
    void deactivate_whenCalled_makesTypeUnavailableForNewRequests() {
        type.deactivate();

        assertThat(type.isActive()).isFalse();
    }

    @Test
    void activate_whenCalledAfterDeactivation_returnsTypeToService() {
        type.deactivate();

        type.activate();

        assertThat(type.isActive()).isTrue();
    }

    @Test
    void onCreate_whenCalled_setsCreatedAndUpdatedTimes() {
        type.onCreate();

        assertThat(type.getCreatedAt()).isNotNull();
        assertThat(type.getUpdatedAt()).isEqualTo(type.getCreatedAt());
    }

    @Test
    void onUpdate_whenCalled_movesUpdatedAtAndLeavesCreatedAtIntact() {
        type.onCreate();
        Instant createdAt = type.getCreatedAt();

        type.onUpdate();

        assertThat(type.getCreatedAt()).isEqualTo(createdAt);
        assertThat(type.getUpdatedAt()).isAfterOrEqualTo(createdAt);
    }

    @Test
    void equals_whenTypesAreNotSavedYet_distinguishesThemByIdentity() {
        RequestType other = new RequestType(TENANT_ID, "Выгрузка данных");

        assertThat(type).isEqualTo(type)
                .isNotEqualTo(other)
                .isNotEqualTo(null)
                .isNotEqualTo("не тип заявки");
        assertThat(type.hashCode()).isEqualTo(other.hashCode());
    }
}
