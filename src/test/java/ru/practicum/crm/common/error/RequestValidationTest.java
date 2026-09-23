package ru.practicum.crm.common.error;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(
        controllers = RequestValidationTest.ProbeController.class,
        properties = "app.web.max-request-body-size=1KB"
)
@Import(RequestValidationTest.ProbeController.class)
class RequestValidationTest {

    private static final String BODY_URL = "/test-validation/requests";
    private static final String PARAMS_URL = "/test-validation/items";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void body_whenSeveralFieldsInvalid_returnsAllProblemsInOneResponse() throws Exception {
        mockMvc.perform(post(BODY_URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"\",\"email\":\"not-an-email\",\"count\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.length()").value(3))
                .andExpect(jsonPath("$.errors[*].pointer")
                        .value(containsInAnyOrder("#/subject", "#/email", "#/count")));
    }

    @Test
    void body_whenFieldIsBlank_returnsRussianMessage() throws Exception {
        mockMvc.perform(post(BODY_URL).contentType(MediaType.APPLICATION_JSON)
                        .content(body("", "user@example.com", "1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].pointer").value("#/subject"))
                .andExpect(jsonPath("$.errors[0].detail").value("не должно быть пустым"));
    }

    @Test
    void body_whenTextLongerThanLimit_returnsSizeError() throws Exception {
        mockMvc.perform(post(BODY_URL).contentType(MediaType.APPLICATION_JSON)
                        .content(body("x".repeat(256), "user@example.com", "1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].pointer").value("#/subject"))
                .andExpect(jsonPath("$.errors[0].detail").value("размер должен быть от 0 до 255"));
    }

    @Test
    void body_whenEmailMalformed_returnsFormatError() throws Exception {
        mockMvc.perform(post(BODY_URL).contentType(MediaType.APPLICATION_JSON)
                        .content(body("Выгрузка", "not-an-email", "1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].pointer").value("#/email"))
                .andExpect(jsonPath("$.errors[0].detail")
                        .value("должно быть корректным адресом электронной почты"));
    }

    @Test
    void body_whenJsonMalformed_returnsMalformedRequest() throws Exception {
        mockMvc.perform(post(BODY_URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void body_whenLargerThanLimit_isRejectedWithoutProcessing() throws Exception {
        mockMvc.perform(post(BODY_URL).contentType(MediaType.APPLICATION_JSON)
                        .content(body("x".repeat(2000), "user@example.com", "1")))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
    }

    @Test
    void body_whenLimitExceededWhileReading_returnsPayloadTooLarge() throws Exception {
        mockMvc.perform(post("/test-validation/stream"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
    }

    @Test
    void param_whenConstraintViolated_returnsParameterError() throws Exception {
        mockMvc.perform(get(PARAMS_URL).param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.detail").value(GlobalExceptionHandler.PARAMETERS_DETAIL))
                .andExpect(jsonPath("$.errors[0].parameter").value("size"))
                .andExpect(jsonPath("$.errors[0].detail").value("должно быть не меньше 1"));
    }

    @Test
    void param_whenValueHasWrongType_returnsParameterError() throws Exception {
        mockMvc.perform(get(PARAMS_URL).param("size", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].parameter").value("size"))
                .andExpect(jsonPath("$.errors[0].detail")
                        .value(GlobalExceptionHandler.WRONG_TYPE_MESSAGE));
    }

    @Test
    void param_whenRequiredMissing_returnsParameterError() throws Exception {
        mockMvc.perform(get(PARAMS_URL))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].parameter").value(hasItem("size")))
                .andExpect(jsonPath("$.errors[0].detail")
                        .value(GlobalExceptionHandler.MISSING_PARAMETER_MESSAGE));
    }

    private static String body(String subject, String email, String count) {
        return "{\"subject\":\"" + subject + "\",\"email\":\"" + email + "\",\"count\":"
                + count + "}";
    }

    record ProbeRequest(
            @NotBlank @Size(max = 255) String subject,
            @NotBlank @Email String email,
            @NotNull Integer count) {
    }

    @RestController
    static class ProbeController {

        @PostMapping(BODY_URL)
        String create(@Valid @RequestBody ProbeRequest request) {
            return "ok";
        }

        @GetMapping(PARAMS_URL)
        String list(@RequestParam @Min(1) Integer size) {
            return "ok";
        }

        @PostMapping("/test-validation/stream")
        String stream(HttpServletRequest request) {
            throw new HttpMessageNotReadableException("Тело запроса слишком большое",
                    new PayloadTooLargeException(1024), new ServletServerHttpRequest(request));
        }
    }
}
