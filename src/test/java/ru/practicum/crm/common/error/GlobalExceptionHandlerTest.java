package ru.practicum.crm.common.error;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.core.MethodParameter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(controllers = GlobalExceptionHandlerTest.ProbeConfiguration.ProbeController.class)
@Import(GlobalExceptionHandler.class)
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void apiException_whenObjectMissing_returnsNotFoundWithCode() throws Exception {
        mockMvc.perform(get("/test-errors/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("https://crm.example/problems/not-found"))
                .andExpect(jsonPath("$.title").value("Ресурс не найден"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Заявка не найдена."))
                .andExpect(jsonPath("$.instance").value("/test-errors/not-found"))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void unexpectedException_whenThrown_returnsFixedTextWithoutInternals() throws Exception {
        mockMvc.perform(get("/test-errors/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.title").value("Внутренняя ошибка сервиса"))
                .andExpect(jsonPath("$.detail")
                        .value(ErrorCode.INTERNAL_ERROR.getDefaultDetail()))
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(jsonPath("$.exception").doesNotExist());
    }

    @Test
    void validationException_whenFieldsInvalid_returnsAllFieldsAsPointers() throws Exception {
        mockMvc.perform(get("/test-errors/invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.length()").value(2))
                .andExpect(jsonPath("$.errors[0].pointer").value("#/subject"))
                .andExpect(jsonPath("$.errors[0].detail").value("не должно быть пустым"))
                .andExpect(jsonPath("$.errors[1].pointer").value("#/items/0/name"))
                .andExpect(jsonPath("$.errors[0].parameter").doesNotExist());
    }

    @Test
    void springOwnError_whenMethodNotAllowed_isReturnedInSameFormat() throws Exception {
        mockMvc.perform(post("/test-errors/not-found"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.title").value("Метод не поддерживается"))
                .andExpect(jsonPath("$.type")
                        .value("https://crm.example/problems/method-not-allowed"));
    }

    @TestConfiguration
    static class ProbeConfiguration {

        @RestController
        static class ProbeController {

            @GetMapping("/test-errors/not-found")
            String notFound() {
                throw new ApiException(ErrorCode.NOT_FOUND, "Заявка не найдена.");
            }

            @GetMapping("/test-errors/boom")
            String boom() {
                throw new IllegalStateException("таблица requests недоступна, SQL: select 1");
            }

            @GetMapping("/test-errors/invalid")
            String invalid() throws Exception {
                Method method = ProbeController.class.getDeclaredMethod("invalid");
                BeanPropertyBindingResult binding =
                        new BeanPropertyBindingResult(new Object(), "request");
                binding.addError(new FieldError("request", "subject", "не должно быть пустым"));
                binding.addError(new FieldError("request", "items[0].name",
                        "не должно быть пустым"));
                throw new MethodArgumentNotValidException(new MethodParameter(method, -1),
                        binding);
            }
        }
    }
}
