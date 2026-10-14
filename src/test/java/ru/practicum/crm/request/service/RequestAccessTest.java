package ru.practicum.crm.request.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.common.error.NotFoundException;
import ru.practicum.crm.common.security.CurrentUser;
import ru.practicum.crm.request.domain.Request;
import ru.practicum.crm.request.domain.RequestStatus;
import ru.practicum.crm.request.repository.RequestRepository;

/**
 * Проверка владения заявкой без базы. Право на операцию ({@code @PreAuthorize}) здесь не
 * действует — его проверяет {@code RequestAccessIntegrationTest}.
 */
class RequestAccessTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID REQUEST_ID = UUID.randomUUID();
    private static final UUID AUTHOR_ID = UUID.randomUUID();
    private static final UUID SOMEONE_ELSE = UUID.randomUUID();

    private final RequestRepository repository = mock(RequestRepository.class);
    private final CurrentUser currentUser = mock(CurrentUser.class);
    private final RequestAccess access = new RequestAccess(repository, currentUser);

    @Test
    void readable_withReadOwnPermission_returnsOwnRequest() {
        Request own = storedRequest();
        signedInAs(AUTHOR_ID, false);

        assertThat(access.readable(TENANT_ID, REQUEST_ID)).isSameAs(own);
    }

    /** DoD: клиент, запросивший чужую заявку, получает «не найдено», а не «запрещено». */
    @Test
    void readable_withReadOwnPermission_hidesForeignRequestAsNotFound() {
        storedRequest();
        signedInAs(SOMEONE_ELSE, false);

        NotFoundException thrown = catchThrowableOfType(
                () -> access.readable(TENANT_ID, REQUEST_ID), NotFoundException.class);

        assertThat(thrown.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void readable_withReadAllPermission_returnsAnyRequestOfTenant() {
        Request foreign = storedRequest();
        signedInAs(SOMEONE_ELSE, true);

        assertThat(access.readable(TENANT_ID, REQUEST_ID)).isSameAs(foreign);
    }

    @Test
    void readable_whenRequestMissing_isNotFoundEvenWithReadAllPermission() {
        when(repository.findByIdAndTenantIdAndDeletedFalse(REQUEST_ID, TENANT_ID))
                .thenReturn(Optional.empty());
        signedInAs(AUTHOR_ID, true);

        NotFoundException thrown = catchThrowableOfType(
                () -> access.readable(TENANT_ID, REQUEST_ID), NotFoundException.class);

        assertThat(thrown.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
    }

    private Request storedRequest() {
        Request request = new Request(TENANT_ID, AUTHOR_ID, "Тема", "Описание", RequestStatus.NEW);
        when(repository.findByIdAndTenantIdAndDeletedFalse(REQUEST_ID, TENANT_ID))
                .thenReturn(Optional.of(request));
        return request;
    }

    private void signedInAs(UUID userId, boolean readAll) {
        when(currentUser.id()).thenReturn(userId);
        when(currentUser.hasPermission("REQUEST_READ_ALL")).thenReturn(readAll);
    }
}
