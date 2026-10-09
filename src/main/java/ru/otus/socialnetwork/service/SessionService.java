package ru.otus.socialnetwork.service;

import java.time.Clock;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.otus.socialnetwork.repository.SessionRepository;

/** Session lookup for future protected endpoints. Current homework endpoints are public. */
@Service
public class SessionService {
    private final SessionRepository sessions;
    private final Clock clock;

    public SessionService(SessionRepository sessions, Clock clock) {
        this.sessions = sessions;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Optional<Integer> resolveUserId(String token) {
        if (token == null || !token.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")) {
            return Optional.empty();
        }
        return sessions.findActiveUserId(TokenHashes.sha256(token), clock.instant());
    }
}
