# Проверка репликации PostgreSQL

## 1. Подготовьте окружение

Для Windows, Linux и macOS нужны Docker с Compose, PowerShell 7 и JDK 21. Запустите Docker и откройте PowerShell 7 командой `pwsh`. Выполняйте шаги из корня репозитория в одном окне.

Освободите порты `5432` и `8880` на хосте, проверьте, что у вас нужные версии PowerShell, Docker, Java.

```powershell
$PSVersionTable.PSVersion
docker version
java -version
javac -version
```

Ожидаются PowerShell 7, секция `Server` в выводе Docker и версия 21 у `java` и `javac`. Если `JAVA_HOME` пуста или указывает на другую версию, выберите блок для своей ОС и повторите проверку.

**Windows.** Укажите путь к установленному JDK 21.

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
```

**Linux.** Укажите путь к установленному JDK 21.

```powershell
$env:JAVA_HOME = '/path/to/jdk-21'
$env:PATH = "$($env:JAVA_HOME)/bin:$env:PATH"
```

**macOS.** Выберите установленный JDK 21 через `java_home`.

```powershell
$env:JAVA_HOME = & /usr/libexec/java_home -F -v 21
$env:PATH = "$($env:JAVA_HOME)/bin:$env:PATH"
```

Далее подготовьте параметры запуска.

```powershell
$reviewRun = Get-Date -Format 'yyyyMMddHHmmss'
$reviewDir = "target/replication-review-$reviewRun"
$replicationCompose = 'load-testing/replication/compose.yaml'
$baseline = @('-p', "hl-review-baseline-$reviewRun", '-f', 'compose.yaml', '-f', 'load-testing/compose.load.yaml')
$cluster = @('-p', "hl-review-replication-$reviewRun", '-f', $replicationCompose)

function Invoke-MavenWrapper {
    if ($IsWindows) {
        & .\mvnw.cmd @args
    } else {
        & sh ./mvnw @args
    }
    if ($LASTEXITCODE -ne 0) { throw "Maven Wrapper exited with code $LASTEXITCODE" }
}
```

## 2. Снимите базовые замеры на одной БД

Запустите PostgreSQL, создайте нагрузочную базу и запустите приложение.

```powershell
docker compose @baseline up -d --wait db
docker compose @baseline exec -T db createdb -U postgres social_network_load
docker compose @baseline up -d --build app
docker compose @baseline logs -f app
```

Дождитесь `Started SocialNetworkApplication` и выйдите из просмотра логов через `Ctrl+C`. Заполните нагрузочную базу и проверьте API.

```powershell
docker compose @baseline exec -T db psql -U postgres -d social_network_load -f /load/generate-profiles.sql
docker compose @baseline exec -T db psql -U postgres -d social_network_load -c 'SELECT count(*), min(id), max(id) FROM public.users'
Invoke-RestMethod 'http://localhost:8880/user/get/1' -TimeoutSec 10
```

Ожидается 1 000 000 анкет с ID от 1 до 1 000 000. Сценарий `UserGetSimulation` выбирает ID из этого диапазона, а `UserSearchSimulation` ищет по префиксам `first_name=Ив` и `second_name=Ив`. Генератор создаёт случайный набор, поэтому число совпадений может отличаться от отчёта.

Объявите функцию запуска Gatling. Для каждого GET сначала выполните прогрев одним клиентом на 30 секунд, затем замер со 100 одновременными клиентами на 60 секунд.

```powershell
function Invoke-ReadRun($scenario, $users, $duration) {
    Push-Location 'load-testing/gatling'
    try {
        Invoke-MavenWrapper -B -ntp gatling:test "-Dgatling.simulationClass=ru.otus.socialnetwork.load.$scenario" "-Dusers=$users" "-Dduration=$duration"
    } finally {
        Pop-Location
    }
}

Invoke-ReadRun UserGetSimulation 1 30
Invoke-ReadRun UserGetSimulation 100 60

Invoke-ReadRun UserSearchSimulation 1 30
Invoke-ReadRun UserSearchSimulation 100 60
```

Из двух 60-секундных прогонов запишите число успешных запросов, `OK RPS`, среднюю задержку, p95, p99 и число ошибок. Gatling выводит показатели в консоль и сохраняет HTML в `load-testing/gatling/target/gatling`.

## 3. Перенесите тот же набор данных на стенд с репликами

Запустите `primary`, снимите дамп одиночной базы и восстановите его в новом кластере. Выполните восстановление один раз для нового тома.

```powershell
New-Item -ItemType Directory -Path $reviewDir -Force | Out-Null
$dumpPath = "$reviewDir/baseline.dump"

docker compose @cluster up -d --wait primary
docker compose @baseline exec -T db pg_dump -Fc -U postgres -d social_network_load -f /tmp/reviewer-baseline.dump
docker compose @baseline cp db:/tmp/reviewer-baseline.dump $dumpPath
docker compose @cluster cp $dumpPath primary:/tmp/reviewer-baseline.dump
docker compose @cluster exec -T primary pg_restore -U postgres -d social_network_load --no-owner --single-transaction --exit-on-error /tmp/reviewer-baseline.dump
docker compose @cluster exec -T primary psql -U postgres -d social_network_load -v ON_ERROR_STOP=1 -c 'ANALYZE public.users'
```

Запустите обе реплики и убедитесь, что они получают WAL от primary. Затем проверьте роль каждого из трёх узлов и сверьте количество анкет.

```powershell
docker compose @cluster up -d --wait replica1 replica2
docker compose @cluster exec -T primary psql -U postgres -d social_network_load -c 'SELECT application_name, state, sync_state FROM pg_stat_replication ORDER BY application_name'

