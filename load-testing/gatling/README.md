# Ручное нагрузочное тестирование чтения анкет

Gatling 3.16.0, Java 21, Maven Wrapper. Один запуск проверяет один уровень нагрузки.
Приложение и PostgreSQL запускаются отдельно; приложение подключается к `social_network_load`.
Данные: [миллион анкет](../docs/load-data.md).

## Запуск в IDEA

Создайте конфигурацию **Run → Edit Configurations → + → Maven**:

- Working directory: `C:\Users\serge\Desktop\hl-social-network\load-testing\gatling`.
- JRE: Java 21.
- Command line:

```text
gatling:test -Dusers=100 -Dduration=60
```

По умолчанию запускается `UserSearchSimulation` для `/user/search`. Для `/user/get/{id}` укажите класс `UserGetSimulation`:

```text
gatling:test -Dgatling.simulationClass=ru.otus.socialnetwork.load.UserGetSimulation -Dusers=100 -Dduration=60
```

Приложение запускайте через Run, с включённым `Disable launch optimization`.
Для прогрева выполните `gatling:test -Dusers=1 -Dduration=30`.

## Параметры

| Параметр     | По умолчанию            | Значение                                |
|--------------|-------------------------|-----------------------------------------|
| `users`      | `1`                     | Число одновременно работающих клиентов  |
| `duration`   | `60`                    | Длительность подачи нагрузки, секунды   |
| `baseUrl`    | `http://localhost:8880` | Адрес приложения                        |
| `firstName`  | `Ив`                    | Префикс имени                           |
| `secondName` | `Ив`                    | Префикс фамилии                         |
| `maxUserId`  | `1000000`               | Верхняя граница ID для `/user/get/{id}` |

Каждый клиент выполняет один GET, затем заменяется следующим. Пауз между запросами нет.
Сценарий чтения по ID использует псевдослучайную последовательность с seed `42` и сверяет поле `id` в ответе. Все ID от `1` до `maxUserId` должны существовать в БД.

## Результаты

В итоговой таблице в консоли показано число запросов, пропускная способность,
среднее время ответа, p95 и p99.

План сравнения: [методология](../reports/user-search/README.md).
Переключение индексов и одинаковый EXPLAIN: [SQL-кандидаты](../scripts/index-candidates/README.md).
