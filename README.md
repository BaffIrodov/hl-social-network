# Социальная сеть — домашнее задание №1

Монолит на Java 21 и Spring Boot 4.1.1: регистрация, вход и публичное получение анкеты по ID.
PostgreSQL 17, Spring JDBC с `NamedParameterJdbcTemplate`, Liquibase, Argon2id.
ORM, кэширование, репликация и шардирование не используются. Фронтенд в этом ДЗ не требуется.

## Быстрый запуск через Docker

Подробная инструкция для Windows, Linux/macOS, IntelliJ IDEA и своей PostgreSQL:
[Локальный запуск](docs/local-startup.md).

Понадобится запущенный Docker Desktop (на Windows — с Linux-контейнерами) или Docker Engine
с Compose v2. Локальные Java и Maven для этого варианта не нужны.
Распакуйте архив и откройте терминал в папке `hl-social-network`, где находится `compose.yaml`.
Если у вас уже есть `.env`, выставьте в нём `DB_USERNAME=postgres` и `DB_PASSWORD=postgres`.

```shell
docker compose up --build -d
docker compose logs -f app
```

Дождитесь сообщения `Started SocialNetworkApplication`. Приложение: `http://localhost:8080`.
Подключение к БД с компьютера: `localhost:5432`, база `social_network`,
пользователь `postgres`, пароль `postgres`.
Внутри сети Compose приложение использует `db:5432`.
Liquibase автоматически создаёт таблицы при первом запуске и проверяет миграции при последующих.
Ctrl+C завершает просмотр логов; контейнеры продолжают работать.

```shell
docker compose down
```

