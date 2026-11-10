package ru.otus.socialnetwork.load;

import java.time.Duration;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;

import io.gatling.javaapi.core.Simulation;

import static io.gatling.javaapi.core.CoreDsl.constantConcurrentUsers;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

public class UserGetSimulation extends Simulation {
    private static final int USERS = Integer.parseInt(System.getProperty("users", "1"));
    private static final int DURATION = Integer.parseInt(System.getProperty("duration", "60"));
    private static final int MAX_USER_ID = Integer.parseInt(System.getProperty("maxUserId", "1000000"));
    private static final String BASE_URL = System.getProperty("baseUrl", "http://localhost:8880");
    private static final long ID_SEED = 42L;

    public UserGetSimulation() {
        if (USERS < 1 || DURATION < 1 || MAX_USER_ID < 1) {
            throw new IllegalArgumentException("users, duration and maxUserId must be positive");
        }

        var protocol = http.baseUrl(BASE_URL)
                .acceptHeader("application/json")
                .shareConnections()
                .disableCaching()
                .disableWarmUp();

        var random = new Random(ID_SEED);
        var userIds = Stream.generate(() -> Map.<String, Object>of(
                "userId", Integer.toString(random.nextInt(MAX_USER_ID) + 1))).iterator();

        var getProfile = scenario("User get: " + USERS + " concurrent clients")
                .feed(userIds)
                .exec(http("GET /user/get/{id}")
                        .get("/user/get/#{userId}")
                        .check(status().is(200), jsonPath("$.id").isEL("#{userId}")));

        setUp(getProfile.injectClosed(constantConcurrentUsers(USERS).during(Duration.ofSeconds(DURATION))))
                .protocols(protocol);
    }
}
