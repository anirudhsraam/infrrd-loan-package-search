param(
    [string]$BaseUrl = "http://localhost:9090",
    [int]$WarmRepeats = 25
)

$ErrorActionPreference = "Stop"
if ($WarmRepeats -lt 1) {
    throw "WarmRepeats must be at least 1."
}

$query = [uri]::EscapeDataString('"cash to close"')
$uri = "$($BaseUrl.TrimEnd('/'))/api/search?q=$query&page=0&size=20"

function Measure-SearchRequest {
    param([string]$RequestUri)

    $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
    $response = Invoke-RestMethod -Method Get -Uri $RequestUri
    $stopwatch.Stop()
    [pscustomobject]@{
        ClientMs = $stopwatch.Elapsed.TotalMilliseconds
        ControllerMs = [double]$response.requestTotalMs
        CatalogMs = [double]$response.catalogAccessMs
        SearchHandlingMs = [double]$response.searchHandlingMs
        SearchEngineMs = [double]$response.timings.totalMs
        CacheHit = [bool]$response.timings.cacheHit
        SnapshotRebuilt = [bool]$response.snapshotRebuilt
        TotalHits = [int]$response.totalHits
    }
}

$cold = Measure-SearchRequest -RequestUri $uri
$warm = @()
for ($i = 0; $i -lt $WarmRepeats; $i++) {
    $warm += Measure-SearchRequest -RequestUri $uri
}

if ($warm | Where-Object { -not $_.CacheHit }) {
    throw "A repeated search did not hit the bounded query cache."
}
if (($warm | Where-Object { $_.TotalHits -ne $cold.TotalHits }).Count -gt 0) {
    throw "Repeated searches returned inconsistent hit counts."
}

function Get-Percentile {
    param([double[]]$Values, [double]$Percentile)
    $sorted = @($Values | Sort-Object)
    $index = [Math]::Ceiling($Percentile * $sorted.Count) - 1
    $sorted[[Math]::Max(0, $index)]
}

$clientTimes = [double[]]@($warm | ForEach-Object { $_.ClientMs })
$controllerTimes = [double[]]@($warm | ForEach-Object { $_.ControllerMs })
$catalogTimes = [double[]]@($warm | ForEach-Object { $_.CatalogMs })
$handlingTimes = [double[]]@($warm | ForEach-Object { $_.SearchHandlingMs })
$engineTimes = [double[]]@($warm | ForEach-Object { $_.SearchEngineMs })

[pscustomobject]@{
    BaseUrl = $BaseUrl
    Query = '"cash to close"'
    WarmRepeats = $WarmRepeats
    MatchingPages = $cold.TotalHits
    ColdClientMs = [Math]::Round($cold.ClientMs, 3)
    ColdControllerMs = [Math]::Round($cold.ControllerMs, 3)
    ColdCatalogMs = [Math]::Round($cold.CatalogMs, 3)
    ColdSearchHandlingMs = [Math]::Round($cold.SearchHandlingMs, 3)
    ColdSearchEngineMs = [Math]::Round($cold.SearchEngineMs, 3)
    SnapshotRebuiltOnColdRequest = $cold.SnapshotRebuilt
    WarmClientMedianMs = [Math]::Round((Get-Percentile $clientTimes 0.50), 3)
    WarmClientP95Ms = [Math]::Round((Get-Percentile $clientTimes 0.95), 3)
    WarmControllerMedianMs = [Math]::Round((Get-Percentile $controllerTimes 0.50), 3)
    WarmCatalogMedianMs = [Math]::Round((Get-Percentile $catalogTimes 0.50), 3)
    WarmSearchHandlingMedianMs = [Math]::Round((Get-Percentile $handlingTimes 0.50), 3)
    WarmSearchEngineMedianMs = [Math]::Round((Get-Percentile $engineTimes 0.50), 3)
    ClientSpeedup = if (($warm | Measure-Object -Property ClientMs -Average).Average -gt 0) {
        [Math]::Round($cold.ClientMs / (Get-Percentile $clientTimes 0.50), 2)
    } else { 0 }
}
