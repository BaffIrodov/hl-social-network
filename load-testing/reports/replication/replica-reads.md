# Чтение с двух асинхронных реплик

Замеры проведены 10 ноября 2026 года на PostgreSQL 17.11. На всех трёх узлах по 1 000 000 анкет и индекс `users_surname_name_prefix_idx` на `(second_name varchar_pattern_ops, first_name varchar_pattern_ops)`. Контрольная сумма анкет `1efc85f6988fd5eb24d2e22d70ec97eb` совпала с одиночным стендом. [Базовый замер](baseline.md).

## Маршрутизация

Spring-профиль `replication` использует `LazyConnectionDataSourceProxy`. При первом SQL-запросе в транзакции `readOnly = true` прокси выбирает одну из двух реплик по очереди. Для обычной транзакции выбирается `primary`. Liquibase получает пул `primary` напрямую через `@LiquibaseDataSource`. [Конфигурация источников данных](../../../src/main/java/ru/otus/socialnetwork/config/ReplicationDataSourceConfiguration.java).

Регистрация, логин и миграции проверены на временной базе того же кластера. По счётчикам SQL на `primary` выполнены запись анкеты, запись хэша пароля, чтение хэша при логине, запись сессии и запросы Liquibase. Обе реплики получили анкету, сессию и все шесть записей миграций через WAL.

## Сравнение замеров

Для каждого API выполнен прогрев одним клиентом в течение 30 секунд, затем один замер со 100 одновременными клиентами в течение 60 секунд. Сценарии, данные, параметры GET и хост взяты из базового замера. В одиночном стенде пул допускает 10 соединений. В новом стенде суммарный лимит чтения тоже 10, по 5 на каждую реплику. Оба замера выполнены на одном компьютере. Для подсчёта SQL на стенде с репликами включён `pg_stat_statements`.

| Запрос | Стенд | Успешных запросов | OK RPS | Средняя, мс | p95, мс | p99, мс | Ошибки |
|---|---|---:|---:|---:|---:|---:|---:|
| `GET /user/get/{id}` | Одна БД | 377 724 | 6 295,4 | 14 | 31 | 45 | 0 |
| `GET /user/get/{id}` | Две реплики | 434 264 | 7 237,73 | 13 | 30 | 46 | 0 |
| `GET /user/search` | Одна БД | 310 926 | 5 182,1 | 18 | 39 | 54 | 0 |
| `GET /user/search` | Две реплики | 336 224 | 5 603,73 | 17 | 41 | 59 | 0 |

В этих прогонах OK RPS вырос на 15,0% для чтения по ID и на 8,1% для поиска.

## SQL по узлам

Для каждого прогона взята разница `pg_stat_statements.calls` между концом прогрева и концом измерения. Сумма SQL-вызовов на репликах совпала с числом успешных запросов Gatling.

| Запрос | `primary` | `replica1` | `replica2` |
|---|---:|---:|---:|
| `GET /user/get/{id}` | 0 | 217 132 | 217 132 |
| `GET /user/search` | 0 | 168 112 | 168 112 |

Выполните этот запрос в `social_network_load` на каждом узле до и после основного прогона.

```sql
SELECT COALESCE(sum(calls) FILTER (WHERE query ILIKE '%FROM users WHERE id = %'), 0) AS get_calls,
       COALESCE(sum(calls) FILTER (WHERE query ILIKE '%WHERE first_name LIKE %'), 0) AS search_calls
FROM pg_stat_statements
WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
  AND query ILIKE 'SELECT id, first_name, second_name,%';
```

Для повторения запустите приложение на реплицированном стенде и выполните оба сценария с параметрами базового замера. Полные HTML-отчёты Gatling сохранены в `load-testing/gatling/target/gatling`, консольные журналы и дельты SQL — в `target/replication-routing`. [Запуск стенда](../../replication/README.md) · [Команды Gatling](baseline.md#нагрузка).
