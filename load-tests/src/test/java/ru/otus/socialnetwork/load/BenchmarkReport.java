package ru.otus.socialnetwork.load;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class BenchmarkReport {
    record Summary(int users, int repeat, long ok, long failed, double seconds, int peak, List<Long> latencies) {
        long total() { return ok + failed; }
        double rps() { return seconds == 0 ? 0 : total() / seconds; }
        double okRps() { return seconds == 0 ? 0 : ok / seconds; }
        double errorRate() { return total() == 0 ? 0 : 100.0 * failed / total(); }
        Double mean() { return latencies.isEmpty() ? null : latencies.stream().mapToLong(Long::longValue).average().orElseThrow(); }
        Long percentile(double fraction) {
            return latencies.isEmpty() ? null : latencies.get(Math.max(0, (int) Math.ceil(fraction * latencies.size()) - 1));
        }
    }

    static Summary summarize(List<StageStats> stages, int users, int repeat) {
        List<Long> latencies = new ArrayList<>();
        long ok = 0;
        long failed = 0;
        double seconds = 0;
        int peak = 0;
        for (StageStats stage : stages) {
            ok += stage.ok.sum();
            failed += stage.failed.sum();
            seconds += stage.seconds();
            peak = Math.max(peak, stage.peak.get());
            latencies.addAll(stage.latencies);
        }
        latencies.sort(Comparator.naturalOrder());
        return new Summary(users, repeat, ok, failed, seconds, peak, latencies);
    }

    static void write(Path root, List<StageStats> stages, String stage, String baseUrl, String firstName, String secondName,
                      int duration, int repeats, int warmup, int cooldown, String databaseInfo, String applicationInfo) {
        try {
            List<Summary> totals = new ArrayList<>();
            List<Summary> individual = new ArrayList<>();
            for (int users : new int[] {1, 10, 100, 1000}) {
                totals.add(summarize(stages.stream().filter(s -> s.users == users).toList(), users, 0));
            }
            for (StageStats current : stages) {
                individual.add(summarize(List.of(current), current.users, current.repeat));
            }
            StringBuilder totalRows = new StringBuilder();
            StringBuilder individualRows = new StringBuilder();
            totals.forEach(s -> totalRows.append(row(s, false)));
            individual.forEach(s -> individualRows.append(row(s, true)));
            StringBuilder errors = new StringBuilder();
            for (StageStats current : stages) {
                current.errors.forEach((reason, count) -> errors.append("<tr><td>")
                        .append(escape(current.name)).append("</td><td>").append(escape(reason))
                        .append("</td><td>").append(count.sum()).append("</td></tr>"));
            }
            if (errors.isEmpty()) errors.append("<tr><td colspan='3'>Ошибок не зарегистрировано</td></tr>");
            boolean incomplete = stages.stream().anyMatch(s -> s.total() == 0);
            long failed = totals.stream().mapToLong(Summary::failed).sum();
            String status = incomplete ? "Неполный запуск: некоторые замеры не содержат запросов."
                    : failed == 0 ? "Все уровни выполнены. Ошибок нет."
                    : "Все уровни выполнены. Ошибочные запросы отражены в таблицах и графиках.";
            String data = "[" + String.join(",", totals.stream().map(s ->
                    "{\"users\":" + s.users() + ",\"mean\":" + number(s.mean())
                    + ",\"p50\":" + number(s.percentile(0.50)) + ",\"p95\":" + number(s.percentile(0.95))
                    + ",\"p99\":" + number(s.percentile(0.99)) + ",\"rps\":" + number(s.rps())
                    + ",\"okRps\":" + number(s.okRps()) + ",\"errorRate\":" + number(s.errorRate()) + "}").toList()) + "]";
            String highcharts;
            try (var resource = BenchmarkReport.class.getResourceAsStream("/io/gatling/charts/assets/js/highstock.js")) {
                if (resource == null) throw new IOException("Gatling chart resources not found");
                highcharts = new String(resource.readAllBytes(), StandardCharsets.UTF_8).replace("</script", "<\\/script");
            }
            ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Europe/Moscow"));
            String html = TEMPLATE.formatted(escape(stage), escape(status),
                    now.format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss z")),
                    escape(baseUrl), escape(firstName), escape(secondName), duration, repeats, warmup, cooldown,
                    escape(databaseInfo), escape(applicationInfo), escape(System.getProperty("java.version")),
                    Runtime.getRuntime().availableProcessors(), Runtime.getRuntime().maxMemory() / 1024 / 1024,
                    totalRows, individualRows, errors, highcharts, data);
            Path archive = root.resolve(stage + "-" + now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")));
            Files.createDirectories(archive);
            Files.writeString(archive.resolve("index.html"), html, StandardCharsets.UTF_8);
            Files.writeString(root.resolve("latest.html"), html, StandardCharsets.UTF_8);
            StringBuilder csv = new StringBuilder("users,repeat,seconds,total,ok,failed,peak,mean_ms,p50_ms,p95_ms,p99_ms,total_rps,ok_rps,error_percent\n");
            for (Summary s : individual) {
                csv.append(s.users()).append(',').append(s.repeat()).append(',').append(number(s.seconds())).append(',')
                        .append(s.total()).append(',').append(s.ok()).append(',').append(s.failed()).append(',').append(s.peak()).append(',')
                        .append(number(s.mean())).append(',').append(number(s.percentile(.5))).append(',')
                        .append(number(s.percentile(.95))).append(',').append(number(s.percentile(.99))).append(',')
                        .append(number(s.rps())).append(',').append(number(s.okRps())).append(',').append(number(s.errorRate())).append('\n');
            }
            Files.writeString(archive.resolve("measurements.csv"), csv, StandardCharsets.UTF_8);
            System.out.println("BENCHMARK REPORT: " + root.resolve("latest.html"));
            System.out.println("BENCHMARK ARCHIVE: " + archive);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot write benchmark report", exception);
        }
    }

    private static String row(Summary s, boolean withRepeat) {
        return "<tr><td>" + s.users() + "</td>" + (withRepeat ? "<td>" + s.repeat() + "</td>" : "")
                + "<td>" + display(s.seconds()) + "</td><td>" + s.peak() + "</td><td>" + s.total()
                + "</td><td>" + s.ok() + "</td><td>" + s.failed() + "</td><td>" + display(s.mean())
                + "</td><td>" + display(s.percentile(.5)) + "</td><td>" + display(s.percentile(.95))
                + "</td><td>" + display(s.percentile(.99)) + "</td><td>" + display(s.rps())
                + "</td><td>" + display(s.okRps()) + "</td><td>" + display(s.errorRate()) + "</td></tr>";
    }

    private static String number(Number value) {
        return value == null ? "null" : String.format(Locale.ROOT, "%.3f", value.doubleValue());
    }

    private static String display(Number value) { return value == null ? "—" : number(value); }
    private static String escape(String value) {
        return String.valueOf(value).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static final String HEADER = "<th>Клиенты</th>REPEAT<th>Время, с</th><th>Пик клиентов</th><th>Всего</th><th>OK</th><th>Ошибки</th><th>Среднее, мс</th><th>p50, мс</th><th>p95, мс</th><th>p99, мс</th><th>Всего RPS</th><th>OK RPS</th><th>Ошибки, %%</th>";
    private static final String TEMPLATE = """
            <!doctype html><html lang="ru"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>Нагрузочный тест /user/search</title>
            <style>body{font:15px/1.6 'Segoe UI',Arial,sans-serif;background:#f2f5fa;color:#182640;margin:0}
            main{max-width:1500px;margin:32px auto;padding:0 24px}section{background:white;border-radius:12px;padding:24px;margin:20px 0}
            h1{font-size:30px;margin:0}h2{font-size:21px}p{margin:8px 0}.chart{height:380px}.scroll{overflow:auto}
            table{border-collapse:collapse;width:100%%;font-size:13px;white-space:nowrap}th,td{padding:9px;border-bottom:1px solid #dde4ef;text-align:right}
            th{background:#eef3fb}td:first-child,th:first-child{text-align:left}code{background:#eef3fb;padding:2px 5px}small{color:#54657d}</style>
            <main><h1>/user/search — %s</h1><p>%s</p><small>Сформирован %s</small>
            <section><h2>Условия</h2><p>Адрес: <code>%s</code>. Префиксы: <code>first_name=%s</code>, <code>second_name=%s</code>.</p>
            <p>Нагрузка: 1 / 10 / 100 / 1000 параллельных клиентов. %d с на уровень, %d повтора.
            Прогрев: %d с. Пауза перед каждым замером: %d с. Таймаут запроса: 30 с.</p>
            <p>Docker / social_network_load: число анкет, версия PostgreSQL, индексы users: <code>%s</code>.</p>
            <p>Приложение: %s.</p>
            <p>Нагрузочный JVM: Java %s; доступно процессоров: %d; максимальная heap: %d МБ.</p>
            <small>При localhost сервис и генератор нагрузки используют ресурсы одного компьютера. Результаты относятся к этому стенду.</small></section>
            <section><h2>Сводка по конкурентности</h2><div class="scroll"><table><thead><tr>
            """ + HEADER.replace("REPEAT", "") + """
            </tr></thead><tbody>%s</tbody></table></div>
            <p><small>Прогрев и паузы исключены. Время замера — от начала первого запроса до завершения последнего, включая ожидание оставшихся ответов.
            RPS = сумма запросов / сумма времён замеров; OK RPS учитывает только успешные ответы.
            Latency — только успешные запросы, по измерению Gatling. Перцентили посчитаны методом ближайшего ранга по объединённым данным повторов.</small></p></section>
            <section><div id="latency" class="chart"></div></section><section><div id="throughput" class="chart"></div></section>
            <section><div id="errors" class="chart"></div></section>
            <section><h2>Все замеры</h2><div class="scroll"><table><thead><tr>
            """ + HEADER.replace("REPEAT", "<th>Повтор</th>") + """
            </tr></thead><tbody>%s</tbody></table></div></section>
            <section><h2>Зарегистрированные ошибки</h2><table><tr><th>Замер</th><th>Причина</th><th>Количество</th></tr>%s</table></section>
            </main><script>%s</script><script>
            const rows=%s;
            function draw(id,title,unit,series){Highcharts.chart(id,{chart:{type:'line'},title:{text:title},
            xAxis:{categories:rows.map(r=>String(r.users)),title:{text:'Параллельные клиенты'}},yAxis:{min:0,title:{text:unit}},
            accessibility:{enabled:false},series:series.map(s=>({name:s[0],data:rows.map(r=>r[s[1]])}))});}
            draw('latency','Latency успешных запросов','мс',[['Среднее','mean'],['p50','p50'],['p95','p95'],['p99','p99']]);
            draw('throughput','Throughput','запросов / с',[['Все запросы','rps'],['Успешные запросы','okRps']]);
            draw('errors','Доля ошибок','%%',[['Ошибки','errorRate']]);
            </script></html>
            """;
}
