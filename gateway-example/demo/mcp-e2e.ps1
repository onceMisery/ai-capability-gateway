param(
    [string]$GatewayUrl = $(if ($env:GATEWAY_URL) { $env:GATEWAY_URL } else { 'http://localhost:8080' }),
    [string]$Report = ''
)

$ErrorActionPreference = 'Stop'
$script = Join-Path $PSScriptRoot 'mcp-e2e.mjs'
if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
    throw 'Node.js is required to run the MCP E2E runner'
}

$env:GATEWAY_URL = $GatewayUrl
if ($Report) {
    $env:MCP_E2E_REPORT = $Report
}

& node $script
if ($LASTEXITCODE -ne 0) {
    throw "MCP E2E failed with exit code $LASTEXITCODE"
}
