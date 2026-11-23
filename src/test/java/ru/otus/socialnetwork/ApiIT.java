package ru.otus.socialnetwork;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import ru.otus.socialnetwork.config.AuthProperties;
import ru.otus.socialnetwork.service.FeedCache;
import ru.otus.socialnetwork.service.FeedEventProcessor;
import ru.otus.socialnetwork.service.SessionService;
import ru.otus.socialnetwork.service.TokenHashes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ApiIT {
    private static final String PASSWORD = "ExamplePassword123!";
    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired SessionService sessions;
    @Autowired AuthProperties properties;
    @Autowired SpringLiquibase liquibase;
    @Autowired StringRedisTemplate redis;
    @Autowired FeedCache feedCache;
    @Autowired FeedEventProcessor feedEventProcessor;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void clearDedicatedTestDatabase() {
        jdbc.execute("TRUNCATE user_sessions, user_credentials, users, feed_events RESTART IDENTITY CASCADE");
    }

    @Test
    void registrationAndPublicProfileMatchContract() throws Exception {
        var registration = post("/user/register", registration());
        assertThat(registration.statusCode()).isEqualTo(200);
        String id = json(registration).get("user_id").asString();
        assertThat(id).matches("[1-9][0-9]*");
        assertThat(json(registration).has("token")).isFalse();
        var response = get("/user/get/" + id);
        assertThat(response.statusCode()).isEqualTo(200);
        var profile = json(response);
        assertThat(profile.get("id").asString()).isEqualTo(id);
        assertThat(profile.get("first_name").asString()).isEqualTo("Иван");
        assertThat(profile.get("second_name").asString()).isEqualTo("Иванов");
        assertThat(profile.get("birthdate").asString()).isEqualTo("1995-05-20");
        assertThat(profile.get("gender").asString()).isEqualTo("MALE");
        assertThat(profile.get("biography").asString()).isEqualTo("Java, музыка");
        assertThat(profile.get("city").asString()).isEqualTo("Москва");
        assertThat(profile.size()).isEqualTo(7);
        String hash = jdbc.queryForObject("SELECT password_hash FROM user_credentials WHERE user_id = ?",
                String.class, Integer.parseInt(id));
        assertThat(hash).startsWith("$argon2id$").doesNotContain(PASSWORD);
        assertThat(encoder.matches(PASSWORD, hash)).isTrue();
    }

    @Test
    void optionalFieldsDefaultAndEmptyStringsBecomeNull() throws Exception {
        var input = registration();
        input.remove("gender");
        input.put("city", "  ");
        input.remove("biography");
        input.put("first_name", "  Иван  ");
        String id = register(input);
        var profile = json(get("/user/get/" + id));
        assertThat(profile.get("first_name").asString()).isEqualTo("Иван");
        assertThat(profile.get("gender").asString()).isEqualTo("UNSPECIFIED");
        assertThat(profile.get("biography").isNull()).isTrue();
        assertThat(profile.get("city").isNull()).isTrue();
        input.put("gender", null);
        assertThat(json(get("/user/get/" + register(input))).get("gender").asString()).isEqualTo("UNSPECIFIED");
    }

    @Test
    void loginCreatesUuidAndStoresOnlyHashWithConfiguredTtl() throws Exception {
        String id = register(registration());
        var response = post("/login", Map.of("id", id, "password", PASSWORD));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        String token = json(response).get("token").asString();
        assertThat(UUID.fromString(token).version()).isEqualTo(4);
        String hash = TokenHashes.sha256(token);
        var row = jdbc.queryForMap("SELECT * FROM user_sessions WHERE token_hash = ?", hash);
        assertThat(row.get("token_hash")).isEqualTo(hash).isNotEqualTo(token);
        assertThat(row.keySet()).containsExactlyInAnyOrder("token_hash", "user_id", "created_at", "expires_at");
        Instant created = ((Timestamp) row.get("created_at")).toInstant();
        Instant expires = ((Timestamp) row.get("expires_at")).toInstant();
        assertThat(Duration.between(created, expires)).isEqualTo(properties.tokenTtl());
        assertThat(sessions.resolveUserId(token)).contains(Integer.parseInt(id));
        String secondToken = json(post("/login", Map.of("id", id, "password", PASSWORD))).get("token").asString();
        assertThat(secondToken).isNotEqualTo(token);
        assertThat(sessions.resolveUserId(token)).contains(Integer.parseInt(id));
    }

    @Test
    void allFiveWriteEndpointsRequireBearerAuthentication() throws Exception {
        String postId = UUID.randomUUID().toString();
        assertUnauthorized(request("PUT", "/friend/set/2", null, null));
        assertUnauthorized(request("PUT", "/friend/delete/2", null, null));
        assertUnauthorized(post("/post/create", Map.of("text", "Привет")));
        assertUnauthorized(request("PUT", "/post/update", Map.of("id", postId, "text", "Привет"), null));
        assertUnauthorized(request("PUT", "/post/delete/" + postId, null, null));

        String owner = register(registration());
        String token = login(owner);
        var body = Map.of("text", "Новый пост");
        for (String authorization : List.of("Basic " + token, "Bearer", "Bearer invalid",
                "Bearer " + UUID.randomUUID(), "Bearer " + token + ", Bearer " + token)) {
            assertUnauthorized(request("POST", "/post/create", body, authorization));
        }

        Instant now = Instant.now();
        jdbc.update("UPDATE user_sessions SET created_at = ?, expires_at = ? WHERE token_hash = ?",
                Timestamp.from(now.minusSeconds(7200)), Timestamp.from(now.minusSeconds(3600)),
                TokenHashes.sha256(token));
        assertUnauthorized(request("POST", "/post/create", body, "Bearer " + token));

        String activeToken = login(owner);
        String authorization = "Bearer " + activeToken;
        assertThat(request("POST", "/post/create", body, authorization).statusCode()).isEqualTo(200);
        assertThat(jdbc.update("DELETE FROM user_sessions WHERE token_hash = ?", TokenHashes.sha256(activeToken)))
                .isEqualTo(1);
        assertUnauthorized(request("POST", "/post/create", body, authorization));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM posts", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM friendships", Integer.class)).isZero();
    }

    @Test
    void feedRequiresBearerAndValidPagination() throws Exception {
        assertUnauthorized(get("/post/feed"));
        assertUnauthorized(request("GET", "/post/feed", null, "Bearer " + UUID.randomUUID()));

        String authorization = "Bearer " + login(register(registration()));
        for (String query : List.of("offset=-1", "offset=abc", "offset=", "offset=2147483648",
                "limit=0", "limit=-1", "limit=1.5", "limit=2147483648")) {
            var response = request("GET", "/post/feed?" + query, null, authorization);
            assertThat(response.statusCode()).as(query).isEqualTo(400);
            assertThat(json(response).get("errors").get(0).get("field").asString())
                    .isEqualTo(query.substring(0, query.indexOf('=')));
        }
    }

    @Test
    void feedRestoresFromPostgresAndServesReadyPagesFromRedis() throws Exception {
        String reader = register(registration());
        String firstFriend = register(registration());
        String secondFriend = register(registration());
        String other = register(registration());
        String authorization = "Bearer " + login(reader);
        follow(reader, firstFriend);
        follow(reader, secondFriend);
        follow(other, reader);
        insertFeedPost(1, firstFriend, "Старый", "2025-10-01T10:00:00Z");
        insertFeedPost(2, firstFriend, "Первый при равной дате", "2025-10-01T11:00:00Z");
        insertFeedPost(3, secondFriend, "Второй при равной дате", "2025-10-01T11:00:00Z");
        insertFeedPost(4, firstFriend, "Новый", "2025-10-01T12:00:00Z");
        insertFeedPost(5, reader, "Свой", "2025-10-01T13:00:00Z");
        insertFeedPost(6, other, "Чужой", "2025-10-01T14:00:00Z");

        clearFeed(reader);
        try {
            var middle = feed("/post/feed?offset=1&limit=1", authorization);
            assertThat(middle.size()).isEqualTo(1);
            assertThat(middle.get(0).get("id").asString()).isEqualTo(new UUID(0, 3).toString());
            assertThat(middle.get(0).get("author_user_id").asString()).isEqualTo(secondFriend);
            assertThat(middle.get(0).size()).isEqualTo(3);
            assertThat(redis.opsForZSet().size(feedKey(reader, "order"))).isEqualTo(4);

            jdbc.update("UPDATE posts SET text = 'Обновлён из SQL' WHERE id = ?", new UUID(0, 4));
            var ready = feed("/post/feed", authorization);
            assertThat(ready.size()).isEqualTo(4);
            assertThat(ready.get(0).get("text").asString()).isEqualTo("Новый");
            assertThat(ready.get(1).get("id").asString()).isEqualTo(new UUID(0, 3).toString());
            assertThat(ready.get(2).get("id").asString()).isEqualTo(new UUID(0, 2).toString());
            assertThat(ready.get(3).get("id").asString()).isEqualTo(new UUID(0, 1).toString());

            redis.delete(feedKey(reader, "order"));
            assertThat(feed("/post/feed?limit=1", authorization).get(0).get("text").asString())
                    .isEqualTo("Обновлён из SQL");

            clearFeed(reader);
            assertThat(feed("/post/feed?offset=4&limit=1", authorization).isEmpty()).isTrue();
            assertThat(feed("/post/feed?limit=1", authorization).get(0).get("text").asString())
                    .isEqualTo("Обновлён из SQL");
        } finally {
            clearFeed(reader);
        }
    }

    @Test
    void feedKeepsOnlyLatestThousandPostsAndReturnsLastPage() throws Exception {
        String reader = register(registration());
        String friend = register(registration());
        String authorization = "Bearer " + login(reader);
        follow(reader, friend);
        jdbc.update("""
                INSERT INTO posts (author_user_id, text, created_at)
                SELECT ?, 'Пост ' || number, TIMESTAMPTZ '2025-01-01 00:00:00+00' + number * INTERVAL '1 second'
                FROM generate_series(1, 1005) AS number
                """, Integer.parseInt(friend));

        clearFeed(reader);
        try {
            var first = feed("/post/feed", authorization);
            assertThat(first.size()).isEqualTo(10);
            assertThat(first.get(0).get("text").asString()).isEqualTo("Пост 1005");
            assertThat(redis.opsForZSet().size(feedKey(reader, "order"))).isEqualTo(1_000);
            assertThat(redis.opsForHash().size(feedKey(reader, "posts"))).isEqualTo(1_001);
            var last = feed("/post/feed?offset=998&limit=2147483647", authorization);
            assertThat(last.size()).isEqualTo(2);
            assertThat(last.get(0).get("text").asString()).isEqualTo("Пост 7");
            assertThat(last.get(1).get("text").asString()).isEqualTo("Пост 6");
            assertThat(feed("/post/feed?offset=1000", authorization).isEmpty()).isTrue();
        } finally {
            clearFeed(reader);
        }
    }

    @Test
    void outboxUpdatesCachedEmptyFeedAndRemovingFriendInvalidatesIt() throws Exception {
        String reader = register(registration());
        String friend = register(registration());
        String authorization = "Bearer " + login(reader);
        String friendAuthorization = "Bearer " + login(friend);
        follow(reader, friend);

        clearFeed(reader);
        try {
            assertThat(feed("/post/feed", authorization).isEmpty()).isTrue();
            assertThat(redis.opsForHash().hasKey(feedKey(reader, "posts"), "ready")).isTrue();

            var created = request("POST", "/post/create", Map.of("text", "Новая запись"), friendAuthorization);
            assertThat(created.statusCode()).isEqualTo(200);
            assertThat(feed("/post/feed", authorization).isEmpty()).isTrue();
            feedEventProcessor.processPendingEvents();
            assertThat(feed("/post/feed", authorization).get(0).get("id").asString())
                    .isEqualTo(json(created).asString());

            assertThat(request("PUT", "/friend/delete/" + friend, null, authorization).statusCode()).isEqualTo(200);
            feedEventProcessor.processPendingEvents();
            assertThat(redis.hasKey(feedKey(reader, "posts"))).isFalse();
            assertThat(feed("/post/feed", authorization).isEmpty()).isTrue();
        } finally {
            clearFeed(reader);
        }
    }

    @Test
    void postCreationPreventsAnInFlightRebuildFromPublishingStaleCache() throws Exception {
        String reader = register(registration());
        String friend = register(registration());
        String authorization = "Bearer " + login(reader);
        String friendAuthorization = "Bearer " + login(friend);
        follow(reader, friend);

        clearFeed(reader);
        try {
            int readerId = Integer.parseInt(reader);
            var rebuild = feedCache.readOrStartRebuild(readerId, 0, 10);
            assertThat(rebuild.isHit()).isFalse();
            var created = request("POST", "/post/create", Map.of("text", "После начала пересборки"), friendAuthorization);
            assertThat(created.statusCode()).isEqualTo(200);
            feedEventProcessor.processPendingEvents();

            feedCache.storeRebuiltFeed(readerId, rebuild.rebuildToken(), List.of());
            assertThat(redis.hasKey(feedKey(reader, "posts"))).isFalse();
            assertThat(feed("/post/feed", authorization).get(0).get("id").asString())
                    .isEqualTo(json(created).asString());
        } finally {
            clearFeed(reader);
        }
    }

    @Test
    void friendshipWritesAreDirectedAndIdempotent() throws Exception {
        String userId = register(registration());
        String friendId = register(registration());
        String authorization = "bEaReR  " + login(userId);
        String reverseAuthorization = "Bearer " + login(friendId);

        String add = "/friend/set/" + friendId;
        assertThat(request("PUT", add, null, authorization).statusCode()).isEqualTo(200);
        assertThat(request("PUT", add, null, authorization).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForList("SELECT user_id || ':' || friend_id FROM friendships", String.class))
                .containsExactly(userId + ":" + friendId);
        assertThat(feedEvents()).containsExactly(new FeedEvent("FRIENDSHIPS_CHANGED", Integer.parseInt(userId), null));

        assertThat(request("PUT", "/friend/set/" + userId, null, reverseAuthorization).statusCode()).isEqualTo(200);
        String delete = "/friend/delete/" + friendId;
        assertThat(request("PUT", delete, null, authorization).statusCode()).isEqualTo(200);
        assertThat(request("PUT", delete, null, authorization).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForList("SELECT user_id || ':' || friend_id FROM friendships", String.class))
                .containsExactly(friendId + ":" + userId);
        assertThat(feedEvents()).containsExactly(
                new FeedEvent("FRIENDSHIPS_CHANGED", Integer.parseInt(userId), null),
                new FeedEvent("FRIENDSHIPS_CHANGED", Integer.parseInt(friendId), null),
                new FeedEvent("FRIENDSHIPS_CHANGED", Integer.parseInt(userId), null));
    }

    @Test
    void friendEndpointsRejectInvalidIdsAndAdditionRequiresAnotherExistingUser() throws Exception {
        String userId = register(registration());
        String authorization = "Bearer " + login(userId);
        for (String path : List.of("/friend/set/001", "/friend/delete/2147483648", "/friend/set/" + userId)) {
            var response = request("PUT", path, null, authorization);
            assertThat(response.statusCode()).as(path).isEqualTo(400);
            assertThat(json(response).get("errors").get(0).get("field").asString()).isEqualTo("user_id");
        }
        assertThat(request("PUT", "/friend/set/2147483647", null, authorization).statusCode()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM friendships", Integer.class)).isZero();
        assertThat(feedEvents()).isEmpty();
    }

    @Test
    void authorCanCreateEditAndDeleteTheirPost() throws Exception {
        String userId = register(registration());
        String authorization = "Bearer " + login(userId);
        String original = "  Публикация с переводом строки\nи апострофом '  ";
        var created = request("POST", "/post/create", Map.of("text", original), authorization);
        assertThat(created.statusCode()).withFailMessage(created.body()).isEqualTo(200);
        assertThat(json(created).isString()).isTrue();
        UUID id = UUID.fromString(json(created).asString());
        assertThat(id.version()).isEqualTo(4);

        var before = jdbc.queryForMap("SELECT author_user_id, text, created_at FROM posts WHERE id = ?", id);
        assertThat(before.get("author_user_id")).isEqualTo(Integer.parseInt(userId));
        assertThat(before.get("text")).isEqualTo(original);
        assertThat(request("PUT", "/post/update", Map.of("id", id.toString(), "text", "Новый текст"),
                authorization).statusCode()).isEqualTo(200);
        var after = jdbc.queryForMap("SELECT author_user_id, text, created_at FROM posts WHERE id = ?", id);
        assertThat(after).containsEntry("author_user_id", before.get("author_user_id"))
                .containsEntry("created_at", before.get("created_at"))
                .containsEntry("text", "Новый текст");
        assertThat(request("PUT", "/post/delete/" + id, null, authorization).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM posts", Integer.class)).isZero();
        int authorId = Integer.parseInt(userId);
        assertThat(feedEvents()).containsExactly(
                new FeedEvent("POST_CREATED", authorId, id),
                new FeedEvent("POST_UPDATED", authorId, id),
                new FeedEvent("POST_DELETED", authorId, id));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM feed_events WHERE processed_at IS NOT NULL", Integer.class))
                .isZero();
    }

    @Test
    void otherUsersAndUnknownIdsCannotUpdateOrDeletePosts() throws Exception {
        String userId = register(registration());
        String author = "Bearer " + login(userId);
        String other = "Bearer " + login(register(registration()));
        var created = request("POST", "/post/create", Map.of("text", "Исходный текст"), author);
        assertThat(created.statusCode()).withFailMessage(created.body()).isEqualTo(200);
        String id = json(created).asString();
        String unknownId = UUID.randomUUID().toString();

        assertThat(request("PUT", "/post/update", Map.of("id", id, "text", "Чужая правка"),
                other).statusCode()).isEqualTo(404);
        assertThat(request("PUT", "/post/delete/" + id, null, other).statusCode()).isEqualTo(404);
        assertThat(request("PUT", "/post/update", Map.of("id", unknownId, "text", "Текст"),
                author).statusCode()).isEqualTo(404);
        assertThat(request("PUT", "/post/delete/" + unknownId, null, author).statusCode()).isEqualTo(404);
        assertThat(jdbc.queryForList("SELECT text FROM posts", String.class)).containsExactly("Исходный текст");
        assertThat(feedEvents()).containsExactly(
                new FeedEvent("POST_CREATED", Integer.parseInt(userId), UUID.fromString(id)));
    }

    @Test
    void postAndFriendshipWritesRollBackWhenFeedEventInsertFails() throws Exception {
        String userId = register(registration());
        String existingFriendId = register(registration());
        String newFriendId = register(registration());
        String authorization = "Bearer " + login(userId);
        var initialPost = request("POST", "/post/create", Map.of("text", "Исходный текст"), authorization);
        assertThat(initialPost.statusCode()).isEqualTo(200);
        String postId = json(initialPost).asString();
        assertThat(request("PUT", "/friend/set/" + existingFriendId, null, authorization).statusCode())
                .isEqualTo(200);

        jdbc.execute("ALTER TABLE feed_events ADD CONSTRAINT test_reject_feed_event CHECK (false) NOT VALID");
        try {
            assertThat(request("POST", "/post/create", Map.of("text", "Другой пост"), authorization).statusCode())
                    .isEqualTo(500);
            assertThat(request("PUT", "/post/update", Map.of("id", postId, "text", "Правка"),
                    authorization).statusCode()).isEqualTo(500);
            assertThat(request("PUT", "/post/delete/" + postId, null, authorization).statusCode()).isEqualTo(500);
            assertThat(request("PUT", "/friend/set/" + newFriendId, null, authorization).statusCode())
                    .isEqualTo(500);
            assertThat(request("PUT", "/friend/delete/" + existingFriendId, null, authorization).statusCode())
                    .isEqualTo(500);

            assertThat(jdbc.queryForList("SELECT text FROM posts", String.class)).containsExactly("Исходный текст");
            assertThat(jdbc.queryForList("SELECT user_id || ':' || friend_id FROM friendships", String.class))
                    .containsExactly(userId + ":" + existingFriendId);
            assertThat(feedEvents()).containsExactly(
                    new FeedEvent("POST_CREATED", Integer.parseInt(userId), UUID.fromString(postId)),
                    new FeedEvent("FRIENDSHIPS_CHANGED", Integer.parseInt(userId), null));
        } finally {
            jdbc.execute("ALTER TABLE feed_events DROP CONSTRAINT test_reject_feed_event");
        }
    }

    @Test
    void postEndpointsRejectInvalidTextAndIds() throws Exception {
        String authorization = "Bearer " + login(register(registration()));
        for (var body : List.of(Map.of(), Map.of("text", " \t\n\u00a0"), Map.of("text", "a\0b"),
                Map.of("text", 123), Map.of("text", "Текст", "author_user_id", "2"))) {
            assertThat(request("POST", "/post/create", body, authorization).statusCode()).isEqualTo(400);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM posts", Integer.class)).isZero();
        var created = request("POST", "/post/create", Map.of("text", "Исходный текст"), authorization);
        assertThat(created.statusCode()).withFailMessage(created.body()).isEqualTo(200);
        String id = json(created).asString();
        for (var body : List.of(Map.of("text", "Текст"), Map.of("id", "1-1-1-1-1", "text", "Текст"),
                Map.of("id", id), Map.of("id", id, "text", " \t"))) {
            assertThat(request("PUT", "/post/update", body, authorization).statusCode()).isEqualTo(400);
        }
        assertThat(request("PUT", "/post/delete/1-1-1-1-1", null, authorization).statusCode()).isEqualTo(400);
        assertThat(jdbc.queryForList("SELECT text FROM posts", String.class)).containsExactly("Исходный текст");
    }

    @Test
    void validationReturns400WithoutCreatingUsers() throws Exception {
        for (var change : List.of(
                Map.entry("first_name", " "), Map.entry("second_name", " "),
                Map.entry("first_name", "A".repeat(101)), Map.entry("city", "A".repeat(101)),
                Map.entry("biography", "A".repeat(2001)), Map.entry("gender", "UNKNOWN"),
                Map.entry("birthdate", "1995-02-30"), Map.entry("birthdate", LocalDate.now().plusDays(1).toString()),
                Map.entry("password", "Short123!"), Map.entry("password", "A".repeat(41)),
                Map.entry("password", "Password with space"), Map.entry("password", "Пароль12345678"))) {
            var input = registration();
            input.put(change.getKey(), change.getValue());
            var response = post("/user/register", input);
            assertThat(response.statusCode()).as(change.getKey()).isEqualTo(400);
            assertThat(json(response).has("message")).isTrue();
            assertThat(json(response).get("request_id").asString())
                    .isEqualTo(response.headers().firstValue("X-Request-Id").orElseThrow());
        }
        var missing = registration();
        missing.remove("birthdate");
        assertThat(post("/user/register", missing).statusCode()).isEqualTo(400);
        missing = registration();
        missing.remove("password");
        assertThat(post("/user/register", missing).statusCode()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isZero();
    }

    @Test
    void wrongJsonTypesAndUnknownFieldsReturn400() throws Exception {
        for (var change : List.of(
                Map.entry("first_name", 123), Map.entry("password", 123456789012L),
                Map.entry("gender", 1), Map.entry("birthdate", 123))) {
            var input = registration();
            input.put(change.getKey(), change.getValue());
            assertThat(post("/user/register", input).statusCode()).as(change.getKey()).isEqualTo(400);
        }
        var input = registration();
        input.put("unexpected_field", "value");
        assertThat(post("/user/register", input).statusCode()).isEqualTo(400);
    }

    @Test
    void passwordLengthBoundariesWorkThroughHttp() throws Exception {
        for (int length : new int[] {12, 40}) {
            var input = registration();
            input.put("password", "A".repeat(length));
            String id = register(input);
            assertThat(post("/login", Map.of("id", id, "password", "A".repeat(length))).statusCode()).isEqualTo(200);
        }
    }

    @Test
    void failuresReturn400Or404AndDoNotCreateSessions() throws Exception {
        String id = register(registration());
        assertThat(post("/login", Map.of("id", id, "password", "WrongPassword123!")).statusCode()).isEqualTo(400);
        assertThat(post("/login", Map.of("id", "2147483647", "password", PASSWORD)).statusCode()).isEqualTo(404);
        assertThat(get("/user/get/2147483647").statusCode()).isEqualTo(404);
        for (String bad : List.of("0", "001", "2147483648", "abc")) {
            assertThat(get("/user/get/" + bad).statusCode()).isEqualTo(400);
            assertThat(post("/login", Map.of("id", bad, "password", PASSWORD)).statusCode()).isEqualTo(400);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_sessions", Integer.class)).isZero();
    }

    @Test
    void sqlInjectionValuesRemainData() throws Exception {
        String name = "Robert'); DROP TABLE users;--";
        String password = "abcd';--1234";
        var input = registration();
        input.put("first_name", name);
        input.put("password", password);
        String id = register(input);
        assertThat(json(get("/user/get/" + id)).get("first_name").asString()).isEqualTo(name);
        assertThat(post("/login", Map.of("id", id, "password", password)).statusCode()).isEqualTo(200);
        assertThat(post("/login", Map.of("id", "1 OR 1=1", "password", password)).statusCode()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(1);
    }

    @Test
    void searchMatchesBothPrefixesAndReturnsFullProfilesInNumericIdOrder() throws Exception {
        insertProfile(20, "Иван", "Петров");
        insertProfile(3, "Иванна", "Петренко");
        insertProfile(11, "Иван", "Петрова");
        insertProfile(5, "Пётр", "Петров");
        insertProfile(6, "Иван", "Сидоров");
        insertProfile(7, "Диван", "Петров");
        insertProfile(8, "Иван", "Супетров");

        var response = search("  Ив  ", "  Пет  ");
        assertThat(response.statusCode()).isEqualTo(200);
        var profiles = json(response);
        assertThat(profiles.isArray()).isTrue();
        assertThat(profiles.size()).isEqualTo(3);
        assertThat(profiles.get(0).get("id").asString()).isEqualTo("3");
        assertThat(profiles.get(1).get("id").asString()).isEqualTo("11");
        assertThat(profiles.get(2).get("id").asString()).isEqualTo("20");
        assertThat(profiles.get(0)).isEqualTo(json(get("/user/get/3")));
        assertThat(search("ив", "Пет").body()).isEqualTo("[]");
        assertThat(search("НетТакогоИмени", "Пет").body()).isEqualTo("[]");
        assertThat(search("Ив", "НетТакойФамилии").body()).isEqualTo("[]");
    }

    @Test
    void searchRejectsMissingBlankAndTooLongPrefixes() throws Exception {
        for (String path : List.of("/user/search", "/user/search?first_name=Ivan",
                "/user/search?second_name=Petrov", "/user/search?first_name=Ivan&last_name=Petrov")) {
            assertThat(get(path).statusCode()).as(path).isEqualTo(400);
        }
        for (String value : List.of("", " \t ", "A".repeat(101))) {
            var firstNameResponse = search(value, "Пет");
            assertThat(firstNameResponse.statusCode()).isEqualTo(400);
            assertThat(json(firstNameResponse).get("errors").get(0).get("field").asString())
                    .isEqualTo("first_name");
            var secondNameResponse = search("Ив", value);
            assertThat(secondNameResponse.statusCode()).isEqualTo(400);
            assertThat(json(secondNameResponse).get("errors").get(0).get("field").asString())
                    .isEqualTo("second_name");
        }
        assertThat(search("A".repeat(100), "B".repeat(100)).statusCode()).isEqualTo(200);
    }

    @Test
    void searchTreatsLikeWildcardsEscapeCharactersAndSqlAsLiteralText() throws Exception {
        int id = 1;
        for (String prefix : List.of("%", "_", "!", "\\", "' OR 1=1 --")) {
            insertProfile(id, prefix + "Имя", prefix + "Фамилия");
            insertProfile(id + 1, "ДругоеИмя", "ДругаяФамилия");
            var firstNameResponse = search(prefix, prefix + "Фамилия");
            assertThat(firstNameResponse.statusCode()).isEqualTo(200);
            assertThat(json(firstNameResponse).size()).isEqualTo(1);
            assertThat(json(firstNameResponse).get(0).get("id").asString()).isEqualTo(Integer.toString(id));
            var secondNameResponse = search(prefix + "Имя", prefix);
            assertThat(secondNameResponse.statusCode()).isEqualTo(200);
            assertThat(json(secondNameResponse).size()).isEqualTo(1);
            assertThat(json(secondNameResponse).get(0).get("id").asString()).isEqualTo(Integer.toString(id));
            id += 2;
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(10);
    }

    @Test
    void registrationRollsBackWhenCredentialsInsertFails() throws Exception {
        jdbc.execute("ALTER TABLE user_credentials ADD CONSTRAINT test_reject_credentials CHECK (false) NOT VALID");
        try {
            var response = post("/user/register", registration());
            assertThat(response.statusCode()).isEqualTo(500);
            assertThat(json(response).get("message").asString()).isEqualTo("Внутренняя ошибка сервера");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM user_credentials", Integer.class)).isZero();
        } finally {
            jdbc.execute("ALTER TABLE user_credentials DROP CONSTRAINT test_reject_credentials");
        }
    }

    @Test
    void schemaHasSearchIndexAndMigrationIsRepeatable() throws Exception {
        var indexes = jdbc.queryForList("""
                SELECT indexname FROM pg_indexes WHERE schemaname = 'public'
                AND tablename IN ('users', 'user_credentials', 'user_sessions') ORDER BY indexname
                """, String.class);
        assertThat(indexes).containsExactly("user_credentials_pkey", "user_sessions_pkey",
                "users_pkey", "users_surname_name_prefix_idx");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM databasechangelog", Integer.class)).isEqualTo(9);
        liquibase.afterPropertiesSet();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM databasechangelog", Integer.class)).isEqualTo(9);
    }

    private List<FeedEvent> feedEvents() {
        return jdbc.query("SELECT event_type, user_id, post_id FROM feed_events ORDER BY id",
                (row, number) -> new FeedEvent(row.getString("event_type"), row.getInt("user_id"),
                        row.getObject("post_id", UUID.class)));
    }

    private record FeedEvent(String type, int userId, UUID postId) {}

    private HashMap<String, Object> registration() {
        var data = new HashMap<String, Object>();
        data.put("first_name", "Иван"); data.put("second_name", "Иванов");
        data.put("birthdate", "1995-05-20"); data.put("gender", "MALE");
        data.put("biography", "Java, музыка"); data.put("city", "Москва"); data.put("password", PASSWORD);
        return data;
    }

    private String register(Map<String, Object> data) throws Exception {
        var response = post("/user/register", data);
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
        return json(response).get("user_id").asString();
    }

    private String login(String userId) throws Exception {
        var response = post("/login", Map.of("id", userId, "password", PASSWORD));
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
        return json(response).get("token").asString();
    }

    private void assertUnauthorized(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate")).contains("Bearer");
        assertThat(json(response).get("request_id").asString())
                .isEqualTo(response.headers().firstValue("X-Request-Id").orElseThrow());
    }

    private void insertProfile(int id, String firstName, String secondName) {
        jdbc.update("""
                INSERT INTO users (id, first_name, second_name, birthdate)
                OVERRIDING SYSTEM VALUE VALUES (?, ?, ?, ?)
                """, id, firstName, secondName, java.sql.Date.valueOf("1995-05-20"));
    }

    private void follow(String reader, String friend) {
        jdbc.update("INSERT INTO friendships (user_id, friend_id) VALUES (?, ?)",
                Integer.parseInt(reader), Integer.parseInt(friend));
    }

    private void insertFeedPost(int id, String author, String text, String createdAt) {
        jdbc.update("INSERT INTO posts (id, author_user_id, text, created_at) VALUES (?, ?, ?, ?)",
                new UUID(0, id), Integer.parseInt(author), text, Timestamp.from(Instant.parse(createdAt)));
    }

    private JsonNode feed(String path, String authorization) throws Exception {
        var response = request("GET", path, null, authorization);
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
        JsonNode result = json(response);
        assertThat(result.isArray()).isTrue();
        return result;
    }

    private void clearFeed(String reader) {
        redis.delete(List.of(feedKey(reader, "order"), feedKey(reader, "posts")));
    }

    private static String feedKey(String reader, String suffix) {
        return "feed:{" + reader + "}:" + suffix;
    }

    private HttpResponse<String> search(String firstName, String secondName) throws Exception {
        return get("/user/search?first_name=" + URLEncoder.encode(firstName, StandardCharsets.UTF_8)
                + "&second_name=" + URLEncoder.encode(secondName, StandardCharsets.UTF_8));
    }

    private HttpResponse<String> post(String path, Object body) throws Exception {
        return request("POST", path, body, null);
    }

    private HttpResponse<String> request(String method, String path, Object body, String authorization) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10));
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        var publisher = HttpRequest.BodyPublishers.noBody();
        if (body != null) {
            request.header("Content-Type", "application/json");
            publisher = HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body));
        }
        return client.send(request.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> response) {
        return mapper.readTree(response.body());
    }
}