Останавливает контейнеры, сохраняя данные в volume `postgres-data`.
Для изменения портов или TTL скопируйте `.env.example` в `.env` и отредактируйте значения.
По умолчанию `DB_PORT=5432`. Если `.env` остался от прежнего запуска, обновите в нём `DB_PORT`.
При наличии данных от предыдущего запуска смена переменных не переименовывает пользователя БД
и не меняет его пароль: порядок перехода описан в [инструкции](docs/local-startup.md#существующая-база-от-предыдущей-версии).

## Ошибка сборки PKIX / certificate_unknown

Если Maven в Docker пишет `PKIX path building failed` или
`unable to find valid certification path`, JVM сборочного контейнера не доверяет
цепочке сертификатов HTTPS-сервера. Строки `Non-resolvable parent POM` и `absent`
в этом случае являются следствием неудачной загрузки.

Сначала обновите базовый образ и пересоберите приложение:

```shell
docker compose build --pull --no-cache app
```

Если ошибка сохраняется, возможная причина — проверка HTTPS корпоративным прокси,
VPN или антивирусом, чей CA известен Windows, но отсутствует в JVM контейнера.
Сертификат, который требуется добавить, зависит от вашей сети.

1. Получите доверенный CA-сертификат у администратора сети либо из настроек
   программы, проверяющей HTTPS. На Windows уже установленный сертификат можно
   экспортировать через `certmgr.msc` или `certlm.msc`: «Доверенные корневые центры
   сертификации» → нужный сертификат → «Все задачи» → «Экспорт».
   Выберите X.509, например «Base-64 encoded X.509 (.CER)», и сохраните его как
   `network-ca.crt`. Добавляйте сертификат CA, соответствующего цепочке, которую
   контейнер получает при соединении с Maven Central.
2. Положите файл в `docker/certs/network-ca.crt` внутри проекта. Если необходим
   промежуточный CA, добавьте его отдельным файлом с расширением `.crt`.
3. Выполните:

```shell
docker compose build --pull --no-cache app
docker compose up -d
docker compose logs -f app
```

Dockerfile импортирует все `docker/certs/*.crt` в Java `cacerts` через `keytool`
до первого запуска Maven. Пустая папка допустима; некорректный или повреждённый
сертификат останавливает сборку. Эти сертификаты добавляются только на стадии
сборки. В итоговый контейнер приложения копируется JAR.
Локальные `.crt` исключены из Git; собственные сертификаты в архив не включены.

Если причина не ясна, посмотреть получаемую цепочку из отдельного контейнера можно
так (одна строка подходит и для PowerShell):

```shell
docker run --rm maven:3.9-eclipse-temurin-21 keytool -printcert -sslserver repo.maven.apache.org:443
```

Поля `Owner` / `Subject` и `Issuer` / «Издатель» помогут определить нужный CA.
Команда использует прямое TLS-соединение; если у вас явно настроен отдельный
HTTP-прокси только для сборки, маршрут этой диагностики может отличаться.

Справка: [Docker: CA certificates](https://docs.docker.com/engine/network/ca-certs/),
[Java 21: keytool](https://docs.oracle.com/en/java/javase/21/docs/specs/man/keytool.html).

## Запуск из IDE / с локальной Java

Нужен JDK 21. Maven Wrapper включён: установленный Maven не обязателен.
В IntelliJ IDEA откройте `pom.xml` как Maven-проект и выберите JDK 21 для проекта и Maven runner.

Запустите только PostgreSQL. Если контейнер приложения уже запущен, остановите его,
чтобы освободить порт 8080 для локального Java-процесса:

```shell
docker compose stop app
docker compose up -d --wait db
```

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

В IDE можно запускать класс `ru.otus.socialnetwork.SocialNetworkApplication`.
Укажите те же `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` в Environment variables конфигурации запуска.
Файл `.env` читает Docker Compose; локальный запуск Spring Boot из IDE или Maven
автоматически его не читает.

Для PostgreSQL, установленной напрямую на компьютере, создайте базу `social_network`
и задайте фактический порт в `DB_URL` (обычно 5432).
Полный порядок запуска и настройки IDE: [docs/local-startup.md](docs/local-startup.md).

## API

Все три метода доступны без `Authorization`. `/login` проверяет ID и пароль и выдаёт токен.
Регистрация возвращает только `user_id`, без автоматического входа.
Успешные ответы имеют код **200**, как в исходной спецификации.
Токен, полученный после входа, не требуется для текущих трёх методов.
Он возвращается по контракту `/login`; проверка сессии подготовлена для будущих
защищённых операций, например редактирования собственной анкеты.

| Метод | Назначение |
|---|---|
| `POST /user/register` | Регистрация и создание анкеты |
| `POST /login` | Проверка пароля и создание сессии |
| `GET /user/get/{id}` | Публичная анкета |

Пример регистрации:

```json
{
  "first_name": "Иван",
  "second_name": "Иванов",
  "birthdate": "1995-05-20",
  "gender": "MALE",
  "biography": "Java, музыка",
  "city": "Москва",
  "password": "ExamplePassword123!"
}
```

Ответ:

```json
{"user_id": "1"}
```

Вход:

```json
{"id": "1", "password": "ExamplePassword123!"}
```

Ответ:

```json
{"token": "e4d2e6b0-cde2-42c5-aac3-0b8316f21e58"}
```

Анкета `GET /user/get/1`:

```json
{
  "id": "1",
  "first_name": "Иван",
  "second_name": "Иванов",
  "birthdate": "1995-05-20",
  "gender": "MALE",
  "biography": "Java, музыка",
  "city": "Москва"
}
```

Расширенный контракт находится в `docs/openapi.json`; исходный приложенный контракт —
в `docs/otus-openapi.original.json`. Добавлен `gender`, конкретизированы валидация и ошибки.
Другие методы большой спецификации OTUS пока не реализованы.

## Валидация и ошибки

| Поле | Правило |
|---|---|
| `id` | Строковое представление числа 1..2147483647, без ведущих нулей, знака и пробелов |
| `first_name`, `second_name` | Обязательны; не пустые, не только пробелы; до 100 символов |
| `birthdate` | Обязательна; календарная дата `YYYY-MM-DD`, не в будущем |
| `gender` | `MALE`, `FEMALE`, `UNSPECIFIED`; отсутствующее поле или `null` → `UNSPECIFIED` |
| `biography` | Необязательно; до 2000 символов |
| `city` | Необязательно; до 100 символов |
| `password` | Обязателен; 12–40 печатных ASCII-символов без пробелов: `^[!-~]{12,40}$` |

Имя, фамилия, интересы и город очищаются от пробельных символов по краям.
Пустые интересы и город сохраняются как `NULL` и возвращаются как JSON `null`.
Пароль сохраняется в точности после проверки: приложение его не обрезает и не изменяет.
Допустимы кавычки, обратный слеш и другие печатные ASCII-знаки; в JSON их нужно корректно экранировать.
Не требуется обязательная заглавная буква, цифра или спецсимвол.
Неизвестные поля JSON отклоняются с 400.

| Код | Причина |
|---|---|
| `400` | Невалидный запрос, неправильный пароль, неверный ID, неподдерживаемый HTTP-метод/Content-Type |
| `404` | Пользователь с корректным ID не существует или адрес API не найден |
| `500` | Непредвиденная ошибка сервера |
| `503` | База стала недоступна после запуска приложения |

```json
{
  "message": "Пароль не должен содержать пробельные символы",
  "request_id": "761a9817-9c63-4b48-9373-d2d40f46cb41",
  "errors": [
    {"field": "password", "message": "Пароль не должен содержать пробельные символы"}
  ]
}
```

В каждом ответе есть заголовок `X-Request-Id`. В ошибках тот же ID передаётся в JSON.
Пользователь не получает SQL, стек исключения, хеш пароля или токен сессии из БД.
В текущем контракте неверный пароль возвращает 400, отсутствующий пользователь — 404.

## Пароли и сессии

Пароль хранится отдельно от анкеты. Argon2id: соль 16 байт, результат 32 байта,
память 19 МиБ, два прохода, параллелизм 1. Строка содержит алгоритм, параметры, соль и результат.
Проверка: `PasswordEncoder.matches(rawPassword, storedHash)`.

После входа генерируется UUIDv4 через `UUID.randomUUID()`. Клиент получает UUID,
БД — полный SHA-256 от канонической строки UUID в шестнадцатеричном виде.
Каждый вход создаёт отдельную сессию; предыдущие не отзываются.
TTL по умолчанию — шесть часов, задаётся `TOKEN_TTL` или `app.auth.token-ttl`.

`SessionService.resolveUserId(token)` проверяет существование и срок сессии и готов для будущих
защищённых методов. На текущие три метода проверка токена не навешивается.
JWT, refresh-token и logout API в это ДЗ не входят.
Срок действия проверяется независимо от физического удаления записи; фоновой очистки пока нет.

## База и SQL

Схемы и сценарии: `docs/architecture.md`.
Миграции: `src/main/resources/db/changelog/001-initial-schema.sql`.
Три таблицы приложения:

- `users`: integer IDENTITY PK, поля анкеты.
- `user_credentials`: `user_id` — одновременно PK и FK; Argon2id-хеш.
- `user_sessions`: PK — SHA-256 токена; FK — пользователь; время создания и истечения.

Удаление пользователя на уровне БД каскадно удаляет его пароль и сессии.
Внутренний ID постоянный; функция публичного переименования пока отсутствует.
Регистрация сохраняет анкету и пароль одной транзакцией.
IDENTITY может оставлять пропуски в ID после отката — это нормальное поведение.

Индексы приложения создаются только автоматически для PRIMARY KEY.
Liquibase также создаёт свои служебные таблицы `databasechangelog` и `databasechangeloglock`.
SQL написан вручную; все пользовательские значения передаются отдельными JDBC-параметрами.
`Hibernate Validator` используется для проверки DTO и не является ORM.

## Postman

Импортируйте:

- `postman/social-network.postman_collection.json`
- `postman/local.postman_environment.json`

Выберите окружение **Social Network — Local**. Значения можно посмотреть через
Environments → Social Network — Local или на вкладке Variables самой коллекции.
`base_url` по умолчанию — `http://localhost:8080`, `password` — `ExamplePassword123!`.
Эти две переменные читаются обоими разделами. `user_id` и `token` используются
для сохранения результатов автоматического сценария.

В коллекции **шесть запросов**, разделённых на две папки.

### 01 — Ручная проверка

Три запроса без скриптов после ответа: регистрация → вход → публичная анкета.
Переменные Postman автоматически не изменяются.

1. Выполните «Регистрация» и скопируйте `user_id` из JSON-ответа.
2. В «Вход» откройте Body и замените пример `"id": "1"` на полученный ID.
   Используйте тот же пароль, что при регистрации. Выполните запрос.
   UUID-токен останется в JSON-ответе и не будет записан в переменную `token`.
3. В «Публичная анкета» замените конечный `/1` в URL на полученный ID и выполните запрос.

Например, при ответе `{"user_id":"42"}` тело входа должно содержать `"id":"42"`,
а URL анкеты — `{{base_url}}/user/get/42`.
Токен для получения анкеты не требуется. ID `1` в запросах — редактируемый пример.
Этот раздел предназначен для поочерёдного ручного выполнения.

### 02 — Автоматическая подстановка переменных

Те же три запроса со скриптами после ответа. Выполните их по порядку
или запустите только эту папку в Collection Runner:

1. «Регистрация» сохраняет полученный ID в `user_id`.
2. «Вход» использует `{{user_id}}` и сохраняет UUID из ответа в `token`.
3. «Публичная анкета» использует `{{user_id}}` в URL.

Скрипты сохраняют результаты в переменные коллекции и выбранного окружения,
а также проверяют успешные ответы. Сохранённый токен пока не передаётся в запросы:
все три метода доступны без `Authorization`.
Пароль в окружении — демонстрационный. Каждый запуск регистрации создаёт новую анкету.

При установленном Node.js автоматический раздел можно выполнить через Newman:

```shell
npx newman run postman/social-network.postman_collection.json -e postman/local.postman_environment.json --folder "02 — Автоматическая подстановка переменных"
```

## Тесты и сборка

Модульные тесты не требуют БД:

```shell
./mvnw test
```

Интеграционные тесты используют отдельную базу `social_network_test` на том же сервере
PostgreSQL и порту 5432. Миграции применяются автоматически. Запустите сервер:

```shell
docker compose up -d --wait db
```

Один раз создайте тестовую БД, если её ещё нет:

```shell
docker compose exec db createdb -U postgres social_network_test
```

Запустите тесты:

```shell
./mvnw -Pintegration-test verify
```

На Windows используйте `.\mvnw.cmd` вместо `./mvnw`.
По умолчанию тестовая БД: `jdbc:postgresql://localhost:5432/social_network_test`.
По умолчанию пользователь и пароль тестовой БД — `postgres/postgres`.
Можно переопределить `TEST_DB_URL`, `TEST_DB_USERNAME`, `TEST_DB_PASSWORD`.
Интеграционные тесты очищают таблицы приложения в тестовой БД перед каждым сценарием;
используйте для них отдельную базу.

Проверяются регистрация, публичные анкеты, Argon2id, UUID-токены, TTL и его настройка,
ошибки ввода, SQL-инъекции, откат транзакции и повторное применение миграций.
GitHub Actions настроен на выполнение этих проверок на Java 21 и обычном PostgreSQL 17.
Результаты проверки в среде генерации и её ограничения: `docs/verification.md`.

```shell
./mvnw package
java -jar target/social-network-0.0.1-SNAPSHOT.jar
```

База должна быть запущена. В Docker сборка JAR выполняется внутри build-контейнера.

## Настройки

| Переменная окружения | Значение по умолчанию |
|---|---|
| `SERVER_PORT` | `8080` |
| `DB_URL` | `jdbc:postgresql://localhost:5432/social_network` |
| `DB_USERNAME` | `postgres` |
| `DB_PASSWORD` | `postgres` |
| `TOKEN_TTL` | `6h` |

В `.env` для Compose дополнительно доступен `DB_PORT` — порт БД на хосте.
При изменении DB_PORT локальному запуску Java нужен соответствующий DB_URL.
Пароль существующей БД не меняется от изменения переменной POSTGRES_PASSWORD после создания volume.

## Размещение в GitHub

Создайте пустой репозиторий в своём GitHub и из корня проекта выполните:

```shell
git init -b main
git add .
git commit -m "Implement social network homework 1"
git remote add origin https://github.com/YOUR_ACCOUNT/YOUR_REPOSITORY.git
git push -u origin main
```

Замените адрес на свой. `.gitignore` исключает `.env`, сборку и настройки IDE.
README, Postman, спецификации, исходники и workflow попадут в репозиторий.

## Справочные материалы

- [Spring JDBC](https://docs.spring.io/spring-framework/reference/data-access/jdbc/choose-style.html)
- [Spring Security: PasswordEncoder](https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html)
- [OWASP: Password Storage](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html)
- [PostgreSQL: Identity](https://www.postgresql.org/docs/17/ddl-identity-columns.html)
