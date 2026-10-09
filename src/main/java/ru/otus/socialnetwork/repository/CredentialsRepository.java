package ru.otus.socialnetwork.repository;

import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CredentialsRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public CredentialsRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void insert(int userId, String passwordHash) {
        jdbc.update("INSERT INTO user_credentials (user_id, password_hash) VALUES (:id, :hash)",
                Map.of("id", userId, "hash", passwordHash));
    }

    public Optional<String> findPasswordHash(int userId) {
        return jdbc.query("SELECT password_hash FROM user_credentials WHERE user_id = :id",
                Map.of("id", userId), (rs, row) -> rs.getString("password_hash")).stream().findFirst();
    }
}
