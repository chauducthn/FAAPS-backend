param(
    [string]$Email = 'admin@faaps.local',
    [Security.SecureString]$AdminPassword
)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$envFile = Join-Path $root '.env'
if (-not (Test-Path -LiteralPath $envFile)) { throw 'Create a local .env first (see .env.example).' }

$settings = @{}
foreach ($line in Get-Content -LiteralPath $envFile) {
    if ($line -match '^\s*([A-Za-z_][A-Za-z_0-9]*)=(.*)$') {
        $settings[$matches[1]] = $matches[2].Trim().Trim('"', "'")
    }
}
$url = $settings['DB_URL']
if ($url -notmatch '^jdbc:mysql://(localhost|127\.0\.0\.1)(?::([0-9]{1,5}))?/([A-Za-z_][A-Za-z_0-9]*)(?:\?.*)?$') {
    throw 'DB_URL must point to localhost or 127.0.0.1. This script refuses remote databases.'
}
$port = if ($matches[2]) { [int]$matches[2] } else { 3306 }
$database = $matches[3]
if ($port -lt 1 -or $port -gt 65535) { throw 'Invalid MySQL port.' }
$dbUser = $settings['DB_USERNAME']
if ($dbUser -notmatch '^[A-Za-z_][A-Za-z_0-9]*$') { throw 'Set DB_USERNAME in .env.' }
if ($Email -notmatch '^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$') { throw 'Invalid Admin email.' }

$mysql = (Get-Command mysql.exe -ErrorAction SilentlyContinue).Source
if (-not $mysql) { $mysql = 'C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe' }
if (-not (Test-Path -LiteralPath $mysql)) { throw 'mysql.exe was not found. Install MySQL client or add it to PATH.' }
$crypto = Get-ChildItem (Join-Path $env:USERPROFILE '.m2\repository\org\springframework\security\spring-security-crypto') -Recurse -Filter 'spring-security-crypto-*.jar' -ErrorAction SilentlyContinue | Sort-Object FullName -Descending | Select-Object -First 1 -ExpandProperty FullName
if (-not $crypto) { throw 'Spring Security Maven dependencies are missing. Run mvn test once, then retry.' }
$logging = Get-ChildItem (Join-Path $env:USERPROFILE '.m2\repository\commons-logging\commons-logging') -Recurse -Filter 'commons-logging-*.jar' -ErrorAction SilentlyContinue | Sort-Object FullName -Descending | Select-Object -First 1 -ExpandProperty FullName
if (-not $logging) { throw 'Commons Logging Maven dependency is missing. Run mvn test once, then retry.' }
if (-not $AdminPassword) { $AdminPassword = Read-Host 'New local Admin password' -AsSecureString }
$plain = [Net.NetworkCredential]::new('', $AdminPassword).Password
if ([string]::IsNullOrWhiteSpace($plain)) { throw 'Admin password cannot be blank.' }
try {
    $hash = $plain | & java --class-path "$crypto;$logging" (Join-Path $PSScriptRoot 'AdminPasswordHash.java')
    if ($LASTEXITCODE -ne 0 -or $hash -notmatch '^\$2[aby]\$[0-9]{2}\$[./A-Za-z0-9]{53}$') { throw 'BCrypt generation failed.' }
} finally { $plain = $null }

$oldMysqlPassword = [Environment]::GetEnvironmentVariable('MYSQL_PWD', 'Process')
try {
    [Environment]::SetEnvironmentVariable('MYSQL_PWD', $settings['DB_PASSWORD'], 'Process')
    $args = @('--protocol=TCP', '--host=127.0.0.1', "--port=$port", "--user=$dbUser", '--batch', '--skip-column-names', "--database=$database")
    $preflight = @"
SELECT COUNT(*) FROM users u JOIN roles r ON r.id=u.role_id WHERE r.code='ADMIN';
SELECT COUNT(*) FROM users WHERE email='$Email' AND role_id NOT IN (SELECT id FROM roles WHERE code='ADMIN');
SELECT COUNT(*) FROM roles WHERE code='ADMIN';
"@
    $counts = @(& $mysql @args --execute=$preflight)
    if ($LASTEXITCODE -ne 0 -or $counts.Count -ne 3) { throw 'Could not inspect local MySQL schema. Run the project schema migration first.' }
    if ([int]$counts[0] -gt 1) { throw 'More than one Admin exists. Resolve duplicate Admin records manually before retrying.' }
    if ([int]$counts[1] -ne 0) { throw 'The requested email belongs to a non-Admin user.' }
    if ([int]$counts[2] -ne 1) { throw 'Exactly one ADMIN role must exist.' }
    $sql = @"
CREATE TABLE IF NOT EXISTS admin_account (
 singleton_id tinyint PRIMARY KEY,
 user_id char(36) NOT NULL UNIQUE,
 CONSTRAINT chk_admin_account_singleton CHECK (singleton_id=1),
 CONSTRAINT fk_admin_account_user FOREIGN KEY (user_id) REFERENCES users(id)
);
START TRANSACTION;
SET @admin_role=(SELECT id FROM roles WHERE code='ADMIN');
SET @admin_id=(SELECT u.id FROM users u WHERE u.role_id=@admin_role LIMIT 1);
INSERT INTO users (id,role_id,email,password_hash,auth_provider,full_name,status)
SELECT UUID(),@admin_role,'$Email','$hash','LOCAL','Admin','ACTIVE' FROM DUAL WHERE @admin_id IS NULL;
SET @admin_id=(SELECT u.id FROM users u WHERE u.role_id=@admin_role LIMIT 1);
UPDATE users SET email='$Email',password_hash='$hash',auth_provider='LOCAL',status='ACTIVE',failed_login_attempts=0,locked_until=NULL WHERE id=@admin_id;
INSERT INTO admin_account (singleton_id,user_id) VALUES (1,@admin_id)
ON DUPLICATE KEY UPDATE user_id=VALUES(user_id);
COMMIT;
SELECT COUNT(*) FROM admin_account a JOIN users u ON u.id=a.user_id JOIN roles r ON r.id=u.role_id WHERE a.singleton_id=1 AND r.code='ADMIN' AND u.email='$Email' AND u.status='ACTIVE';
"@
    $result = @(& $mysql @args --execute=$sql)
    if ($LASTEXITCODE -ne 0 -or $result[-1] -ne '1') { throw 'Local Admin setup failed. Inspect the MySQL error above.' }
    Write-Host "Configured exactly one local Admin: $Email"
} finally {
    [Environment]::SetEnvironmentVariable('MYSQL_PWD', $oldMysqlPassword, 'Process')
    $hash = $null
}
