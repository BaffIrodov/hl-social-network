# Запуск без Docker

Понадобятся JDK 21 и PostgreSQL 17.
Команды выполняются из корневой папки проекта.

## Подготовка БД

Если ранее запускали проект через Docker, остановите его контейнеры:

```shell
docker compose stop app db
```

Запустите установленный на компьютере PostgreSQL на порту `5432`.
Для пользователя `postgres` задайте пароль `postgres`.

Подключитесь к PostgreSQL:

```shell
psql -h localhost -p 5432 -U postgres -d postgres
```

Введите пароль `postgres`.

Создайте базу, если её ещё нет:

```sql
CREATE DATABASE social_network OWNER postgres;
```

Выйдите из `psql` командой `\q`.

## Запуск приложения

Linux/macOS:

```shell
./mvnw spring-boot:run
```

Windows PowerShell:

```powershell
.\mvnw.cmd spring-boot:run
```

Дождитесь сообщения `Started SocialNetworkApplication`.
Приложение доступно по адресу `http://localhost:8880`.

Таблицы создаются автоматически при запуске.
Для остановки приложения нажмите Ctrl+C.

## Проверка

Используйте [инструкцию проверки через Postman](local-startup.md#проверка-через-postman).

## Подключение к БД

Приложение использует следующие настройки по умолчанию:

| Параметр     | Значение                                          |
|--------------|---------------------------------------------------|
| JDBC URL     | `jdbc:postgresql://localhost:5432/social_network` |
| Пользователь | `postgres`                                        |
| Пароль       | `postgres`                                        |