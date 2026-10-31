package ru.otus.socialnetwork.load;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import io.gatling.javaapi.core.PopulationBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpRequestActionBuilder;
import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

public class UserSearchBenchmarkSimulation extends Simulation {
    private final int duration = Integer.parseInt(System.getProperty("duration", "120"));
    private final int repeats = Integer.parseInt(System.getProperty("repeats", "3"));
    private final int warmup = Integer.parseInt(System.getProperty("warmup", "30"));
    private final int cooldown = Integer.parseInt(System.getProperty("cooldown", "5"));
    private final String baseUrl = System.getProperty("baseUrl", "http://localhost:8880");
    private final String firstName = System.getProperty("firstName", "Ив");
    private final String secondName = System.getProperty("secondName", "Ив");
    private final String stage = System.getProperty("stage", "before-index");
    private final List<StageStats> measurements = new ArrayList<>();
    private String databaseInfo;
    private String applicationInfo;

    public UserSearchBenchmarkSimulation() {
        if (duration < 1 || repeats < 1 || warmup < 0 || cooldown < 0) {
            throw new IllegalArgumentException("duration/repeats must be positive; warmup/cooldown cannot be negative");
        }
        if (!List.of("before-index", "after-index").contains(stage)) {
            throw new IllegalArgumentException("stage must be before-index or after-index");
        }
        List<PopulationBuilder> phases = new ArrayList<>();
        if (warmup > 0) {
            phases.add(population(new StageStats(1, 0), warmup, 0));
        }
        for (int repeat = 1; repeat <= repeats; repeat++) {
            for (int users : new int[] {1, 10, 100, 1000}) {
                StageStats stats = new StageStats(users, repeat);
                measurements.add(stats);
                phases.add(population(stats, duration, cooldown));
            }
        }
        PopulationBuilder plan = phases.getLast();
        for (int i = phases.size() - 2; i >= 0; i--) {
            plan = phases.get(i).andThen(plan);
        }
        setUp(plan).protocols(http.baseUrl(baseUrl).acceptHeader("application/json")
                .shareConnections().disableCaching().disableWarmUp());
    }

    private PopulationBuilder population(StageStats stats, int seconds, int idle) {
        HttpRequestActionBuilder request = http(stats.name).get("/user/search")
                .queryParam("first_name", firstName).queryParam("second_name", secondName)
                .check(responseTimeInMillis().saveAs("responseMillis"), status().saveAs("httpStatus"),
                        status().is(200), jsonPath("$").ofList().exists());
        if (stats.repeat == 0) {
            request = request.silent();
        }
        var scenario = scenario(stats.name).exec(stats::begin).exec(request).exec(stats::end);
        if (idle == 0) {
            return scenario.injectClosed(constantConcurrentUsers(stats.users).during(Duration.ofSeconds(seconds)));
        }
        return scenario.injectClosed(constantConcurrentUsers(0).during(Duration.ofSeconds(idle)),
                constantConcurrentUsers(stats.users).during(Duration.ofSeconds(seconds)));
    }

    @Override
    public void before() {
        try {
            applicationInfo = checkApplicationRuntime();
            String query = "/user/search?first_name=" + URLEncoder.encode(firstName, StandardCharsets.UTF_8)
                    + "&second_name=" + URLEncoder.encode(secondName, StandardCharsets.UTF_8);
            try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
                var response = client.send(HttpRequest.newBuilder(URI.create(baseUrl + query))
                        .timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200 || !response.body().strip().startsWith("[")) {
                    throw new IllegalStateException("Search preflight failed: HTTP " + response.statusCode());
                }
            }
            Path compose = Path.of("..").toAbsolutePath().normalize().resolve("compose.yaml");
            String sql = "SELECT count(*), current_setting('server_version'), "
                    + "(SELECT string_agg(indexname, ', ' ORDER BY indexname) FROM pg_indexes "
                    + "WHERE schemaname='public' AND tablename='users') FROM users";
            databaseInfo = execute("docker", "compose", "-f", compose.toString(), "exec", "-T",
                    "db", "psql", "-X", "-U", System.getenv().getOrDefault("DB_USERNAME", "postgres"),
                    "-d", "social_network_load", "-At", "-F", "\t", "-c", sql);
            String[] fields = databaseInfo.split("\t", -1);
            if (fields.length != 3 || !fields[0].equals("1000000")) {
                throw new IllegalStateException("Expected 1000000 profiles in social_network_load; got " + databaseInfo);
            }
            if (stage.equals("before-index") && !fields[2].equals("users_pkey")) {
                throw new IllegalStateException("Before-index run requires only users_pkey; found " + fields[2]);
            }
            System.out.printf("BENCHMARK: %s, 1/10/100/1000 clients, %d repeats, %ds per level; DB: %s%n",
                    stage, repeats, duration, databaseInfo);
        } catch (Exception exception) {
            throw new IllegalStateException("Benchmark preflight failed. Start the app and Docker database first.", exception);
        }
    }

    @Override
    public void after() {
        BenchmarkReport.write(Path.of("target", "benchmark").toAbsolutePath(), measurements, stage, baseUrl, firstName, secondName,
                duration, repeats, warmup, cooldown, databaseInfo, applicationInfo);
    }

    private String checkApplicationRuntime() throws Exception {
        URI address = URI.create(baseUrl);
        if (!System.getProperty("os.name").startsWith("Windows")
                || !List.of("localhost", "127.0.0.1", "::1", "[::1]").contains(address.getHost())) {
            return "Режим JVM внешнего сервиса не проверялся";
        }
        int port = address.getPort() == -1 ? 80 : address.getPort();
        String script = "$ids=@((Get-NetTCPConnection -State Listen -LocalPort " + port
                + " -ErrorAction SilentlyContinue).OwningProcess); "
                + "Get-CimInstance Win32_Process | "
                + "Where-Object { $_.Name -eq 'java.exe' -and $ids -contains $_.ProcessId -and $_.CommandLine -match 'ru\\.otus\\.socialnetwork\\.SocialNetworkApplication' } | "
                + "ForEach-Object { '{0}|{1}|{2}' -f $_.ProcessId, "
                + "[int]($_.CommandLine -match 'agentlib:jdwp|Xrunjdwp'), "
                + "[int]($_.CommandLine -match 'TieredStopAtLevel=1') }";
        String result = execute("powershell.exe", "-NoProfile", "-Command", script);
        if (result.isBlank()) return "Локальная JVM приложения не определена (например, сервис работает в Docker)";
        for (String line : result.split("\\R")) {
            String[] fields = line.split("\\|");
            if (fields.length != 3) throw new IllegalStateException("Cannot inspect application JVM: " + result);
            if (fields[1].equals("1") || fields[2].equals("1")) {
                throw new IllegalStateException("Application uses Debug or -XX:TieredStopAtLevel=1. "
                        + "Restart it with Run; enable Disable launch optimization in its Spring Boot configuration.");
            }
        }
        return "Обычный Run; отладчик и TieredStopAtLevel=1 отсутствуют";
    }

    private static String execute(String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("Preflight command timed out: " + command[0]);
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        if (process.exitValue() != 0) throw new IllegalStateException("Preflight command failed: " + output);
        return output;
    }
}
