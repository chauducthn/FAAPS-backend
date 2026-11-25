<#
.SYNOPSIS
    FAAPS Local Database Tracker & Idempotent Seeder
.DESCRIPTION
    Tracks the current local MySQL database state across all core FAAPS tables.
    - If the database or schema tables are missing, automatically initializes the schema.
    - If any core table is empty (or if -Force is passed), seeds sample data using
      sql/2026-11-24_seed_local_if_empty.sql (100% idempotent WHERE NOT EXISTS).
    - If -TrackOnly is passed, only prints the current database row-count report.
.EXAMPLE
    .\scripts\Seed-Local-Data.ps1
    .\scripts\Seed-Local-Data.ps1 -TrackOnly
    .\scripts\Seed-Local-Data.ps1 -Force -DbUser root -DbPassword "your_password"
#>
param(
    [string]$DbHost = "",
    [int]$DbPort = 0,
    [string]$DbName = "",
    [string]$DbUser = "",
    [string]$DbPassword = "",
    [switch]$Force,
    [switch]$TrackOnly
)

$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path -Parent $PSScriptRoot
$EnvFile  = Join-Path $RepoRoot ".env"

# 1. Load defaults from .env if present
$envMap = @{}
if (Test-Path $EnvFile) {
    Get-Content $EnvFile | ForEach-Object {
        $line = $_.Trim()
        if ($line -and -not $line.StartsWith("#") -and $line.Contains("=")) {
            $parts = $line.Split("=", 2)
            $envMap[$parts[0].Trim()] = $parts[1].Trim().Trim('"').Trim("'")
        }
    }
}

# Parse JDBC URL from .env or environment if provided
$rawDbUrl = if ($envMap.ContainsKey("DB_URL")) { $envMap["DB_URL"] } elseif ($env:DB_URL) { $env:DB_URL } else { "" }
$parsedHost = "127.0.0.1"
$parsedPort = 3306
$parsedDb   = "faaps"
if ($rawDbUrl -match "jdbc:mysql://([^:/]+)(?::(\d+))?/([^?]+)") {
    $parsedHost = $Matches[1]
    if ($Matches[2]) { $parsedPort = [int]$Matches[2] }
    $parsedDb   = $Matches[3]
}

if (-not $DbHost)     { $DbHost     = $parsedHost }
if ($DbPort -le 0)    { $DbPort     = $parsedPort }
if (-not $DbName)     { $DbName     = $parsedDb }
if (-not $DbUser)     { $DbUser     = if ($envMap.ContainsKey("DB_USERNAME")) { $envMap["DB_USERNAME"] } elseif ($env:DB_USERNAME) { $env:DB_USERNAME } else { "faaps" } }
if (-not $DbPassword) { $DbPassword = if ($envMap.ContainsKey("DB_PASSWORD")) { $envMap["DB_PASSWORD"] } elseif ($env:DB_PASSWORD) { $env:DB_PASSWORD } else { "" } }

Write-Host "==============================================================" -ForegroundColor Cyan
Write-Host " FAAPS Local Database Tracker & Idempotent Seeder" -ForegroundColor Cyan
Write-Host "==============================================================" -ForegroundColor Cyan
Write-Host (" Target MySQL : {0}:{1}/{2} (user: {3})" -f $DbHost, $DbPort, $DbName, $DbUser)

# 2. Locate mysql CLI binary
$MysqlCmd = $null
if (Get-Command "mysql" -ErrorAction SilentlyContinue) {
    $MysqlCmd = (Get-Command "mysql").Source
} else {
    $commonPaths = @(
        "C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe",
        "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe",
        "C:\xampp\mysql\bin\mysql.exe",
        "C:\laragon\bin\mysql\mysql*\bin\mysql.exe"
    )
    foreach ($pattern in $commonPaths) {
        $found = Get-Item $pattern -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($found) {
            $MysqlCmd = $found.FullName
            break
        }
    }
}

