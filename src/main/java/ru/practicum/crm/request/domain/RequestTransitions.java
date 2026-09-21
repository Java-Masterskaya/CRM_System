package ru.practicum.crm.request.domain;

import java.util.Set;

public final class RequestTransitions {

    private RequestTransitions() {
    }

    public static boolean isAllowed(RequestStatus from, RequestStatus to) {
        if (from == null || to == null) {
            return false;
        }
        return allowedFrom(from).contains(to);
    }

    public static Set<RequestStatus> allowedFrom(RequestStatus from) {
        if (from == null) {
            return Set.of();
        }
        return switch (from) {
            case NEW -> Set.of(RequestStatus.CONTACTED, RequestStatus.CANCELLED);
            case CONTACTED -> Set.of(
                    RequestStatus.IN_PROGRESS,
                    RequestStatus.REJECTED,
                    RequestStatus.CANCELLED);
            case IN_PROGRESS -> Set.of(
                    RequestStatus.ON_HOLD,
                    RequestStatus.DONE,
                    RequestStatus.REJECTED,
                    RequestStatus.CANCELLED);
            case ON_HOLD -> Set.of(RequestStatus.IN_PROGRESS);
            case DONE, REJECTED, CANCELLED -> Set.of();
        };
    }

    public static boolean requiresReason(RequestStatus to) {
        return to == RequestStatus.REJECTED;
    }
}
