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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import ru.otus.socialnetwork.config.AuthProperties;
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
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void clearDedicatedTestDatabase() {
        jdbc.execute("TRUNCATE user_sessions, user_credentials, users RESTART IDENTITY CASCADE");
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
    void expiredDeletedAndUnknownTokensAreRejected() throws Exception {
        String id = register(registration());
        String token = json(post("/login", Map.of("id", id, "password", PASSWORD))).get("token").asString();
        Instant now = Instant.now();
        jdbc.update("UPDATE user_sessions SET created_at = ?, expires_at = ? WHERE token_hash = ?",
                Timestamp.from(now.minusSeconds(7200)), Timestamp.from(now.minusSeconds(3600)), TokenHashes.sha256(token));
        assertThat(sessions.resolveUserId(token)).isEmpty();
        jdbc.update("DELETE FROM user_sessions WHERE token_hash = ?", TokenHashes.sha256(token));
        assertThat(sessions.resolveUserId(token)).isEmpty();
        assertThat(sessions.resolveUserId(UUID.randomUUID().toString())).isEmpty();
        assertThat(sessions.resolveUserId("not-a-token")).isEmpty();
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
    void schemaHasOnlyPrimaryKeyIndexesAndMigrationIsRepeatable() throws Exception {
        var indexes = jdbc.queryForList("""
                SELECT indexname FROM pg_indexes WHERE schemaname = 'public'
                AND tablename IN ('users', 'user_credentials', 'user_sessions') ORDER BY indexname
                """, String.class);
        assertThat(indexes).containsExactly("user_credentials_pkey", "user_sessions_pkey", "users_pkey");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM databasechangelog", Integer.class)).isEqualTo(4);
        liquibase.afterPropertiesSet();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM databasechangelog", Integer.class)).isEqualTo(4);
    }

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

    private void insertProfile(int id, String firstName, String secondName) {
        jdbc.update("""
                INSERT INTO users (id, first_name, second_name, birthdate)
                OVERRIDING SYSTEM VALUE VALUES (?, ?, ?, ?)
                """, id, firstName, secondName, java.sql.Date.valueOf("1995-05-20"));
    }

    private HttpResponse<String> search(String firstName, String secondName) throws Exception {
        return get("/user/search?first_name=" + URLEncoder.encode(firstName, StandardCharsets.UTF_8)
                + "&second_name=" + URLEncoder.encode(secondName, StandardCharsets.UTF_8));
    }

    private HttpResponse<String> post(String path, Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> response) {
        return mapper.readTree(response.body());
    }
}
