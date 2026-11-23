package ru.otus.socialnetwork.repository;

import java.sql.Types;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.otus.socialnetwork.domain.FeedEventType;

@Repository
public class FeedEventRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public FeedEventRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void append(FeedEventType type, int userId, UUID postId) {
        var parameters = new MapSqlParameterSource()
                .addValue("type", type.name())
                .addValue("userId", userId)
                .addValue("postId", postId, Types.OTHER);
        jdbc.update("""
                INSERT INTO feed_events (event_type, user_id, post_id) VALUES (:type, :userId, :postId)
                """, parameters);
    }

    public Optional<PendingEvent> lockNextPending() {
        return jdbc.query("""
                SELECT id, event_type, user_id, post_id
                FROM feed_events
                WHERE processed_at IS NULL
                ORDER BY id
                LIMIT 1
                FOR UPDATE
                """, Map.of(), (row, number) -> new PendingEvent(row.getLong("id"),
                FeedEventType.valueOf(row.getString("event_type")), row.getInt("user_id"),
                row.getObject("post_id", UUID.class))).stream().findFirst();
    }

    public void markProcessed(long id) {
        jdbc.update("UPDATE feed_events SET processed_at = clock_timestamp() WHERE id = :id", Map.of("id", id));
    }

    public record PendingEvent(long id, FeedEventType type, int userId, UUID postId) {}
}
