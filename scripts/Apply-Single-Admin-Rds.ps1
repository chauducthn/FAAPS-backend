param([Guid]$CurrentAdminId)

$ErrorActionPreference = 'Stop'
$backendDirectory = Split-Path $PSScriptRoot -Parent
$migration = Join-Path $backendDirectory 'sql\2026-10-21_single_active_admin_up.sql'
$mysql = 'C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe'
$certificate = Join-Path $env:LOCALAPPDATA 'FAAPS\rds-global-bundle.pem'
if (-not (Test-Path -LiteralPath $certificate)) {
    $certificate = Join-Path (Split-Path $backendDirectory -Parent) 'rds-global-bundle.pem'
}
foreach ($path in @($mysql, $certificate, $migration)) {
    if (-not (Test-Path -LiteralPath $path)) { throw "Required file is missing: $path" }
}

$password = Read-Host 'Enter the RDS master password (faaps_admin)' -AsSecureString
$env:MYSQL_PWD = [Net.NetworkCredential]::new('', $password).Password
$connection = @('--host=faaps-dev.crwkasygqczm.ap-southeast-1.rds.amazonaws.com', '--port=3306',
    '--user=faaps_admin', '--database=faaps', '--ssl-mode=VERIFY_IDENTITY', "--ssl-ca=$certificate", '--batch')
$temporaryFile = $null
try {
    & $mysql @connection '--execute=SELECT 1;'
    if ($LASTEXITCODE -ne 0) { throw 'RDS connection failed; no migration was run.' }
    & $mysql @connection "--execute=SELECT u.id, u.full_name, u.email, u.status FROM users u JOIN roles r ON r.id=u.role_id WHERE r.code='ADMIN' ORDER BY u.created_at;"
    if ($LASTEXITCODE -ne 0) { throw 'Could not list Admin accounts; no migration was run.' }
    $existing = & $mysql @connection '--skip-column-names' "--execute=SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='admin_control';"
    if ($LASTEXITCODE -ne 0) { throw 'Could not inspect the schema; no migration was run.' }
    if ([int]$existing -ne 0) { throw 'admin_control already exists; inspect it before retrying.' }

    $source = Get-Content -LiteralPath $migration -Raw
    if ($PSBoundParameters.ContainsKey('CurrentAdminId')) {
        $source = $source.Replace('SET @initial_admin_id = NULL;', "SET @initial_admin_id = '$CurrentAdminId';")
    }
    $temporaryFile = New-TemporaryFile
    [IO.File]::WriteAllText($temporaryFile.FullName, $source, [Text.UTF8Encoding]::new($false))
    $sourcePath = $temporaryFile.FullName.Replace('\', '/')
    & $mysql @connection '--abort-source-on-error' "--execute=SOURCE $sourcePath"
    if ($LASTEXITCODE -ne 0) { throw 'Migration failed; inspect admin_control before retrying.' }
    & $mysql @connection '--skip-column-names' '--execute=SELECT control_id, current_admin_id FROM admin_control;'
    if ($LASTEXITCODE -ne 0) { throw 'Migration ran but verification failed.' }
    $schemaState = & $mysql @connection '--skip-column-names' "--execute=SELECT (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='users' AND column_name='avatar_url') AS avatar_columns, (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='notifications' AND column_name='recipient_user_id') AS recipient_columns;"
    if ($LASTEXITCODE -ne 0 -or ($schemaState -join "`n") -notmatch '^0\s+1$') {
        throw 'Migration ran but avatar/notification schema verification failed.'
    }
    Write-Host 'Single-admin migration, avatar removal, and notification recipient normalization applied and verified.'
} finally {
    if ($temporaryFile) { Remove-Item -LiteralPath $temporaryFile.FullName -ErrorAction SilentlyContinue }
    Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
}
