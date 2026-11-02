<#
.SYNOPSIS
  Executes the 100 mock users seed script into local or remote MySQL database.
.PARAMETER Host
  MySQL host (default: localhost)
.PARAMETER Port
  MySQL port (default: 3306)
.PARAMETER Database
  Database name (default: faaps)
.PARAMETER User
  MySQL username (default: faaps)
#>
param(
    [string]$HostName = "localhost",
    [int]$Port = 3306,
    [string]$Database = "faaps",
    [string]$User = "faaps",
    [string]$Password = $env:DB_PASSWORD
)

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$sqlFile = Join-Path (Split-Path -Parent $scriptDir) "sql\2026-10-31_seed_100_mock_users.sql"

if (-not (Test-Path $sqlFile)) {
    Write-Error "Seed file not found: $sqlFile"
    exit 1
}

Write-Host "Seeding 100 Mock Users into $Database at ${HostName}:${Port}..." -ForegroundColor Cyan

$mysqlCmd = "mysql"
$args = @(
    "-h", $HostName,
    "-P", $Port,
    "-u", $User,
    "-D", $Database
)

if ($Password) {
    $args += "-p$Password"
}

Get-Content $sqlFile | & $mysqlCmd @args

if ($LASTEXITCODE -eq 0) {
    Write-Host "Successfully seeded 100 mock users!" -ForegroundColor Green
} else {
    Write-Error "MySQL execution failed with code $LASTEXITCODE"
}
