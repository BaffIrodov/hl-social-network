# Проверка ленты и её кеша

Далее представлена пошаговая инструкция по проверке работы кеширования в ленте постов.

Запустите Docker с Compose. Выполняйте команды по порядку из корня репозитория
в одном окне PowerShell. На Linux и macOS откройте PowerShell командой `pwsh`.
Для стенда нужны свободные порты `5432`, `6379` и `8880`.

## 1. Запустите чистый стенд

```powershell
$review = 'hl-feed-review-' + (Get-Date -Format 'yyyyMMddHHmmss')
$api = 'http://localhost:8880'
docker compose -p "$review" up --build -d
docker compose -p "$review" logs -f app
```

Дождитесь строки `Started SocialNetworkApplication` и нажмите Ctrl+C, 
чтобы выйти из просмотра логов.

## 2. Загрузите тестовые данные

```powershell
docker compose -p "$review" cp src/test/resources/db/seed-feed.sql db:/tmp/seed-feed.sql
docker compose -p "$review" exec -T db psql -U postgres -d social_network -f /tmp/seed-feed.sql
```

Предполагается, что запускаемый стенд чистый, и не содержит записей. 
После одного запуска скрипта появятся четыре анкеты. 
Vera Sokolova (`id = 1`) добавила Maria Volkova (`id = 2`) и Ilya Orlov (`id = 3`). 
В её ленте должно быть три поста этих авторов. 
Также есть ещё собственный пост Vera и пост Oleg Lebedev (`id = 4`), который не добавлен в список друзей.
Эти посты позволяют проверить, какие авторы попадают в ленту - не видит ли пользователей посты друзей или собственные.

## 3. Войдите и посмотрите ленту Vera

```powershell
$password = 'ExamplePassword123!'
$veraBody = @{ id = '1'; password = $password } | ConvertTo-Json
$mariaBody = @{ id = '2'; password = $password } | ConvertTo-Json

$veraLogin = Invoke-RestMethod -Method Post -Uri "$api/login" -ContentType 'application/json' -Body $veraBody
$mariaLogin = Invoke-RestMethod -Method Post -Uri "$api/login" -ContentType 'application/json' -Body $mariaBody

$veraHeaders = @{ Authorization = "Bearer $($veraLogin.token)" }
$mariaHeaders = @{ Authorization = "Bearer $($mariaLogin.token)" }
$feedUrl = "$api/post/feed?offset=0&limit=10"

$feed = Invoke-RestMethod -Method Get -Uri $feedUrl -Headers $veraHeaders
$feed | Format-Table author_user_id, text -AutoSize -Wrap
```

Ожидаются три поста, отсортированные по новизне. 
ID авторов идут в порядке `2`, `3`, `2`.
Первый пост — `I found a new coffee shop near home.`

## 4. Создайте пост от Maria

```powershell
$postBody = @{ text = 'Hello from Maria!' } | ConvertTo-Json
$newPostId = Invoke-RestMethod -Method Post -Uri "$api/post/create" -Headers $mariaHeaders -ContentType 'application/json' -Body $postBody
```

Обновление ленты происходит в фоне и занимает небольшое время. Повторяйте следующий блок, пока новый пост
с ID `$newPostId` не появится первым. Всего должно стать четыре поста.

```powershell
$feed = Invoke-RestMethod -Method Get -Uri $feedUrl -Headers $veraHeaders
$feed | Format-Table id, author_user_id, text -AutoSize -Wrap
```

## 5. Удалите новый пост

```powershell
Invoke-RestMethod -Method Put -Uri "$api/post/delete/$newPostId" -Headers $mariaHeaders
```

Повторяйте проверку ниже до обновления ленты. Ожидаются `3` и `False`.

```powershell
$feed = Invoke-RestMethod -Method Get -Uri $feedUrl -Headers $veraHeaders
$feed.Count
$feed.id -contains $newPostId
```

## 6. Проверьте восстановление ленты из PostgreSQL

Далее проверим работу кеширования.
Сохраните текущую ленту и удалите два ключа Redis для пользователя `1`.

```powershell
$feedBefore = Invoke-RestMethod -Method Get -Uri $feedUrl -Headers $veraHeaders
docker compose -p "$review" exec -T redis redis-cli DEL 'feed:{1}:order' 'feed:{1}:posts'
docker compose -p "$review" exec -T redis redis-cli EXISTS 'feed:{1}:order' 'feed:{1}:posts'
```

`DEL` должен вернуть `2`, а `EXISTS` — `0`. Повторный запрос ленты восстановит
посты из PostgreSQL и заполнит кеш.

```powershell
$feedAfter = Invoke-RestMethod -Method Get -Uri $feedUrl -Headers $veraHeaders
$beforeJson = ConvertTo-Json -InputObject $feedBefore -Compress
$afterJson = ConvertTo-Json -InputObject $feedAfter -Compress

$feedAfter.Count
$beforeJson -ceq $afterJson
docker compose -p "$review" exec -T redis redis-cli EXISTS 'feed:{1}:order' 'feed:{1}:posts'
```

Ожидаются `3`, `True` и `2`. Вернулись те же посты в том же порядке, а оба ключа
снова появились в Redis.

Когда проверка закончится, удалите контейнеры стенда и его том PostgreSQL.

```powershell
docker compose -p "$review" down -v
```