# 3. Verify TCP connectivity to MySQL
$tcpClient = New-Object System.Net.Sockets.TcpClient
try {
    $asyncResult = $tcpClient.BeginConnect($DbHost, $DbPort, $null, $null)
    $connected = $asyncResult.AsyncWaitHandle.WaitOne(2500, $false)
    if (-not $connected -or -not $tcpClient.Connected) {
        Write-Host ""
        Write-Host ("[!] Cannot reach MySQL at {0}:{1} (connection refused / service stopped)." -f $DbHost, $DbPort) -ForegroundColor Yellow
        Write-Host "    -> Start your local MySQL service and re-run .\scripts\Seed-Local-Data.ps1" -ForegroundColor Yellow
        Write-Host "    -> Note: Spring Boot also includes LocalDatabaseSeeder.java which automatically" -ForegroundColor Green
        Write-Host "       tracks and seeds an empty database on backend startup!" -ForegroundColor Green
        exit 0
    }
} catch {
    Write-Host ""
    Write-Host ("[!] Cannot reach MySQL at {0}:{1}: {2}" -f $DbHost, $DbPort, $_.Exception.Message) -ForegroundColor Yellow
    Write-Host "    -> Note: Spring Boot also includes LocalDatabaseSeeder.java which automatically" -ForegroundColor Green
    Write-Host "       tracks and seeds an empty database on backend startup!" -ForegroundColor Green
    exit 0
} finally {
    $tcpClient.Close()
}

if (-not $MysqlCmd) {
    Write-Host "[!] 'mysql' CLI was not found in PATH. Spring Boot LocalDatabaseSeeder will auto-seed on startup." -ForegroundColor Yellow
    exit 0
}

function Invoke-MySqlQuery {
    param([string]$Sql, [string]$Database = "")
    $argsList = @("--host=$DbHost", "--port=$DbPort", "--user=$DbUser", "--batch", "--skip-column-names", "--default-character-set=utf8mb4")
    if ($DbPassword) { $argsList += "--password=$DbPassword" }
    if ($Database)   { $argsList += "--database=$Database" }
    $argsList += @("-e", $Sql)
    return & $MysqlCmd @argsList 2>&1
}

function Invoke-MySqlFile {
    param([string]$FilePath, [string]$Database = "")
    $argsList = @("--host=$DbHost", "--port=$DbPort", "--user=$DbUser", "--default-character-set=utf8mb4")
    if ($DbPassword) { $argsList += "--password=$DbPassword" }
    if ($Database)   { $argsList += "--database=$Database" }
    Get-Content -Raw -Encoding UTF8 $FilePath | & $MysqlCmd @argsList
}

# 4. Track core tables
$TrackedTables = @(
    "roles",
    "users",
    "admin_account",
    "majors",
    "semesters",
    "curriculum_versions",
    "course_categories",
    "courses",
    "course_prerequisites",
    "curriculum_courses",
    "academic_policies",
    "advisors",
    "students",
    "student_advisor_assignments",
    "student_course_records",
    "semester_summaries",
    "attendance_default_config",
    "student_course_attendance",
    "academic_warnings",
    "study_plans",
    "advising_requests",
    "advising_sessions",
    "ai_configurations"
)

function Get-DatabaseSnapshot {
    $dbExists = Invoke-MySqlQuery -Sql "SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME = '$DbName';"
    if (-not $dbExists -or ($dbExists -match "ERROR")) {
        return @{ Exists = $false; Counts = @{} }
    }
    $counts = [ordered]@{}
    foreach ($tbl in $TrackedTables) {
        $tblExists = Invoke-MySqlQuery -Database $DbName -Sql "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = '$DbName' AND TABLE_NAME = '$tbl';"
        if ("$tblExists".Trim() -eq "1") {
            $cnt = Invoke-MySqlQuery -Database $DbName -Sql "SELECT COUNT(*) FROM `$tbl`;"
            $counts[$tbl] = [int]("$cnt".Trim())
        } else {
            $counts[$tbl] = -1
        }
    }
    return @{ Exists = $true; Counts = $counts }
}

