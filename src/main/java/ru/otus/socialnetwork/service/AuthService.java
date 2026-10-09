package ru.otus.socialnetwork.service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.otus.socialnetwork.api.ApiException;
import ru.otus.socialnetwork.config.AuthProperties;
import ru.otus.socialnetwork.repository.CredentialsRepository;
import ru.otus.socialnetwork.repository.SessionRepository;
import ru.otus.socialnetwork.validation.PasswordPolicy;

@Service
public class AuthService {
    private final CredentialsRepository credentials;
    private final SessionRepository sessions;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final AuthProperties properties;
    private final Clock clock;

    public AuthService(CredentialsRepository credentials, SessionRepository sessions,
                       PasswordEncoder passwordEncoder, PasswordPolicy passwordPolicy,
                       AuthProperties properties, Clock clock) {
        this.credentials = credentials;
        this.sessions = sessions;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public String login(int userId, String password) {
        passwordPolicy.validate(password);
        String storedHash = credentials.findPasswordHash(userId).orElseThrow(ApiException::userNotFound);
        if (!passwordEncoder.matches(password, storedHash)) {
            throw ApiException.badRequest("Неверный пароль", "password");
        }
        String token = UUID.randomUUID().toString();
        Instant now = clock.instant();
        sessions.insert(TokenHashes.sha256(token), userId, now, now.plus(properties.tokenTtl()));
        return token;
    }
}
