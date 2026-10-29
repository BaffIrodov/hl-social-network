# Приложение на компьютере, PostgreSQL в Docker

Понадобятся JDK 21 и запущенный Docker с Compose v2.
Команды выполняются из корневой папки проекта.

## Запуск

Если приложение уже работает в Docker, остановите его:

```shell
docker compose stop app
```

Запустите PostgreSQL и дождитесь готовности:

```shell
docker compose up -d --wait db
```

Запустите приложение.

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

Для остановки приложения нажмите Ctrl+C.

## Запуск из IntelliJ IDEA

1. Откройте `pom.xml` как Maven-проект.
2. Выберите JDK 21 для проекта и Maven runner.
3. Запустите класс `ru.otus.socialnetwork.SocialNetworkApplication`.

PostgreSQL должен быть запущен командой из предыдущего раздела.

## Проверка

Используйте [инструкцию проверки через Postman](local-startup.md#проверка-через-postman).

## Подключение к БД

Эти настройки уже заданы в приложении по умолчанию:

| Параметр     | Значение                                          |
|--------------|---------------------------------------------------|
| JDBC URL     | `jdbc:postgresql://localhost:5432/social_network` |
| Пользователь | `postgres`                                        |
| Пароль       | `postgres`                                        |

Для подключения через панель Database в IDE:
адрес `localhost`, порт `5432`, база `social_network`.