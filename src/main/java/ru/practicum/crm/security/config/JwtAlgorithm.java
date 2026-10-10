package ru.practicum.crm.security.config;

import com.nimbusds.jose.JWSAlgorithm;
import java.util.Arrays;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;

public enum JwtAlgorithm {

    HMAC_SHA256(
            "HmacSHA256",
            JWSAlgorithm.HS256,
            MacAlgorithm.HS256
    ),

    HMAC_SHA384(
            "HmacSHA384",
            JWSAlgorithm.HS384,
            MacAlgorithm.HS384
    ),

    HMAC_SHA512(
            "HmacSHA512",
            JWSAlgorithm.HS512,
            MacAlgorithm.HS512
    );

    private final String jcaName;
    private final JWSAlgorithm jwsAlgorithm;
    private final MacAlgorithm macAlgorithm;

    JwtAlgorithm(
            String jcaName,
            JWSAlgorithm jwsAlgorithm,
            MacAlgorithm macAlgorithm
    ) {
        this.jcaName = jcaName;
        this.jwsAlgorithm = jwsAlgorithm;
        this.macAlgorithm = macAlgorithm;
    }

    public String jcaName() {
        return jcaName;
    }

    public JWSAlgorithm jwsAlgorithm() {
        return jwsAlgorithm;
    }

    public MacAlgorithm macAlgorithm() {
        return macAlgorithm;
    }

    public static JwtAlgorithm fromJcaName(String value) {
        return Arrays.stream(values())
                .filter(algorithm -> algorithm.jcaName.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Неподдерживаемый JWT алгоритм: " + value
                ));
    }
}
