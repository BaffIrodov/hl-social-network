# Потоковая репликация PostgreSQL

Compose-проект `hl-social-network-replication` запускает PostgreSQL 17 на трёх узлах. `primary` принимает записи, `replica1` и `replica2` принимают чтение и получают WAL от `primary`. У каждого узла свой том. Внутри сети Compose узлы доступны по именам сервисов на порту 5432.

При создании primary скрипт добавляет роль `replicator` и разрешает ей подключения для репликации из подсети узла по SCRAM. При первом запуске каждой реплики `pg_basebackup --wal-method=stream -R` копирует кластер и создаёт `standby.signal` и параметры подключения к primary. В `application_name` записывается имя реплики. Пустой `synchronous_standby_names` задаёт асинхронный режим. [Документация PostgreSQL](https://www.postgresql.org/docs/17/warm-standby.html#STREAMING-REPLICATION).

## Первый запуск

Выполняйте команды PowerShell из корня проекта. Для копирования нужен запущенный одиночный контейнер `db` с базой `social_network_load` из базового замера. [Подготовка одиночной БД](../docs/load-data.md).

Поднимите primary и перенесите данные в его пустую базу. `pg_restore` выполняет восстановление одной транзакцией.

```powershell
$replicationCompose = 'load-testing/replication/compose.yaml'
$dumpPath = 'target/replication-stand/baseline.dump'
New-Item -ItemType Directory -Path 'target/replication-stand' -Force | Out-Null

docker compose -f $replicationCompose up -d --wait primary
docker compose -f compose.yaml exec -T db pg_dump -Fc -U postgres -d social_network_load -f /tmp/replication-baseline.dump
docker compose -f compose.yaml cp db:/tmp/replication-baseline.dump $dumpPath
docker compose -f $replicationCompose cp $dumpPath primary:/tmp/replication-baseline.dump
docker compose -f $replicationCompose exec -T primary pg_restore -U postgres -d social_network_load --no-owner --single-transaction --exit-on-error /tmp/replication-baseline.dump
docker compose -f $replicationCompose exec -T primary psql -U postgres -d social_network_load -v ON_ERROR_STOP=1 -c 'ANALYZE public.users'
```

Запустите обе реплики. Для последующих запусков стенда используется та же команда. Реплики продолжают работу с данными в своих томах.

```powershell
docker compose -f $replicationCompose up -d --wait primary replica1 replica2
```

## Проверка

На primary проверьте подключения обеих реплик.

```powershell
docker compose -f $replicationCompose exec -T primary psql -U postgres -d social_network_load -c 'SELECT application_name, state, sync_state FROM pg_stat_replication ORDER BY application_name'
```

| Узел | `state` | `sync_state` |
|---|---|---|
| `replica1` | `streaming` | `async` |
| `replica2` | `streaming` | `async` |

На каждом узле сверьте количество анкет и роль сервера.

```powershell
foreach ($node in @('primary', 'replica1', 'replica2')) {
    Write-Output $node
    docker compose -f $replicationCompose exec -T $node psql -U postgres -d social_network_load -c 'SELECT pg_is_in_recovery(), count(*) FROM public.users'
}
```

При проверке на всех трёх узлах было по 1 000 000 анкет. Контрольная сумма `1efc85f6988fd5eb24d2e22d70ec97eb` и индекс `(second_name varchar_pattern_ops, first_name varchar_pattern_ops)` совпали с базовым замером. Тестовая запись, сделанная на primary, появилась на обеих репликах. Прямой `INSERT` на каждой реплике вернул `SQLSTATE 25006`. [Базовые замеры](../reports/replication/baseline.md).

## Запуск приложения с репликами

Сервис `app` включает Spring-профиль `replication`. Для транзакций `readOnly = true` он по очереди берёт соединение из двух пулов реплик. Каждый пул допускает до 5 соединений. Запись и Liquibase используют пул `primary`.

Если порт 8880 занят приложением одиночного стенда, остановите его перед запуском приложения с репликами. На узлах, созданных до включения `pg_stat_statements`, создайте расширение на primary после запуска трёх серверов. Запись о расширении придёт на реплики через WAL.

```powershell
docker compose -f compose.yaml stop app
docker compose -f $replicationCompose exec -T primary psql -U postgres -d social_network_load -v ON_ERROR_STOP=1 -c 'CREATE EXTENSION IF NOT EXISTS pg_stat_statements'
docker compose -f $replicationCompose up -d --build --wait app
```

Gatling обращается к приложению по `http://localhost:8880`. Прогоны и проверка SQL-счётчиков описаны в отчёте. [Сравнение чтения](../reports/replication/replica-reads.md).

## Эксперимент с отказом primary

Запускайте из корня проекта в PowerShell 7 с `JAVA_HOME`, указывающим на JDK 21. Скрипт создаёт отдельный Compose-проект для каждого прогона. Четыре JDBC-клиента записывают UUID в тестовую таблицу, затем скрипт посылает `SIGKILL` контейнеру primary. После повышения реплики он подключает к ней второй узел и сверяет список подтверждённых UUID с таблицей нового primary.

```powershell
docker compose -f load-testing/replication/compose.yaml build app
.\mvnw.cmd -B -ntp dependency:copy-dependencies -DincludeArtifactIds=postgresql -DoutputDirectory=target/replication-driver
$driver = Get-ChildItem -LiteralPath 'target/replication-driver' -Filter 'postgresql-*.jar' -File | Select-Object -First 1
.\load-testing\replication\run-failover.ps1 -Mode async -JdbcJar $driver.FullName
.\load-testing\replication\run-failover.ps1 -Mode quorum -JdbcJar $driver.FullName
```

Режимы `async-gap` и `quorum-gap` проверяют один и тот же разрыв доставки WAL. Перед падением primary скрипт отключает получение WAL на обеих репликах и ждёт две секунды после подтверждения разрыва потоков. В `quorum-gap` он также проверяет, что все клиенты ждут подтверждения в `SyncRep`.

```powershell
.\load-testing\replication\run-failover.ps1 -Mode async-gap -JdbcJar $driver.FullName
.\load-testing\replication\run-failover.ps1 -Mode quorum-gap -JdbcJar $driver.FullName
```

Результаты каждого запуска сохраняются в `target/replication-failover` в отдельной папке. После сверки экспериментальные контейнеры останавливаются, их тома сохраняются. [Отчёт об отказах](../reports/replication/failover.md).
