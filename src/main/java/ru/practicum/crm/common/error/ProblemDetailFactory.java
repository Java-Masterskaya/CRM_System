package ru.practicum.crm.common.error;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.http.ProblemDetail;

public final class ProblemDetailFactory {

    static final String CODE_PROPERTY = "code";
    static final String REQUEST_ID_PROPERTY = "requestId";
    static final String ERRORS_PROPERTY = "errors";
    static final String MDC_REQUEST_ID = "requestId";

    private ProblemDetailFactory() {
    }

    public static ProblemDetail create(ErrorCode code, String detail, URI instance,
            List<ValidationError> errors) {
        ProblemDetail problem = ProblemDetail.forStatus(code.getStatus());
        problem.setType(code.getType());
        problem.setTitle(code.getTitle());
        problem.setDetail(detail == null || detail.isBlank() ? code.getDefaultDetail() : detail);
        if (instance != null) {
            problem.setInstance(instance);
        }
        problem.setProperty(CODE_PROPERTY, code.name());
        addRequestId(problem);
        if (errors != null && !errors.isEmpty()) {
            problem.setProperty(ERRORS_PROPERTY, List.copyOf(errors));
        }
        return problem;
    }

    public static void enrich(ProblemDetail problem, ErrorCode code, URI instance) {
        if (code == null || hasCode(problem)) {
            return;
        }
        problem.setType(code.getType());
        problem.setTitle(code.getTitle());
        problem.setDetail(code.getDefaultDetail());
        if (problem.getInstance() == null && instance != null) {
            problem.setInstance(instance);
        }
        problem.setProperty(CODE_PROPERTY, code.name());
        addRequestId(problem);
    }

    private static boolean hasCode(ProblemDetail problem) {
        Map<String, Object> properties = problem.getProperties();
        return properties != null && properties.containsKey(CODE_PROPERTY);
    }

    private static void addRequestId(ProblemDetail problem) {
        String requestId = MDC.get(MDC_REQUEST_ID);
        if (requestId != null && !requestId.isBlank()) {
            problem.setProperty(REQUEST_ID_PROPERTY, requestId);
        }
    }
}
