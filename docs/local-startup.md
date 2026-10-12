# Локальный запуск

Команды выполняются из корневой папки проекта, в которой лежат `pom.xml` и `compose.yaml`.
Для запуска всего приложения в контейнерах нужен Docker с Compose v2.
Для запуска Java-процесса на компьютере нужен JDK 21; Maven Wrapper уже входит в проект.

## Параметры подключения

| Что подключается | Адрес | База | Пользователь | Пароль |
|---|---|---|---|---|
| IDE, локальная Java, клиент БД → PostgreSQL в Compose | `localhost:5432` | `social_network` | `postgres` | `postgres` |
| Контейнер приложения → контейнер БД | `db:5432` | `social_network` | `postgres` | `postgres` |
| Интеграционные тесты → отдельная тестовая БД | `localhost:5432` | `social_network_test` | `postgres` | `postgres` |

`5432` используется и на компьютере, и внутри контейнера. Основная БД — `social_network`,
тестовая — `social_network_test`; обе находятся на одном сервере PostgreSQL.
В существующем `.env` установите `DB_PORT=5432`. Если раньше вы вручную задавали
`DB_URL` в терминале или IDE, обновите его до `jdbc:postgresql://localhost:5432/social_network`.
Параметр `DB_PORT` меняет внешний порт. Если вы меняете его, измените также `DB_URL`
у локального Java-процесса и подключение в IDE. Адрес `db:5432` внутри Compose сохраняется.

## Всё приложение в Docker

1. Установите и запустите Docker Desktop на Windows/macOS или Docker Engine на Linux.
   На Windows для проекта нужны Linux-контейнеры; подходит WSL 2.
   Проверьте, что команды доступны в новом терминале:

```shell
docker version
docker compose version
```

2. `.env` необязателен: настройки уже имеют значения по умолчанию.
   При необходимости создайте его из `.env.example`. На Windows PowerShell:

```powershell
if (!(Test-Path .env)) { Copy-Item .env.example .env }
```

Linux/macOS:

```shell
[ -f .env ] || cp .env.example .env
```

Проверьте значения, особенно если `.env` остался от предыдущего запуска:

```dotenv
SERVER_PORT=8080
DB_PORT=5432
DB_USERNAME=postgres
DB_PASSWORD=postgres
TOKEN_TTL=6h
```

3. Соберите и запустите приложение:

```shell
docker compose up --build -d
docker compose logs -f app
```

Дождитесь сообщения `Started SocialNetworkApplication`. API доступен по
`http://localhost:8080`. Ctrl+C закрывает просмотр логов. Остановка контейнеров:

```shell
docker compose down
```

Данные сохраняются в volume `postgres-data`.
Если этот volume уже содержит БД с прежними учётными данными, сначала выполните
переход из раздела «Существующая база от предыдущей версии» ниже.

## PostgreSQL в Docker, приложение на компьютере

1. Если приложение уже работает в контейнере, остановите его, чтобы освободить порт 8080.
   Запустите БД и дождитесь её готовности:

```shell
docker compose stop app
docker compose up -d --wait db
```

2. В новом терминале проверьте Java:

```shell
java -version
```

Нужна версия 21. Если установлено несколько JDK, выберите JDK 21 через `JAVA_HOME` и `PATH`.

3. Задайте подключение и запустите приложение.

Windows PowerShell:

```powershell
$env:DB_URL = "jdbc:postgresql://localhost:5432/social_network"
$env:DB_USERNAME = "postgres"
$env:DB_PASSWORD = "postgres"
.\mvnw.cmd --version
.\mvnw.cmd spring-boot:run
```

Linux/macOS:

```shell
export DB_URL=jdbc:postgresql://localhost:5432/social_network
export DB_USERNAME=postgres
export DB_PASSWORD=postgres
./mvnw --version
./mvnw spring-boot:run
```

При первом запуске Wrapper загрузит Maven, а Maven — зависимости.
После старта приложение доступно по `http://localhost:8080`.
Liquibase сам создаёт таблицы; вручную выполнять SQL миграций не требуется.
Локальный Spring Boot автоматически не читает `.env`, используемый Compose.

### IntelliJ IDEA

Откройте `pom.xml` как Maven-проект. Выберите JDK 21 в Project SDK и в настройках Maven runner.
Создайте конфигурацию запуска для `ru.otus.socialnetwork.SocialNetworkApplication`.
В Environment variables добавьте:

```text
DB_URL=jdbc:postgresql://localhost:5432/social_network;DB_USERNAME=postgres;DB_PASSWORD=postgres
```

Запустите `SocialNetworkApplication`. Если нужна панель Database в IDE, создайте подключение
PostgreSQL с host `localhost`, port `5432`, database `social_network`, user `postgres`, password `postgres`.

## Своя PostgreSQL без Docker

Установите JDK 21 и PostgreSQL 17. Для локального пользователя `postgres` задайте пароль `postgres`.
Для этого варианта используется установленный на компьютере сервер PostgreSQL.
Если сервер БД в Compose уже запущен, остановите его перед использованием своей PostgreSQL
на том же порту:

```shell
docker compose stop app db
```
Проверьте фактический порт PostgreSQL: по умолчанию это `5432`.

