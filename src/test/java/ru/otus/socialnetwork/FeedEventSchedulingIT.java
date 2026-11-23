package ru.otus.socialnetwork;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"app.feed.worker-enabled=true", "app.feed.poll-delay=100ms"})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FeedEventSchedulingIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;

    @Test
    void scheduledWorkerPicksUpCommittedEvent() {
        int readerId = Integer.MAX_VALUE;
        String key = "feed:{" + readerId + "}:posts";
        redis.opsForHash().put(key, "ready", "1");
        redis.expire(key, Duration.ofMinutes(1));
        Long eventId = jdbc.queryForObject("""
                INSERT INTO feed_events (event_type, user_id)
                VALUES ('FRIENDSHIPS_CHANGED', ?) RETURNING id
                """, Long.class, readerId);
        try {
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                assertThat(jdbc.queryForObject("SELECT processed_at IS NOT NULL FROM feed_events WHERE id = ?",
                        Boolean.class, eventId)).isTrue();
                assertThat(redis.hasKey(key)).isFalse();
            });
        } finally {
            redis.delete(key);
            jdbc.update("DELETE FROM feed_events WHERE id = ?", eventId);
        }
    }
}
