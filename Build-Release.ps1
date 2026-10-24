param([string]$JavaDirectory = 'C:\Users\amir\.jdks\ms-17.0.17', [switch]$SkipUnavailableLint, [switch]$IncludeQaProbe)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$connectionPath = if ($env:MAHAN_CONNECTION_FILE) { $env:MAHAN_CONNECTION_FILE } else { Join-Path $PSScriptRoot '.private\connection.json' }
if (!(Test-Path -LiteralPath $connectionPath)) { throw 'Customer release requires a private connection file; do not distribute a development build.' }
$connection = Get-Content -LiteralPath $connectionPath -Raw -Encoding UTF8 | ConvertFrom-Json
if ($connection.endpoint -ne 'https://otp.mahanvip.ir/api/v1/sms/otp' -or $connection.device_key.Length -lt 16) { throw 'Private connection file is invalid.' }
$env:JAVA_HOME = $JavaDirectory
$env:Path = "$JavaDirectory\bin;$env:Path"
$signingDirectory = Join-Path $PSScriptRoot '.signing'
New-Item -ItemType Directory -Path $signingDirectory -Force | Out-Null
$env:MAHAN_KEYSTORE = Join-Path $signingDirectory 'mahan-release.jks'
$credentialFile = Join-Path $signingDirectory 'credentials.xml'
$env:MAHAN_KEY_ALIAS = 'mahan-release'
if (!(Test-Path -LiteralPath $env:MAHAN_KEYSTORE)) {
    if (Test-Path -LiteralPath $credentialFile) { throw 'Signing key missing. Restore the original keystore; do not rotate silently.' }
    $randomBytes = New-Object byte[] 32
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $rng.GetBytes($randomBytes)
    $rng.Dispose()
    $env:MAHAN_STORE_PASSWORD = [Convert]::ToBase64String($randomBytes)
    $env:MAHAN_KEY_PASSWORD = $env:MAHAN_STORE_PASSWORD
    $credential = New-Object System.Management.Automation.PSCredential('mahan-release', (ConvertTo-SecureString $env:MAHAN_STORE_PASSWORD -AsPlainText -Force))
    $credential | Export-Clixml -LiteralPath $credentialFile
    & "$JavaDirectory\bin\keytool.exe" -genkeypair -keystore $env:MAHAN_KEYSTORE -storetype JKS -alias $env:MAHAN_KEY_ALIAS -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Mahan OTP Android' -storepass:env MAHAN_STORE_PASSWORD -keypass:env MAHAN_KEY_PASSWORD
    if ($LASTEXITCODE -ne 0) { throw 'Signing key generation failed' }
} else {
    $credential = Import-Clixml -LiteralPath $credentialFile
    $env:MAHAN_STORE_PASSWORD = $credential.GetNetworkCredential().Password
    $env:MAHAN_KEY_PASSWORD = $env:MAHAN_STORE_PASSWORD
}
try {
    $buildArguments = @(':app:testDebugUnitTest', ':app:assembleRelease', ':app:bundleRelease', '--no-daemon', '--console=plain')
    if ($IncludeQaProbe) { $buildArguments += ':qa:assembleRelease' }
    if ($SkipUnavailableLint) {
        Write-Warning 'Lint is being skipped explicitly due to unavailable dependencies. This is not a passed Lint check.'
        $buildArguments += @('-x', 'lintVitalAnalyzeRelease', '-x', 'lintVitalReportRelease', '-x', 'lintVitalRelease', '--offline')
    }
    & .\gradlew.bat @buildArguments
    if ($LASTEXITCODE -ne 0) { throw 'Release build failed; inspect Gradle output.' }
} finally {
    Remove-Item Env:MAHAN_STORE_PASSWORD,Env:MAHAN_KEY_PASSWORD -ErrorAction SilentlyContinue
}
