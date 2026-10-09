package ru.otus.socialnetwork.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration(proxyBeanMethods = false)
public class CryptoConfiguration {
    @Bean
    PasswordEncoder passwordEncoder() {
        // Salt: 16 bytes; hash: 32 bytes; parallelism: 1; memory: 19 MiB; iterations: 2.
        return new Argon2PasswordEncoder(16, 32, 1, 19456, 2);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
