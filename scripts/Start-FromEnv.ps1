$ErrorActionPreference = 'Stop'
$backendDirectory = Split-Path $PSScriptRoot -Parent
$envPath = Join-Path $backendDirectory '.env'
if (-not (Test-Path -LiteralPath $envPath)) { throw 'Copy .env.example to .env and fill in the private values first.' }

$allowed = @(
    'FAAPS_RDS_HOST', 'FAAPS_RDS_PORT', 'FAAPS_RDS_NAME', 'DB_USERNAME', 'DB_PASSWORD',
    'SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_ID',
    'SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_SECRET',
    'FAAPS_FRONTEND_URL'
)
$previous = @{}
try {
    foreach ($line in Get-Content -LiteralPath $envPath -Encoding utf8) {
        $entry = $line.Trim()
        if (-not $entry -or $entry.StartsWith('#')) { continue }
        $parts = $entry.Split('=', 2)
        if ($parts.Count -ne 2 -or $allowed -notcontains $parts[0]) { throw "Unsupported .env entry: $($parts[0])" }
        $name = $parts[0]
        if (-not $previous.ContainsKey($name)) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
        [Environment]::SetEnvironmentVariable($name, $parts[1], 'Process')
    }
    foreach ($name in @('DB_PASSWORD', 'SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_ID',
            'SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_SECRET')) {
        $value = [Environment]::GetEnvironmentVariable($name, 'Process')
        if (-not $value -or $value.StartsWith('replace-with-')) { throw "Fill in $name in .env before starting." }
    }
    & (Join-Path $PSScriptRoot 'Start-Rds.ps1') -EnableGoogle
} finally {
    foreach ($name in $previous.Keys) {
        [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process')
    }
}
