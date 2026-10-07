package ru.practicum.crm.platform.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    static final String TITLE = "CRM «Заявки на обработку данных»";
    static final String CONTRACT_VERSION = "v1";

    @Bean
    public OpenAPI crmOpenApi() {
        return new OpenAPI().info(new Info()
                .title(TITLE)
                .description("Многоарендная CRM-система приёма и ведения заявок "
                        + "на обработку данных.")
                .version(CONTRACT_VERSION)
                .contact(new Contact()
                        .name("Java-Masterskaya / CRM_System")
                        .url("https://github.com/Java-Masterskaya/CRM_System")));
    }
}
