import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

final class WriteProbe {
    public static void main(String[] args) throws Exception {
        String url = args[0];
        Path output = Path.of(args[1]);
        int clients = Integer.parseInt(args[2]);
        Files.createDirectories(output);
        var connections = new ArrayList<Connection>(clients);

        try (var executor = Executors.newFixedThreadPool(clients)) {
            for (int i = 0; i < clients; i++) {
                var connection = DriverManager.getConnection(url, "postgres", "postgres");
                connections.add(connection);
                connection.setAutoCommit(false);
            }

            Files.createFile(output.resolve("ready"));
            long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
            var workers = new ArrayList<Future<?>>(clients);
            for (int i = 0; i < clients; i++) {
                Connection connection = connections.get(i);
                Path log = output.resolve("client-" + i + ".csv");
                workers.add(executor.submit(() -> {
                    write(connection, log, output.resolve("stop"), deadline);
                    return null;
                }));
            }
            for (var worker : workers) {
                worker.get();
            }
        } finally {
            for (var connection : connections) {
                connection.close();
            }
        }
    }

    private static void write(Connection connection, Path log, Path stop, long deadline)
            throws IOException, SQLException {
        try (var output = Files.newBufferedWriter(log);
             var insert = connection.prepareStatement("INSERT INTO replication_write_probe (id) VALUES (?)")) {
            output.write("id,outcome,finished_ms,latency_us,sqlstate\n");
            while (!Files.exists(stop) && System.nanoTime() < deadline) {
                var id = UUID.randomUUID();
                long started = System.nanoTime();
                try {
                    insert.setObject(1, id);
                    insert.executeUpdate();
                    connection.commit();
                } catch (SQLException error) {
                    output.write(id + ",unconfirmed," + System.currentTimeMillis() + ","
                            + TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - started) + ","
                            + error.getSQLState() + "\n");
                    break;
                }
                output.write(id + ",confirmed," + System.currentTimeMillis() + ","
                        + TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - started) + ",\n");
            }
        }
    }
}
