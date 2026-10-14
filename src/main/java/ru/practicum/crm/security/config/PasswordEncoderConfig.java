package ru.practicum.crm.security.config;

import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import ru.practicum.crm.security.api.dto.DummyPasswordHash;

@Configuration
@RequiredArgsConstructor
public class PasswordEncoderConfig {

    private final PasswordPolicyProperties properties;

    @Bean
    public PasswordEncoder passwordEncoder() {
        String encodingId = "bcrypt";
        Map<String, PasswordEncoder> encoders = new HashMap<>();
        encoders.put(encodingId, new BCryptPasswordEncoder(properties.bcryptStrength()));
        return new DelegatingPasswordEncoder(encodingId, encoders);
    }

    @Bean
    public DummyPasswordHash dummyPasswordHash(PasswordEncoder passwordEncoder) {
        return new DummyPasswordHash(
                passwordEncoder.encode("dummy-password-for-timing-attack-protection")
        );
    }
}
