# Данные для нагрузочных тестов

Для поиска генерируем 1 000 000 анкет SQL-скриптом.
Нужен Docker с Compose v2. Команды выполняются из корня проекта.

## Запуск с отдельной базой

Запустите PostgreSQL:

```shell
docker compose up -d db
```

Один раз создайте базу для нагрузки:

```shell
docker compose exec db createdb -U postgres social_network_load
```

Если база уже существует, повторять эту команду не нужно.

Подключите словарь и SQL-скрипт к контейнеру и переключите приложение на нагрузочную базу:

```shell
docker compose -f compose.yaml -f load-testing/compose.load.yaml up --build -d
docker compose logs -f app
```

Дождитесь `Started SocialNetworkApplication`, затем выйдите из просмотра логов через Ctrl+C.
Liquibase автоматически создаст таблицы. API доступен на `http://localhost:8880`.

## Словарь и генерация

В [profile-names.json](../data/profile-names.json) сохранены все уникальные
имена и фамилии из исходного CSV OTUS: 138 имён и 1006 фамилий.

Каждая анкета получает:

- случайное имя и фамилию из отдельных списков, с одинаковой вероятностью выбора;
- случайную дату рождения: полный возраст от 20 до 50 лет включительно на день генерации;
- случайный пол `MALE` или `FEMALE`, с одинаковой вероятностью выбора;
- `biography=null`, `city=null`.

Запустите генератор:

```shell
docker compose -f compose.yaml -f load-testing/compose.load.yaml exec -T db psql -U postgres -d social_network_load -f /load/generate-profiles.sql
```

Команда создаем/заменяет существующие анкеты только в `social_network_load`.

Генерация выполняется одной транзакцией. После неё обновляется статистика
командой `ANALYZE users`.
При повторном запуске набор будет новым; размер задаётся константой `profile_count`
в [generate-profiles.sql](../scripts/generate-profiles.sql).

## Проверка

```shell
docker compose exec db psql -U postgres -d social_network_load -c "SELECT count(*) FROM users;"
```

Ожидаемый результат — `1000000`.
В Postman выполните запрос поиска с префиксами `Ив` и `Ив`.
Интеграционные тесты используют `social_network_test` и не очищают нагрузочную базу.

Чтобы вернуть приложение к обычной базе:

```shell
docker compose up -d
```
