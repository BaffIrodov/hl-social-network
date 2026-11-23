package ru.otus.socialnetwork;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import ru.otus.socialnetwork.domain.FeedPost;
import ru.otus.socialnetwork.service.FeedEventProcessor;
import ru.otus.socialnetwork.service.FriendshipService;
import ru.otus.socialnetwork.service.PostService;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class FeedEventProcessorIT {
    private static final RedisScript<Long> EXPIRES_AT =
            RedisScript.of("return redis.call('PEXPIRETIME', KEYS[1])", Long.class);

    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired ObjectMapper mapper;
    @Autowired FeedEventProcessor processor;
    @Autowired PostService posts;
    @Autowired FriendshipService friendships;

    private final List<String> cacheKeys = new ArrayList<>();

    @BeforeEach
    void clearDedicatedTestDatabase() {
        jdbc.execute("TRUNCATE users, feed_events RESTART IDENTITY CASCADE");
    }

    @AfterEach
    void clearTestFeeds() {
        if (!cacheKeys.isEmpty()) {
            redis.delete(cacheKeys);
        }
    }

    @Test
    void newPostUpdatesOnlyReadyReadersAndKeepsLatestThousandPosts() {
        int author = user();
        int reader = user();
        int emptyFeedReader = user();
        int coldReader = user();
        int unrelated = user();
        follow(reader, author);
        follow(emptyFeedReader, author);
        follow(coldReader, author);
        readyFeed(reader, 1_000);
        readyFeed(emptyFeedReader, 0);
        readyFeed(unrelated, 1);
        Long expiresBefore = expiresAt(postKey(reader));
        assertThat(expiresBefore).isPositive();

        UUID postId = posts.create(author, "Новый пост");
        processor.processPendingEvents();

        assertThat(redis.opsForZSet().size(orderKey(reader))).isEqualTo(1_000);
        assertThat(redis.opsForZSet().reverseRange(orderKey(reader), 0, 0)).containsExactly(postId.toString());
        assertThat(redis.opsForZSet().score(orderKey(reader), oldPostId(1))).isNull();
        assertThat(redis.opsForHash().hasKey(postKey(reader), oldPostId(1))).isFalse();
        assertThat(redis.opsForHash().size(postKey(reader))).isEqualTo(1_001);
        assertThat(expiresAt(orderKey(reader))).isEqualTo(expiresBefore);
        assertThat(expiresAt(postKey(reader))).isEqualTo(expiresBefore);
        String body = (String) redis.opsForHash().get(postKey(reader), postId.toString());
        var queuedPost = mapper.readValue(body, FeedPost.class);
        assertThat(queuedPost.id()).isEqualTo(postId);
        assertThat(queuedPost.authorUserId()).isEqualTo(author);
        assertThat(queuedPost.text()).isEqualTo("Новый пост");
        assertThat(redis.opsForZSet().range(orderKey(emptyFeedReader), 0, -1)).containsExactly(postId.toString());
        assertThat(redis.hasKey(orderKey(coldReader))).isFalse();
        assertThat(redis.hasKey(postKey(coldReader))).isFalse();
        assertThat(redis.opsForZSet().range(orderKey(unrelated), 0, -1)).containsExactly(oldPostId(1));
        assertThat(pending()).isZero();
    }

    @Test
    void postEditAndDeleteInvalidateReaderFeeds() {
        int author = user();
        int reader = user();
        int unrelated = user();
        follow(reader, author);
        readyFeed(reader, 1);
        readyFeed(unrelated, 1);
        UUID postId = posts.create(author, "Старый текст");
        processor.processPendingEvents();
        assertThat(redis.opsForHash().hasKey(postKey(reader), postId.toString())).isTrue();

        posts.update(author, postId, "Новый текст");
        processor.processPendingEvents();
        assertInvalidated(reader);
        assertThat(redis.hasKey(postKey(unrelated))).isTrue();

        readyFeed(reader, 1);
        posts.delete(author, postId);
        processor.processPendingEvents();
        assertInvalidated(reader);
        assertThat(redis.hasKey(postKey(unrelated))).isTrue();
        assertThat(pending()).isZero();
    }

    @Test
    void addingAndRemovingFriendInvalidateOnlyTheOwnersFeed() {
        int reader = user();
        int friend = user();
        readyFeed(reader, 1);
        readyFeed(friend, 1);

        friendships.add(reader, friend);
        processor.processPendingEvents();
        assertInvalidated(reader);
        assertThat(redis.hasKey(postKey(friend))).isTrue();

        readyFeed(reader, 1);
        friendships.delete(reader, friend);
        processor.processPendingEvents();
        assertInvalidated(reader);
        assertThat(redis.hasKey(postKey(friend))).isTrue();
        assertThat(pending()).isZero();
    }

    @Test
    void redisFailureKeepsEventPendingAndRetryDoesNotDuplicateThePost() {
        int author = user();
        int reader = user();
        int failingReader = user();
        follow(reader, author);
        follow(failingReader, author);
        readyFeed(reader, 0);
        readyFeed(failingReader, 0);
        redis.opsForValue().set(orderKey(failingReader), "wrong type");

        UUID postId = posts.create(author, "Пост для повтора");
        assertThatThrownBy(processor::processPendingEvents).isInstanceOf(DataAccessException.class);
        assertThat(pending()).isEqualTo(1);
        assertThat(redis.opsForZSet().range(orderKey(reader), 0, -1)).containsExactly(postId.toString());

        redis.delete(orderKey(failingReader));
        processor.processPendingEvents();
        assertThat(pending()).isZero();
        assertThat(redis.opsForZSet().range(orderKey(reader), 0, -1)).containsExactly(postId.toString());
        assertThat(redis.opsForZSet().range(orderKey(failingReader), 0, -1)).containsExactly(postId.toString());
    }

    private int user() {
        return jdbc.queryForObject("""
                INSERT INTO users (first_name, second_name, birthdate)
                VALUES ('Иван', 'Иванов', DATE '1995-05-20') RETURNING id
                """, Integer.class);
    }

    private void follow(int reader, int author) {
        jdbc.update("INSERT INTO friendships (user_id, friend_id) VALUES (?, ?)", reader, author);
    }

    private void readyFeed(int readerId, int postCount) {
        String order = orderKey(readerId);
        String posts = postKey(readerId);
        cacheKeys.add(order);
        cacheKeys.add(posts);
        var payloads = new HashMap<String, String>();
        payloads.put("ready", "1");
        Set<TypedTuple<String>> ordered = new HashSet<>();
        for (int index = 1; index <= postCount; index++) {
            String id = oldPostId(index);
            payloads.put(id, "{}");
            ordered.add(new DefaultTypedTuple<>(id, (double) index));
        }
        redis.opsForHash().putAll(posts, payloads);
        redis.expire(posts, Duration.ofMinutes(10));
        if (!ordered.isEmpty()) {
            redis.opsForZSet().add(order, ordered);
            redis.expire(order, Duration.ofMinutes(10));
        }
    }

    private void assertInvalidated(int readerId) {
        assertThat(redis.hasKey(orderKey(readerId))).isFalse();
        assertThat(redis.hasKey(postKey(readerId))).isFalse();
    }

    private Integer pending() {
        return jdbc.queryForObject("SELECT count(*) FROM feed_events WHERE processed_at IS NULL", Integer.class);
    }

    private Long expiresAt(String key) {
        return redis.execute(EXPIRES_AT, List.of(key));
    }

    private static String oldPostId(int number) {
        return new UUID(0, number).toString();
    }

    private static String orderKey(int readerId) {
        return "feed:{" + readerId + "}:order";
    }

    private static String postKey(int readerId) {
        return "feed:{" + readerId + "}:posts";
    }
}
