# Ручное нагрузочное тестирование /user/search

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

Сценарий `UserSearchSimulation` выбран в `pom.xml`, в папке `load-testing`.

Приложение запускайте через Run, с включённым `Disable launch optimization`.
Для прогрева выполните `gatling:test -Dusers=1 -Dduration=30`.

## Параметры

| Параметр     | По умолчанию            | Значение                               |
|--------------|-------------------------|----------------------------------------|
| `users`      | `1`                     | Число одновременно работающих клиентов |
| `duration`   | `60`                    | Длительность подачи нагрузки, секунды  |
| `baseUrl`    | `http://localhost:8880` | Адрес приложения                       |
| `firstName`  | `Ив`                    | Префикс имени                          |
| `secondName` | `Ив`                    | Префикс фамилии                        |

Каждый клиент выполняет один GET `/user/search`, затем заменяется следующим. Пауз между запросами нет.

## Результаты

В итоговой таблице в консоли показано число запросов, пропускная способность,
среднее время ответа, p95 и p99.

План сравнения: [методология](../reports/user-search/README.md).
Переключение индексов и одинаковый EXPLAIN: [SQL-кандидаты](../scripts/index-candidates/README.md).
