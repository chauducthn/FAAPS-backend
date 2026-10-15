param([switch]$EnableGoogle)

$ErrorActionPreference = 'Stop'
$backendDirectory = Split-Path $PSScriptRoot -Parent
$localDirectory = Join-Path $env:LOCALAPPDATA 'FAAPS'
$bundlePath = Join-Path $localDirectory 'rds-global-bundle.pem'
$truststorePath = Join-Path $localDirectory 'rds-truststore-roots.p12'
$bundleUrl = 'https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem'

New-Item -ItemType Directory -Path $localDirectory -Force | Out-Null
if (-not (Test-Path $bundlePath)) {
    Invoke-WebRequest -Uri $bundleUrl -OutFile $bundlePath
}
if (-not (Test-Path $truststorePath)) {
    java (Join-Path $PSScriptRoot 'BuildRdsTruststore.java') $bundlePath $truststorePath
    if ($LASTEXITCODE -ne 0) { throw 'Could not build the RDS truststore' }
}

if (-not $env:DB_PASSWORD) {
    $password = Read-Host 'Enter the shared faaps_app database password' -AsSecureString
    $env:DB_PASSWORD = [Net.NetworkCredential]::new('', $password).Password
}
if ($EnableGoogle) {
    if (-not $env:SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_ID) {
        $env:SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_ID = Read-Host 'Google OAuth Web Client ID'
    }
    if (-not $env:SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_SECRET) {
        $googleSecret = Read-Host 'Google OAuth Client Secret' -AsSecureString
        $env:SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_SECRET = [Net.NetworkCredential]::new('', $googleSecret).Password
    }
}

$truststoreUrl = ([Uri]::new($truststorePath)).AbsoluteUri
$env:DB_URL = "jdbc:mysql://faaps-dev.crwkasygqczm.ap-southeast-1.rds.amazonaws.com:3306/faaps?sslMode=VERIFY_IDENTITY&trustCertificateKeyStoreUrl=$truststoreUrl&trustCertificateKeyStoreType=PKCS12&trustCertificateKeyStorePassword=changeit&serverTimezone=Asia/Ho_Chi_Minh"
$env:DB_USERNAME = 'faaps_app'

Push-Location $backendDirectory
try {
    mvn spring-boot:run
} finally {
    Pop-Location
    Remove-Item Env:DB_PASSWORD -ErrorAction SilentlyContinue
    if ($EnableGoogle) {
        Remove-Item Env:SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_SECRET -ErrorAction SilentlyContinue
    }
}
