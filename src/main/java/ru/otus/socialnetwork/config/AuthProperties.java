package ru.otus.socialnetwork.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.auth")
public record AuthProperties(Duration tokenTtl) {
    public AuthProperties {
        if (tokenTtl == null || tokenTtl.isNegative() || tokenTtl.isZero()) {
            throw new IllegalArgumentException("app.auth.token-ttl must be positive");
        }
    }
}
