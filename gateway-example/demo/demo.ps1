param(
    [ValidateSet('offline', 'runtime')]
    [string]$Mode = 'offline',
    [switch]$KeepServices
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$providerModule = Join-Path $repoRoot 'gateway-example\test-provider'
$cliModule = Join-Path $repoRoot 'gateway-manifest-cli'
$workRoot = Join-Path $PSScriptRoot '.work'
$descriptor = Join-Path $providerModule 'target\classes\META-INF\ai-gateway\capabilities.json'
$schemas = Join-Path $providerModule 'target\classes'
$governance = Join-Path $providerModule 'src\main\resources\manifest\governance.yml'
$profile = Join-Path $providerModule 'src\main\resources\manifest\environment-profile.yml'
$out = Join-Path $workRoot 'manifests'
$report = Join-Path $workRoot 'generation-report.json'
$manifest = Join-Path $out 'order.detail.query.json'
$compose = Join-Path $PSScriptRoot 'docker-compose.yml'
$gatewayUrl = if ($env:DEMO_GATEWAY_URL) { $env:DEMO_GATEWAY_URL } else { 'http://localhost:8080' }
$token = 'demo-token'

function Invoke-Maven([string[]]$Arguments) {
    Push-Location $repoRoot
    try {
        & mvn @Arguments
        if ($LASTEXITCODE -ne 0) { throw "Maven failed with exit code $LASTEXITCODE" }
    } finally {
        Pop-Location
    }
}

function Invoke-HttpJson([string]$Method, [string]$Uri, [object]$Body = $null) {
    $headers = @{ Authorization = "Bearer $token" }
    $params = @{ Method = $Method; Uri = $Uri; Headers = $headers; ContentType = 'application/json' }
    if ($null -ne $Body) { $params.Body = ($Body | ConvertTo-Json -Depth 20 -Compress) }
    return Invoke-RestMethod @params
}

function Assert-Equal([object]$Actual, [object]$Expected, [string]$Message) {
    if ($Actual -ne $Expected) { throw "$Message. Actual: $Actual; expected: $Expected" }
}

function Build-Artifacts {
    Write-Host 'Building the annotation processor, provider, Manifest CLI, and gateway...'
    Invoke-Maven @('-q', '-pl', 'gateway-example/test-provider,gateway-manifest-cli,gateway-bootstrap', '-am', 'package', '-DskipTests')
    if (-not (Test-Path $descriptor)) { throw "Generated descriptor not found: $descriptor" }
}

function Generate-Manifest {
    New-Item -ItemType Directory -Force -Path $workRoot, $out | Out-Null
    $cpFile = Join-Path $cliModule 'target\demo-classpath.txt'
    Invoke-Maven @('-q', '-pl', 'gateway-manifest-cli', 'dependency:build-classpath', "-Dmdep.outputFile=$cpFile", '-Dmdep.includeScope=runtime')
    $classpath = "$($cliModule)\target\classes;$(Get-Content $cpFile -Raw)"
    & java '-cp' $classpath 'com.ai.gateway.cli.ManifestCli' 'generate' '--descriptor' $descriptor '--schemas' $schemas '--governance' $governance '--profile' $profile '--out' $out '--report' $report
    if ($LASTEXITCODE -ne 0) { throw 'Manifest Draft generation failed' }
    & java '-cp' $classpath 'com.ai.gateway.cli.ManifestCli' 'validate' '--manifest' $manifest
    if ($LASTEXITCODE -ne 0) { throw 'Manifest Draft validation failed' }
    Write-Host "Generated and validated: $manifest"
}

function Run-Offline {
    Build-Artifacts
    Generate-Manifest
    Write-Host 'Offline demo completed. Runtime services were not started.'
}

function Wait-Gateway {
    for ($i = 0; $i -lt 60; $i++) {
        try { Invoke-RestMethod "$gatewayUrl/actuator/health" | Out-Null; return } catch { Start-Sleep -Seconds 2 }
    }
    throw "Gateway did not become ready: $gatewayUrl"
}

function Invoke-DemoTool([string]$CapabilityId, [string]$Version) {
    $body = @{ requestId = 'demo-order-001'; version = $Version; arguments = @{ orderNo = 'DEMO-1001' }; locale = 'zh-CN' }
    for ($i = 0; $i -lt 15; $i++) {
        try { return Invoke-HttpJson 'Post' "$gatewayUrl/api/v1/tools/${CapabilityId}:invoke" $body } catch { Start-Sleep -Seconds 2 }
    }
    throw 'Provider did not become ready for invocation'
}

function Run-Runtime {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Docker is required for runtime mode' }
    Build-Artifacts
    Generate-Manifest
    Push-Location $PSScriptRoot
    & docker compose -f $compose up -d
    if ($LASTEXITCODE -ne 0) { Pop-Location; throw 'Docker Compose startup failed' }
    try {
        Wait-Gateway
        $import = Invoke-HttpJson 'Post' "$gatewayUrl/admin/v1/manifests:import" (Get-Content $manifest -Raw | ConvertFrom-Json)
        Assert-Equal $import.status 'IMPORTED' 'Manifest import failed'
        $id = 'order.detail.query'; $version = '1.0.0'
        $validated = Invoke-HttpJson 'Post' "$gatewayUrl/admin/v1/capabilities/${id}/versions/${version}:validate"
        if ($validated.status -notin @('VALIDATED', 'APPROVED')) { throw 'Manifest validation failed' }
        $approved = Invoke-HttpJson 'Post' "$gatewayUrl/admin/v1/capabilities/${id}/versions/${version}:approve" @{}
        Assert-Equal $approved.status 'APPROVED' 'Manifest approval failed'
        $published = Invoke-HttpJson 'Post' "$gatewayUrl/admin/v1/releases:publish" @{ environment = 'demo'; capabilities = @(@{ capabilityId = $id; version = $version }) }
        Assert-Equal $published.status 'PUBLISHED' 'Catalog publication failed'
        $result = Invoke-DemoTool $id $version
        Assert-Equal $result.status 'COMPLETED' 'Runtime invocation failed'
        Assert-Equal $result.data.data.orderNo 'DEMO-1001' 'Provider returned an unexpected order number'
        if ($result.data.data.customerName -eq 'Test Customer') { throw 'Projection or redaction did not run' }
        Write-Host ($result | ConvertTo-Json -Depth 20)
        Write-Host 'Runtime demo completed. Provider data, projection, and redaction were verified.'
    } finally {
        Pop-Location
        if (-not $KeepServices) { & docker compose -f $compose down -v | Out-Host }
    }
}

if ($Mode -eq 'offline') { Run-Offline } else { Run-Runtime }
