package ru.otus.socialnetwork.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.otus.socialnetwork.domain.FeedPost;

@Repository
public class PostRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public PostRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID insert(int authorId, String text) {
        UUID id = jdbc.queryForObject("""
                INSERT INTO posts (author_user_id, text) VALUES (:authorId, :text)
                RETURNING id
                """, Map.of("authorId", authorId, "text", text), UUID.class);
        if (id == null) {
            throw new IllegalStateException("INSERT did not return a post ID");
        }
        return id;
    }

    public boolean update(int authorId, UUID id, String text) {
        return jdbc.update("""
                UPDATE posts SET text = :text WHERE id = :id AND author_user_id = :authorId
                """, Map.of("id", id, "authorId", authorId, "text", text)) > 0;
    }

    public boolean delete(int authorId, UUID id) {
        return jdbc.update("""
                DELETE FROM posts WHERE id = :id AND author_user_id = :authorId
                """, Map.of("id", id, "authorId", authorId)) > 0;
    }

    public Optional<FeedPost> findForFeed(int authorId, UUID id) {
        return jdbc.query("""
                SELECT id, author_user_id, text, created_at
                FROM posts WHERE id = :id AND author_user_id = :authorId
                """, Map.of("id", id, "authorId", authorId), PostRepository::mapFeedPost).stream().findFirst();
    }

    public List<FeedPost> findLatestFriendPosts(int readerId, int limit) {
        return jdbc.query("""
                SELECT p.id, p.author_user_id, p.text, p.created_at
                FROM friendships f
                JOIN posts p ON p.author_user_id = f.friend_id
                WHERE f.user_id = :readerId
                ORDER BY p.created_at DESC, p.id DESC
                LIMIT :limit
                """, Map.of("readerId", readerId, "limit", limit), PostRepository::mapFeedPost);
    }

    private static FeedPost mapFeedPost(ResultSet row, int number) throws SQLException {
        return new FeedPost(row.getObject("id", UUID.class), row.getInt("author_user_id"), row.getString("text"),
                row.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
