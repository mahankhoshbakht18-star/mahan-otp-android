param(
    [string]$ApkPath = "$PSScriptRoot\app\build\outputs\apk\release\app-release.apk",
    [string]$Tag = 'v2.2.0-rc1',
    [switch]$ProductionApproved
)
$ErrorActionPreference = 'Stop'
if (!(Test-Path -LiteralPath $ApkPath)) { throw 'Signed release APK not found.' }
$repository = 'mahankhoshbakht18-star/mahan-otp-android'
# Reuse Git's credential helper without printing or persisting credentials.
$existingToken = $env:GH_TOKEN
try {
    if (!$env:GH_TOKEN) {
        $env:GCM_INTERACTIVE = 'Never'
        $credentialLines = "protocol=https`nhost=github.com`n`n" | git -c credential.interactive=never credential fill
        if ($LASTEXITCODE -ne 0) { throw 'Log in to GitHub before publishing.' }
        $passwordLine = $credentialLines | Where-Object { $_ -like 'password=*' } | Select-Object -First 1
        if (!$passwordLine) { throw 'No GitHub credential available.' }
        $env:GH_TOKEN = $passwordLine.Substring(9)
    }
    $releaseArguments = @('release','create',$Tag,$ApkPath,'--repo',$repository,'--target','feature/typed-otp-updater-20261024',
        '--title','ماهان ۲.۲.۰ — نامزد آزمایشی انتشار','--notes',
        'نامزد انتشار؛ برای مشتری عمومی نشده است. شامل تفکیک انواع کد ازدواج، تأیید کد بدون متن، کنترل درخواست حذف، بررسی هش و امضای آپدیت و مدیریت نسخه‌ها. انتشار عمومی نیازمند تست پایلوت و تأیید مالک است.')
    if (!$ProductionApproved) { $releaseArguments += '--draft' }
    else { throw 'Public publication must use the verified release approval workflow after pilot results; this helper only creates drafts.' }
    gh @releaseArguments
    if ($LASTEXITCODE -ne 0) { throw 'GitHub Release publication failed.' }
} finally {
    if ($existingToken) { $env:GH_TOKEN = $existingToken }
    else { Remove-Item Env:GH_TOKEN -ErrorAction SilentlyContinue }
}
