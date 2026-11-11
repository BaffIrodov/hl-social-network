#Requires -Version 7

param(
    [Parameter(Mandatory)]
    [ValidateSet('async', 'quorum', 'async-gap', 'quorum-gap')]
    [string]$Mode,
    [Parameter(Mandatory)]
    [string]$JdbcJar,
    [string]$JavaHome = $env:JAVA_HOME,
    [ValidateRange(1, 16)]
    [int]$Clients = 4,
    [ValidateRange(5, 30)]
    [int]$MeasurementSeconds = 10
)

$ErrorActionPreference = 'Stop'
$jdbc = (Resolve-Path -LiteralPath $JdbcJar).Path
$javac = if ($JavaHome) { Join-Path $JavaHome 'bin/javac' } else { 'javac' }
if ($IsWindows -and $JavaHome) { $javac += '.exe' }
$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$project = "hl-social-network-failover-$Mode-$(Get-Date -Format 'yyyyMMddHHmmssfff')"
$composeArgs = @('-p', $project, '-f', (Join-Path $PSScriptRoot 'compose.yaml'))
$output = Join-Path $root "target/replication-failover/$project"
$clientClasses = Join-Path $output 'classes'
$clientDriver = Join-Path $output 'postgresql.jar'
$clientName = "$project-client"
$clientImage = 'hl-social-network-replication-app:latest'
$clientOutput = '/tmp/write-probe'
$replicas = @('replica1', 'replica2')
$useQuorum = $Mode.StartsWith('quorum')
$disconnectWal = $Mode.EndsWith('-gap')
$clientCreated = $false
$standStarted = $false

function Invoke-Docker {
    docker @args
    if ($LASTEXITCODE -ne 0) { throw "Docker exited with code $LASTEXITCODE" }
}

function Invoke-LabSql([string]$node, [string]$sql) {
    Invoke-Docker compose @composeArgs exec -T $node psql -X -U postgres -d social_network_load -v ON_ERROR_STOP=1 -At -c $sql
}

function Wait-For([scriptblock]$condition, [string]$description) {
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        if (& $condition) { return }
        Start-Sleep -Seconds 1
    }
    throw "Timed out waiting for $description"
}

function Set-SynchronousStandbys([string]$node, [string]$names) {
    Invoke-LabSql $node "ALTER SYSTEM SET synchronous_standby_names = '$names'" | Out-Null
    Invoke-LabSql $node 'SELECT pg_reload_conf()' | Out-Null
}

New-Item -ItemType Directory -Path $clientClasses | Out-Null
Copy-Item -LiteralPath $jdbc -Destination $clientDriver
& $javac --release 21 -d $clientClasses (Join-Path $PSScriptRoot 'WriteProbe.java')
if ($LASTEXITCODE -ne 0) { throw 'WriteProbe compilation failed' }
if (-not $IsWindows) {
    # Docker copies the input files as root. The Java client runs as app.
    chmod -R 'a+rX' $clientClasses $clientDriver
    if ($LASTEXITCODE -ne 0) { throw 'Could not make Java client files readable' }
}
Invoke-Docker image inspect $clientImage --format '{{.Id}}' | Out-Null