foreach ($node in @('primary', 'replica1', 'replica2')) {
    Write-Host $node
    docker compose @cluster exec -T $node psql -U postgres -d social_network_load -c 'SELECT pg_is_in_recovery(), count(*) FROM public.users'
}
```

В `pg_stat_replication` ожидаются две строки со значениями `streaming` и `async`. На каждом узле должно быть 1 000 000 анкет. `pg_is_in_recovery()` возвращает `f` на `primary` и `t` на обеих репликах.

Освободите порт `8880` и запустите приложение с профилем `replication`. Транзакции `readOnly = true` по очереди используют две реплики. Запись и Liquibase используют `primary`.

```powershell
docker compose @baseline stop app db
docker compose @cluster up -d --build app
docker compose @cluster logs -f app
```

Дождитесь `Started SocialNetworkApplication` и выйдите из логов через `Ctrl+C`.

Проверьте, что API возвращает анкету с ID `1`.

```powershell
Invoke-RestMethod 'http://localhost:8880/user/get/1' -TimeoutSec 10
```

## 4. Повторите замеры и проверьте маршрутизацию чтения

Снимите счётчики SQL на трёх узлах сразу после прогрева и сразу после каждого основного прогона.

```powershell
$readSql = @'
SELECT COALESCE(sum(calls) FILTER (WHERE query ILIKE '%FROM users WHERE id = %'), 0) AS get_calls,
       COALESCE(sum(calls) FILTER (WHERE query ILIKE '%WHERE first_name LIKE %'), 0) AS search_calls
FROM pg_stat_statements
WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
  AND query ILIKE 'SELECT id, first_name, second_name,%';
'@

function Show-ReadCalls {
    Write-Host 'Узел | get_calls | search_calls'
    foreach ($node in @('primary', 'replica1', 'replica2')) {
        $counts = docker compose @cluster exec -T $node psql -X -U postgres -d social_network_load -v ON_ERROR_STOP=1 -At -c $readSql
        if ($LASTEXITCODE -ne 0) { throw "Could not read SQL counters on $node" }
        Write-Host "$node | $counts"
    }
}

Invoke-ReadRun UserGetSimulation 1 30
Show-ReadCalls
Invoke-ReadRun UserGetSimulation 100 60
Show-ReadCalls

Invoke-ReadRun UserSearchSimulation 1 30
Show-ReadCalls
Invoke-ReadRun UserSearchSimulation 100 60
Show-ReadCalls
```

Ожидается нулевой прирост на `primary` и примерно равный прирост на обеих репликах. Сравните `OK RPS` и задержки с одиночным стендом.

## 5. Проверьте отказ primary

Соберите образ, в котором скрипт запускает Java-клиента, и скопируйте JDBC-драйвер. Имя Compose-проекта в команде сборки задаёт имя образа `hl-social-network-replication-app:latest`.

```powershell
docker compose -p hl-social-network-replication -f $replicationCompose build app
Invoke-MavenWrapper -B -ntp dependency:copy-dependencies -DincludeArtifactIds=postgresql "-DoutputDirectory=$reviewDir/jdbc"
$driver = Get-ChildItem -LiteralPath "$reviewDir/jdbc" -Filter 'postgresql-*.jar' -File | Select-Object -First 1
$driver.FullName
```

Выполните четыре команды по очереди. Каждый прогон создаёт отдельный кластер и запускает четыре JDBC-клиента. Скрипт аварийно останавливает `primary`, повышает реплику с самой свежей позицией WAL, подключает к ней вторую реплику и проверяет контрольную запись.

```powershell
& ./load-testing/replication/run-failover.ps1 -Mode async -JdbcJar $driver.FullName
& ./load-testing/replication/run-failover.ps1 -Mode quorum -JdbcJar $driver.FullName
& ./load-testing/replication/run-failover.ps1 -Mode async-gap -JdbcJar $driver.FullName
& ./load-testing/replication/run-failover.ps1 -Mode quorum-gap -JdbcJar $driver.FullName
```

В первых двух прогонах падает только `primary`. В двух прогонах с суффиксом `-gap` скрипт сначала отключает доставку WAL на обе реплики.

Каждый `summary.json` печатается в консоль. 
`LostConfirmed` считает подтверждённые клиентами UUID, которые отсутствуют на новом `primary`. 
`UnconfirmedPresent` показывает, сколько записей сохранилось при неопределённом исходе коммита для клиента. 
Для сравнения записи до отказа используйте `ConfirmedRps` и `CommitP95Ms`. 
Для проверки переключения — `Promoted`, `Reattached` и `WriteRecoverySeconds`.

После сверки скрипт можно остановить контейнеры, данные сохраняются.

```powershell
docker compose @cluster down
docker compose @baseline down
```
