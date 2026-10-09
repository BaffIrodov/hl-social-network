package ru.otus.socialnetwork.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SessionRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public SessionRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void insert(String tokenHash, int userId, Instant createdAt, Instant expiresAt) {
        jdbc.update("""
                INSERT INTO user_sessions (token_hash, user_id, created_at, expires_at)
                VALUES (:hash, :id, :createdAt, :expiresAt)
                """, Map.of("hash", tokenHash, "id", userId,
                "createdAt", Timestamp.from(createdAt), "expiresAt", Timestamp.from(expiresAt)));
    }

    public Optional<Integer> findActiveUserId(String tokenHash, Instant now) {
        return jdbc.query("""
                SELECT user_id FROM user_sessions
                WHERE token_hash = :hash AND expires_at > :now
                """, Map.of("hash", tokenHash, "now", Timestamp.from(now)),
                (rs, row) -> rs.getInt("user_id")).stream().findFirst();
    }
}