try {
    Write-Host "Starting $project"
    $standStarted = $true
    Invoke-Docker compose @composeArgs up -d --wait --wait-timeout 55 primary replica1 replica2
    $primaryId = Invoke-Docker compose @composeArgs ps -q primary
    $primaryLabels = Invoke-Docker inspect --format '{{index .Config.Labels "com.docker.compose.project"}}|{{index .Config.Labels "com.docker.compose.service"}}' $primaryId
    if ($primaryLabels -ne "$project|primary") { throw 'Unexpected crash target' }

    $streamCount = "SELECT count(*) FROM pg_stat_replication WHERE state = 'streaming'"
    Wait-For { (Invoke-LabSql 'primary' $streamCount) -eq '2' } 'both WAL streams'
    $connections = @{}
    foreach ($node in $replicas) { $connections[$node] = Invoke-LabSql $node 'SHOW primary_conninfo' }
    if ($useQuorum) { Set-SynchronousStandbys 'primary' 'ANY 1 (replica1, replica2)' }
    $syncState = if ($useQuorum) { 'quorum' } else { 'async' }
    $modeQuery = "SELECT count(*) FROM pg_stat_replication WHERE state = 'streaming' AND sync_state = '$syncState'"
    Wait-For { (Invoke-LabSql 'primary' $modeQuery) -eq '2' } "both replicas in $syncState mode"
    if ((Invoke-LabSql 'primary' 'SHOW synchronous_commit') -ne 'on' -or (Invoke-LabSql 'primary' 'SHOW fsync') -ne 'on') {
        throw 'The experiment requires synchronous_commit and fsync on'
    }
    Invoke-LabSql 'primary' 'SELECT application_name, state, sync_state FROM pg_stat_replication ORDER BY application_name' |
        Set-Content -LiteralPath (Join-Path $output 'replication-before.txt') -Encoding utf8
    Invoke-LabSql 'primary' 'CREATE TABLE replication_write_probe (id uuid PRIMARY KEY)' | Out-Null

    Invoke-Docker create --name $clientName --network "${project}_default" --entrypoint java $clientImage `
        -cp /tmp/probe-classes:/tmp/postgresql.jar WriteProbe `
        'jdbc:postgresql://primary:5432/social_network_load?connectTimeout=5&socketTimeout=20' $clientOutput $Clients | Out-Null
    $clientCreated = $true
    Invoke-Docker cp $clientClasses "${clientName}:/tmp/probe-classes"
    Invoke-Docker cp $clientDriver "${clientName}:/tmp/postgresql.jar"
    Invoke-Docker start $clientName | Out-Null
    Wait-For { (Invoke-Docker exec $clientName /bin/sh -c 'if [ -f "$1" ]; then echo ready; fi' sh "$clientOutput/ready") -eq 'ready' } 'JDBC clients'
    Start-Sleep -Seconds 3
    $measureFrom = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    Write-Host "Measuring $Clients writers for $MeasurementSeconds seconds"
    Start-Sleep -Seconds $MeasurementSeconds
    $measureTo = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()

    $gapFrom = $null
    $waitingForQuorum = 0
    if ($disconnectWal) {
        foreach ($node in $replicas) {
            Invoke-LabSql $node "ALTER SYSTEM SET primary_conninfo = ''" | Out-Null
            Invoke-LabSql $node 'SELECT pg_reload_conf()' | Out-Null
            Wait-For { (Invoke-LabSql $node 'SELECT count(*) FROM pg_stat_wal_receiver') -eq '0' } "$node WAL receiver disconnect"
            if ($node -eq 'replica1') { Start-Sleep -Seconds 1 }
        }
        Wait-For { (Invoke-LabSql 'primary' $streamCount) -eq '0' } 'both WAL streams to stop'
        $gapFrom = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
        Write-Host 'Both WAL streams are disconnected. Writers continue.'
        Start-Sleep -Seconds 2
        if ($useQuorum) {
            $waitQuery = "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event = 'SyncRep'"
            Wait-For { (Invoke-LabSql 'primary' $waitQuery) -eq [string]$Clients } 'all writers to wait for quorum'
            $waitingForQuorum = $Clients
            Write-Host "$waitingForQuorum writers are waiting for quorum"
        }
    }

    if ((Invoke-Docker inspect --format '{{.State.Running}}' $clientName) -ne 'true') { throw 'Write client stopped before the crash' }
    Write-Host 'Sending SIGKILL to the experiment primary'
    $recovery = [System.Diagnostics.Stopwatch]::StartNew()
    Invoke-Docker kill --signal SIGKILL $primaryId | Out-Null
    $stopFile = Join-Path $output 'stop'
    New-Item -ItemType File -Path $stopFile | Out-Null
    Invoke-Docker cp $stopFile "${clientName}:$clientOutput/stop"
    if ((Invoke-Docker wait $clientName) -ne '0') { throw 'Write client failed to save its ledger' }

    $positions = foreach ($node in $replicas) {
        Wait-For { (Invoke-LabSql $node 'SELECT count(*) FROM pg_stat_wal_receiver') -eq '0' } "$node to finish receiving WAL"
        $row = (Invoke-LabSql $node @'
SELECT pg_last_wal_receive_lsn(), pg_last_wal_replay_lsn(),
       greatest(pg_last_wal_receive_lsn(), pg_last_wal_replay_lsn()) - '0/0'::pg_lsn,
       pg_last_wal_replay_lsn() - '0/0'::pg_lsn;
'@).Split('|')
        [pscustomobject]@{ Node = $node; Received = $row[0]; Replayed = $row[1]; WalBytes = [decimal]$row[2]; ReplayBytes = [decimal]$row[3] }
    }
    $ranked = @($positions | Sort-Object @{ Expression = 'WalBytes'; Descending = $true }, @{ Expression = 'ReplayBytes'; Descending = $true }, Node)
    $leader = $ranked[0].Node
    $follower = $ranked[1].Node
    Write-Host "Promoting $leader with WAL at $($ranked[0].Received)"
    if ((Invoke-LabSql $leader 'SELECT pg_promote(true, 20)') -ne 't') { throw 'Promotion did not finish' }
    $promotionSeconds = $recovery.Elapsed.TotalSeconds

    $oldConnection = $connections[$follower]
    $newConnection = [regex]::Replace($oldConnection, '\bhost=primary(?=\s|$)', "host=$leader")
    if ($newConnection -eq $oldConnection) { throw 'Could not replace the replication host' }
    $quotedConnection = $newConnection.Replace("'", "''")
    Invoke-LabSql $follower "ALTER SYSTEM SET primary_conninfo = '$quotedConnection'" | Out-Null
    Invoke-LabSql $follower 'SELECT pg_reload_conf()' | Out-Null
    Wait-For { (Invoke-LabSql $leader $streamCount) -eq '1' } 'the follower on the new primary'
    if ($useQuorum) {
        Set-SynchronousStandbys $leader "ANY 1 ($follower)"
        Wait-For { (Invoke-LabSql $leader $modeQuery) -eq '1' } 'the new primary quorum'
    }

    $marker = '00000000-0000-0000-0000-000000000000'
    Invoke-LabSql $leader "INSERT INTO replication_write_probe VALUES ('$marker')" | Out-Null
    $recovery.Stop()
    Wait-For { (Invoke-LabSql $follower "SELECT EXISTS (SELECT 1 FROM replication_write_probe WHERE id = '$marker')") -eq 't' } 'the control write on the follower'

    $actual = [System.Collections.Generic.HashSet[string]]::new()
    $rows = Invoke-LabSql $leader "SELECT id FROM replication_write_probe WHERE id <> '$marker' ORDER BY id"
    $rows | Set-Content -LiteralPath (Join-Path $output 'new-primary-ids.txt') -Encoding utf8
    foreach ($id in $rows) { [void]$actual.Add($id) }
    Invoke-Docker cp "${clientName}:$clientOutput/." $output
    $events = @(Get-ChildItem -LiteralPath $output -Filter 'client-*.csv' -File | ForEach-Object { Import-Csv -LiteralPath $_.FullName })
    $acknowledged = @($events | Where-Object outcome -eq 'confirmed')
    $unconfirmed = @($events | Where-Object outcome -eq 'unconfirmed')
    $lost = @($acknowledged | Where-Object { -not $actual.Contains($_.id) })
    $lost | ForEach-Object id | Set-Content -LiteralPath (Join-Path $output 'lost-confirmed-ids.txt') -Encoding utf8
    $window = @($acknowledged | Where-Object { [long]$_.finished_ms -ge $measureFrom -and [long]$_.finished_ms -lt $measureTo })
    if ($window.Count -eq 0) { throw 'No confirmed writes in the measurement window' }
    $latencies = @($window | ForEach-Object { [long]$_.latency_us } | Sort-Object)
    $p95 = $latencies[[int][Math]::Ceiling(0.95 * $latencies.Count) - 1] / 1000
    $confirmedInGap = if ($null -ne $gapFrom) { @($acknowledged | Where-Object { [long]$_.finished_ms -ge $gapFrom }).Count } else { 0 }

    $summary = [pscustomobject]@{
        Mode = $Mode
        Project = $project
        Clients = $Clients
        WindowSeconds = ($measureTo - $measureFrom) / 1000
        WindowConfirmed = $window.Count
        ConfirmedRps = [Math]::Round($window.Count * 1000 / ($measureTo - $measureFrom), 2)
        CommitP95Ms = $p95
        Confirmed = $acknowledged.Count
        Unconfirmed = $unconfirmed.Count
        UnconfirmedPresent = @($unconfirmed | Where-Object { $actual.Contains($_.id) }).Count
        NewPrimaryRows = $actual.Count
        LostConfirmed = $lost.Count
        ConfirmedAfterWalDisconnect = $confirmedInGap
        ClientsWaitingForQuorum = $waitingForQuorum
        Promoted = $leader
        Reattached = $follower
        WalPositions = $positions
        PromotionSeconds = [Math]::Round($promotionSeconds, 2)
        WriteRecoverySeconds = [Math]::Round($recovery.Elapsed.TotalSeconds, 2)
    }
    $summary | ConvertTo-Json -Depth 4 | Tee-Object -FilePath (Join-Path $output 'summary.json')
} finally {
    if ($clientCreated) {
        docker logs $clientName 2>&1 | Set-Content -LiteralPath (Join-Path $output 'client.log') -Encoding utf8
        docker rm -f $clientName | Out-Null
    }
    if ($standStarted) { docker compose @composeArgs stop primary replica1 replica2 }
}
