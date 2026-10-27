param(
    [Parameter(Mandatory=$true)][string]$UnsignedApk,
    [string]$OutputApk = "$PSScriptRoot\staging-artifacts\MAHANBOT-Android-release.apk"
)
$ErrorActionPreference = 'Stop'
$java = 'C:\Users\amir\.jdks\ms-17.0.17\bin\java.exe'
$python = 'C:\Users\amir\AppData\Local\Programs\Python\Python312\python.exe'
$buildTools = 'C:\Users\amir\AppData\Local\Android\Sdk\build-tools\35.0.0'
$working = Join-Path $PSScriptRoot '.private\ci-signing'
New-Item -ItemType Directory -Force -Path $working,(Split-Path $OutputApk -Parent) | Out-Null
$prepared = Join-Path $working 'connection-injected-unsigned.apk'
$aligned = Join-Path $working 'aligned-unsigned.apk'
& $python "$PSScriptRoot\qa\prepare_unsigned_apk.py" $UnsignedApk $prepared "$PSScriptRoot\.private\connection.json"
if ($LASTEXITCODE -ne 0) { throw 'Connection preparation failed' }
& "$buildTools\zipalign.exe" -f -p 4 $prepared $aligned
if ($LASTEXITCODE -ne 0) { throw 'APK alignment failed' }
$credential = Import-Clixml -LiteralPath "$PSScriptRoot\.signing\credentials.xml"
$env:MAHAN_STORE_PASSWORD = $credential.GetNetworkCredential().Password
$env:MAHAN_KEY_PASSWORD = $env:MAHAN_STORE_PASSWORD
try {
    & $java -jar "$buildTools\lib\apksigner.jar" sign --ks "$PSScriptRoot\.signing\mahan-release.jks" --ks-key-alias mahan-release --ks-pass env:MAHAN_STORE_PASSWORD --key-pass env:MAHAN_KEY_PASSWORD --out $OutputApk $aligned
    if ($LASTEXITCODE -ne 0) { throw 'Local release signing failed' }
    $verification = & $java -jar "$buildTools\lib\apksigner.jar" verify --verbose --print-certs $OutputApk
    if ($LASTEXITCODE -ne 0 -or !($verification -match 'certificate SHA-256 digest: aa151db817e67e44f31840b3deb0e5b146723d0586097dd1096dda33c3f20259')) { throw 'Original signature verification failed' }
    $verification
    $badging = & "$buildTools\aapt.exe" dump badging $OutputApk
    if ($LASTEXITCODE -ne 0 -or !($badging -match "package: name='(com\.example\.codresan|ir\.mahanvip\.otp)' versionCode='5' versionName='2.2.0'") -or ($badging -match 'application-debuggable')) { throw 'Wrong APK identity or debug build' }
    Get-FileHash -LiteralPath $OutputApk -Algorithm SHA256
} finally {
    Remove-Item Env:MAHAN_STORE_PASSWORD,Env:MAHAN_KEY_PASSWORD -ErrorAction SilentlyContinue
}
