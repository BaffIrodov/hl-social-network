# Базовый замер чтения на одном PostgreSQL

Замер проведён 10 ноября 2026 года. Приложение собрано из исходников коммита `f51be82` и запущено в Docker с Temurin 21.0.13. Gatling 3.16.0 запущен на том же компьютере под Windows с Java 21. Стенд работает на Ryzen 7 3700X. Docker видит 16 логических процессоров и около 15,6 ГиБ памяти. Максимальный размер пула HikariCP составляет 10 соединений.

В `social_network_load` на PostgreSQL 17.11 находится 1 000 000 анкет с ID от 1 до 1 000 000. Реплик нет. Набор данных взят из сданного ДЗ по индексам. В БД установлен индекс `users_surname_name_prefix_idx` на `(second_name varchar_pattern_ops, first_name varchar_pattern_ops)`. До и после прогона совпали состав индексов, число строк и контрольная сумма анкет `1efc85f6988fd5eb24d2e22d70ec97eb`.

PostgreSQL доступен приложению по `db:5432` внутри сети Docker. Для замера к `compose.yaml` и `load-testing/compose.load.yaml` добавлен локальный override с `services.db.ports: !reset []`, поскольку порт 5432 на хосте занят. Gatling обращается к приложению по `http://localhost:8880`.

## Нагрузка

Сначала измерено чтение по ID, затем поиск. Перед каждым измерением выполнен прогрев одним клиентом в течение 30 секунд. Основная нагрузка длится 60 секунд со 100 одновременными клиентами. Каждый клиент выполняет один GET без паузы и заменяется следующим.

- `GET /user/get/{id}` выбирает ID из диапазона от 1 до 1 000 000 с seed `42`. Проверяются HTTP 200 и совпадение ID в ответе с запрошенным.
- `GET /user/search` отправляет `first_name=Ив&second_name=Ив`. Запрос возвращает 14 анкет. Проверяются HTTP 200 и наличие JSON-массива.

Для повтора используются следующие параметры Maven Run из каталога `load-testing/gatling`. Для прогрева в каждой строке укажите `-Dusers=1 -Dduration=30`. [Настройка Gatling](../../gatling/README.md).

```text
gatling:test -Dgatling.simulationClass=ru.otus.socialnetwork.load.UserGetSimulation -Dusers=100 -Dduration=60
gatling:test -Dgatling.simulationClass=ru.otus.socialnetwork.load.UserSearchSimulation -Dusers=100 -Dduration=60
```

## Результаты

| Запрос | Успешных запросов | OK RPS | Средняя задержка, мс | p95, мс | p99, мс | Ошибки |
|---|---:|---:|---:|---:|---:|---:|
| `GET /user/get/{id}` | 377 724 | 6 295,4 | 14 | 31 | 45 | 0 |
| `GET /user/search` | 310 926 | 5 182,1 | 18 | 39 | 54 | 0 |

Показатели взяты из итоговой таблицы Gatling. Полные HTML-отчёты сохранены локально в `load-testing/gatling/target/gatling`, консольные журналы замеров — в `load-testing/gatling/target/replication-baseline`.
