package ru.otus.socialnetwork.repository;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FriendshipRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public FriendshipRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public AddResult add(int userId, int friendId) {
        return jdbc.queryForObject("""
                WITH friend AS (
                    SELECT id FROM users WHERE id = :friendId FOR KEY SHARE
                ), added AS (
                    INSERT INTO friendships (user_id, friend_id)
                    SELECT :userId, id FROM friend
                    ON CONFLICT (user_id, friend_id) DO NOTHING
                    RETURNING user_id
                )
                SELECT EXISTS (SELECT 1 FROM friend) AS friend_exists,
                       EXISTS (SELECT 1 FROM added) AS added
                """, Map.of("userId", userId, "friendId", friendId),
                (row, number) -> new AddResult(row.getBoolean("friend_exists"), row.getBoolean("added")));
    }

    public boolean delete(int userId, int friendId) {
        return jdbc.update("""
                DELETE FROM friendships WHERE user_id = :userId AND friend_id = :friendId
                """, Map.of("userId", userId, "friendId", friendId)) > 0;
    }

    public List<Integer> findReadersOf(int authorId) {
        return jdbc.queryForList("""
                SELECT user_id FROM friendships WHERE friend_id = :authorId
                ORDER BY user_id
                """, Map.of("authorId", authorId), Integer.class);
    }

    public record AddResult(boolean friendExists, boolean added) {}
}
