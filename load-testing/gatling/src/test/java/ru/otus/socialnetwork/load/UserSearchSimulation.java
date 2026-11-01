package ru.otus.socialnetwork.load;

import java.time.Duration;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

public class UserSearchSimulation extends Simulation {
    private static final int USERS = Integer.parseInt(System.getProperty("users", "1"));
    private static final int DURATION = Integer.parseInt(System.getProperty("duration", "60"));
    private static final String BASE_URL = System.getProperty("baseUrl", "http://localhost:8880");
    private static final String FIRST_NAME = System.getProperty("firstName", "Ив");
    private static final String SECOND_NAME = System.getProperty("secondName", "Ив");

    public UserSearchSimulation() {
        if (USERS < 1 || DURATION < 1) {
            throw new IllegalArgumentException("users and duration must be positive");
        }

        HttpProtocolBuilder protocol = http.baseUrl(BASE_URL)
                .acceptHeader("application/json")
                .shareConnections()
                .disableCaching()
                .disableWarmUp();

        ScenarioBuilder search = scenario("User search: " + USERS + " concurrent clients")
                .exec(http("GET /user/search")
                        .get("/user/search")
                        .queryParam("first_name", FIRST_NAME)
                        .queryParam("second_name", SECOND_NAME)
                        .check(status().is(200), jsonPath("$").ofList().exists()));

        setUp(search.injectClosed(constantConcurrentUsers(USERS).during(Duration.ofSeconds(DURATION))))
                .protocols(protocol);
    }
}