Если базы `social_network` ещё нет, подключитесь через SQL-клиент или `psql`:

```shell
psql -h localhost -p 5432 -U postgres -d postgres
```

Введите пароль `postgres`, затем выполните:

```sql
CREATE DATABASE social_network OWNER postgres;
```

Выйдите из `psql` командой `\q`. Запустите приложение с фактическим адресом БД.
Windows PowerShell:

```powershell
$env:DB_URL = "jdbc:postgresql://localhost:5432/social_network"
$env:DB_USERNAME = "postgres"
$env:DB_PASSWORD = "postgres"
.\mvnw.cmd spring-boot:run
```

Linux/macOS:

```shell
export DB_URL=jdbc:postgresql://localhost:5432/social_network
export DB_USERNAME=postgres
export DB_PASSWORD=postgres
./mvnw spring-boot:run
```

## Существующая база от предыдущей версии

Переменные `POSTGRES_USER` и `POSTGRES_PASSWORD` у Docker-образа применяются при
создании новой БД в пустом volume. Изменение Compose или `.env` не меняет
учётные данные уже существующей БД.

Для базы, созданной прежней конфигурацией с пользователем `social_network`,
подключитесь через старую административную роль:

```shell
docker compose stop app
docker compose up -d db
docker compose exec db psql -U social_network -d social_network
```

Внутри `psql` выполните следующий блок: он создаст роль `postgres`, если её нет,
либо установит ей нужный пароль. Новая роль получает те же административные
возможности, которые стандартный Docker-образ задаёт своему начальному пользователю.

```sql
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'postgres') THEN
        ALTER ROLE postgres WITH LOGIN PASSWORD 'postgres';
    ELSE
        CREATE ROLE postgres WITH LOGIN SUPERUSER PASSWORD 'postgres';
    END IF;
END;
$$;
```

Выйдите командой `\q`. Убедитесь, что в `.env` стоят `DB_USERNAME=postgres` и
`DB_PASSWORD=postgres`, затем запустите приложение:

```shell
docker compose up --build -d app
```

Порядок выше сохраняет данные и прежнюю роль. Если БД изначально создавалась
с другим административным пользователем, укажите его вместо `social_network` при подключении.

## Проверка через Postman

Импортируйте файлы из папки `postman`, выберите окружение **Social Network — Local**
и проверьте `base_url=http://localhost:8080`.
Коллекция содержит два раздела по три запроса:

- **01 — Ручная проверка.** Выполните регистрацию и скопируйте `user_id` из ответа.
  В Body запроса входа замените `"id":"1"` на этот ID; в URL анкеты замените конечный `/1`.
  Выполните вход и получение анкеты. Скриптов сохранения переменных в этом разделе нет:
  ответы вы читаете и переносите самостоятельно. `base_url` и `password` остаются
  общими настройками, которые запросы используют, но не изменяют.
- **02 — Автоматическая подстановка переменных.** Выполните регистрацию → вход → анкету.
  ID регистрации сохраняется в `user_id` и подставляется в следующие запросы;
  UUID из ответа входа сохраняется в `token`. Можно запускать эту папку в Collection Runner.

Например, после ручной регистрации с `{"user_id":"42"}` задайте в Body входа
`"id":"42"`, а в запросе анкеты — URL `{{base_url}}/user/get/42`.
Для автоматического варианта редактировать ID не требуется.
Подробности и команда Newman: раздел Postman в [README](../README.md#postman).

Токен пока служит заготовкой сессии для будущих защищённых операций.
Текущие три запроса его не используют; заголовок `Authorization` не требуется.
Срок сессии по умолчанию — 6 часов (`TOKEN_TTL`).

## Интеграционные тесты

Основная и тестовая базы размещаются на одном сервере PostgreSQL с портом `5432`.
Тесты подключаются к `social_network_test`, приложение — к `social_network`.
Приложение может продолжать работать во время тестов:
очистка таблиц в тестах относится только к тестовой базе.

Запустите PostgreSQL:

```shell
docker compose up -d --wait db
```

Один раз создайте тестовую БД, если её ещё нет:

```shell
docker compose exec db createdb -U postgres social_network_test
```

Для своей PostgreSQL без Docker используйте `createdb -h localhost -p 5432 -U postgres social_network_test`
либо выполните `CREATE DATABASE social_network_test OWNER postgres;` в SQL-клиенте.

Запустите тесты:

```shell
./mvnw -Pintegration-test verify
```

На Windows используйте `.\mvnw.cmd -Pintegration-test verify`.
Подключение по умолчанию: `jdbc:postgresql://localhost:5432/social_network_test`,
пользователь и пароль `postgres/postgres`.
Тесты очищают таблицы этой базы: задавайте `TEST_DB_URL` только для отдельной тестовой БД.

## Справка

- [Docker Desktop для Windows](https://docs.docker.com/desktop/setup/install/windows-install/)
- [PostgreSQL: подключения и порты](https://www.postgresql.org/docs/17/runtime-config-connection.html)
- [Docker Compose: сеть контейнеров](https://docs.docker.com/compose/how-tos/networking/)
- [Официальный образ PostgreSQL: переменные и существующие данные](https://github.com/docker-library/docs/blob/master/postgres/README.md)