function Show-Snapshot {
    param([string]$Title, [hashtable]$Snapshot)
    Write-Host ""
    Write-Host " $Title" -ForegroundColor Cyan
    Write-Host " ---------------------------------------------"
    Write-Host (" {0,-30} | {1,10}" -f "Table", "Row Count")
    Write-Host " ---------------------------------------------"
    foreach ($key in $Snapshot.Counts.Keys) {
        $val = $Snapshot.Counts[$key]
        $display = if ($val -lt 0) { "MISSING" } else { "$val" }
        $color = if ($val -le 0) { "Yellow" } else { "Green" }
        Write-Host (" {0,-30} | {1,10}" -f $key, $display) -ForegroundColor $color
    }
    Write-Host " ---------------------------------------------"
}

$before = Get-DatabaseSnapshot

if (-not $before.Exists) {
    Write-Host ("[!] Database '{0}' does not exist yet." -f $DbName) -ForegroundColor Yellow
} else {
    Show-Snapshot -Title "Current Local Database State (Before Check)" -Snapshot $before
}

if ($TrackOnly) {
    exit 0
}

# 5. Determine if schema or seed needs to run
$missingSchema = (-not $before.Exists) -or ($before.Counts.Values -contains -1)
$CoreRequiredTables = @("majors", "semesters", "curriculum_versions", "courses", "academic_policies", "advisors", "students")
$emptyCoreTables = @()
if ($before.Exists) {
    foreach ($coreTbl in $CoreRequiredTables) {
        if ($before.Counts[$coreTbl] -le 0) {
            $emptyCoreTables += $coreTbl
        }
    }
}

$SchemaFile     = Join-Path $RepoRoot "sql\faaps_schema_mysql84.sql"
$MigrationFile1 = Join-Path $RepoRoot "sql\2026-11-19_attendance_and_report_fixes.sql"
$MigrationFile2 = Join-Path $RepoRoot "sql\2026-11-25_course_material_link_and_appointment_summary.sql"
$SeedFile       = Join-Path $RepoRoot "sql\2026-11-24_seed_local_if_empty.sql"

if ($missingSchema) {
    Write-Host "[*] Missing database or tables detected. Applying schema & migrations..." -ForegroundColor Cyan
    if (-not $before.Exists) {
        Invoke-MySqlFile -FilePath $SchemaFile
    }
    if (Test-Path $MigrationFile1) {
        Invoke-MySqlFile -FilePath $MigrationFile1 -Database $DbName
    }
    if (Test-Path $MigrationFile2) {
        Invoke-MySqlFile -FilePath $MigrationFile2 -Database $DbName
    }
} elseif (Test-Path $MigrationFile2) {
    Invoke-MySqlFile -FilePath $MigrationFile2 -Database $DbName
}

if ($missingSchema -or $emptyCoreTables.Count -gt 0 -or $Force) {
    if ($Force) {
        Write-Host "[*] -Force specified: running idempotent seeder to fill any missing sample rows..." -ForegroundColor Cyan
    } else {
        Write-Host ("[*] Empty core tables detected ({0}). Seeding sample data..." -f ($emptyCoreTables -join ", ")) -ForegroundColor Cyan
    }
    Invoke-MySqlFile -FilePath $SeedFile -Database $DbName
    $after = Get-DatabaseSnapshot
    Show-Snapshot -Title "Local Database State (After Seeding)" -Snapshot $after
    Write-Host "[+] Local database seeding completed!" -ForegroundColor Green
} else {
    Write-Host ""
    Write-Host "[+] Existing data detected in all core tables. Skipping seed (pass -Force to top-up any missing sample records)." -ForegroundColor Green
}
