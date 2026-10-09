# Социальная сеть — домашнее задание №1

Монолит на Java 21 и Spring Boot 4.1.1: регистрация, вход и публичное получение анкеты по ID.
PostgreSQL 17, Spring JDBC с `NamedParameterJdbcTemplate`, Liquibase, Argon2id.
ORM, кэширование, репликация и шардирование не используются. Фронтенд в этом ДЗ не требуется.

## Быстрый запуск через Docker

Понадобятся Docker с Compose v2 и доступ к интернету для загрузки образов и зависимостей.
Локально устанавливать Java или Maven для этого варианта не нужно.
Распакуйте архив, откройте терминал в папке `social-network-hw` и выполните:

```shell
docker compose up --build -d
docker compose logs -f app
```

Дождитесь сообщения `Started SocialNetworkApplication`. Приложение: `http://localhost:8080`.
База: `localhost:5932`, database/user — `social_network`, пароль — `local_password`.
Liquibase автоматически создаёт таблицы при первом запуске и проверяет миграции при последующих.
Ctrl+C завершает просмотр логов; контейнеры продолжают работать.

```shell
docker compose down
```

Останавливает контейнеры, сохраняя данные в Docker volume `postgres-data`.
Для изменения портов или TTL скопируйте `.env.example` в `.env` и отредактируйте значения.
Например, `DB_PORT=5435`, если порт 5932 занят.

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

1. Запустите только базу:

```shell
docker compose up -d db
```

2. Проверьте Java и запустите приложение.

Linux/macOS:

```shell
./mvnw --version
./mvnw spring-boot:run
```

Windows PowerShell:

```powershell
.\mvnw.cmd --version
.\mvnw.cmd spring-boot:run
```

В IDE можно запускать класс `ru.otus.socialnetwork.SocialNetworkApplication`.
При использовании своей PostgreSQL задайте `DB_URL`, `DB_USERNAME` и `DB_PASSWORD`.
Например, в PowerShell:

```powershell
$env:DB_URL = "jdbc:postgresql://localhost:5435/social_network"
$env:DB_USERNAME = "social_network"
$env:DB_PASSWORD = "local_password"
.\mvnw.cmd spring-boot:run
```

Файл `.env` читает Docker Compose. Spring Boot при запуске из IDE или Maven автоматически его не читает:
передайте нужные значения через переменные окружения или параметры приложения.

## API

Все три метода доступны без `Authorization`. `/login` проверяет ID и пароль и выдаёт токен.
Регистрация возвращает только `user_id`, без автоматического входа.
Успешные ответы имеют код **200**, как в исходной спецификации.

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

Выберите окружение **Social Network — Local**. При необходимости измените `base_url`.
Запустите всю коллекцию через Collection Runner либо выполните первый раздел по порядку:
регистрация → вход → публичная анкета. `user_id` и `token` сохраняются автоматически.
Коллекция содержит успешные сценарии, значения по умолчанию и отрицательные проверки.
Пароль в окружении — демонстрационный. Каждый запуск коллекции создаёт новые анкеты.

При установленном Node.js можно выполнить:

```shell
npx newman run postman/social-network.postman_collection.json -e postman/local.postman_environment.json
```

## Тесты и сборка

Модульные тесты не требуют БД:

```shell
./mvnw test
```

Интеграционные тесты запускаются на выделенной PostgreSQL, миграции применяются автоматически:

```shell
docker compose --profile test up -d --wait db-test
./mvnw -Pintegration-test verify
```

На Windows используйте `.\mvnw.cmd` вместо `./mvnw`.
По умолчанию тестовая БД: `jdbc:postgresql://localhost:5433/social_network_test`.
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
| `DB_URL` | `jdbc:postgresql://localhost:5932/social_network` |
| `DB_USERNAME` | `social_network` |
| `DB_PASSWORD` | `local_password` |
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
