# Архитектура и сценарии

Один Java-процесс и PostgreSQL. Контроллеры отвечают за HTTP, сервисы — за сценарии и транзакции,
репозитории — за параметризованный SQL. Публичная модель анкеты не содержит данных для входа.
Spring Security подключён как модуль `spring-security-crypto`; автоматического требования
авторизации и браузерной формы входа нет.

```mermaid
flowchart TD
    Client["Postman или браузер"]
    subgraph App["Монолит Spring Boot"]
        UC["UserController"]
        AC["AuthController"]
        US["UserService"]
        AS["AuthService"]
        UR["UserRepository"]
        CR["CredentialsRepository"]
        SR["SessionRepository"]
        PE["Argon2id PasswordEncoder"]
        UC --> US
        AC --> AS
        US --> UR
        US --> CR
        US --> PE
        AS --> CR
        AS --> SR
        AS --> PE
    end
    Client --> UC
    Client --> AC
    UR --> DB[("PostgreSQL")]
    CR --> DB
    SR --> DB
```

## Таблицы

```mermaid
erDiagram
    USERS ||--o| USER_CREDENTIALS : "пароль"
    USERS ||--o{ USER_SESSIONS : "сессии"
    USERS {
        integer id PK
        varchar first_name
        varchar second_name
        date birthdate
        user_gender gender
        text biography
        varchar city
    }
    USER_CREDENTIALS {
        integer user_id PK, FK
        text password_hash
    }
    USER_SESSIONS {
        varchar token_hash PK
        integer user_id FK
        timestamptz created_at
        timestamptz expires_at
    }
```

FK и PK допускают максимум одну запись пароля на пользователя. Наличие пароля у каждой созданной
анкеты обеспечивает транзакция регистрации. Гендер: MALE, FEMALE, UNSPECIFIED.

## Регистрация и вход

```mermaid
flowchart TD
    R["POST /user/register"] --> V["Проверить данные"]
    V --> H["Вычислить Argon2id"]
    H --> T["Транзакция: анкета и хеш"]
    T --> ID["Ответ: user_id строкой"]
    L["POST /login"] --> C["Найти хеш по ID"]
    C --> M{"Пароль подходит?"}
    M -->|Нет| E["400: неверный пароль"]
    M -->|Да| U["Создать UUIDv4"]
    U --> S["Сохранить SHA-256 и срок"]
    S --> O["Ответ: token"]
```

Не найден пользователь — 404. Невалидные данные — 400.
Получение `/user/get/{id}` обращается только к таблице users и не требует токена.

## Разделение секретов

- Анкета: только публичные поля.
- Пароль: соль и параметры входят в строку Argon2id.
- Токен: UUID у клиента, SHA-256 в БД.
- Поиск сессии: хеш токена и проверка `expires_at`; результат — постоянный integer ID.

Публичное имя, друзья, посты и сообщения могут быть добавлены дальнейшими миграциями.
