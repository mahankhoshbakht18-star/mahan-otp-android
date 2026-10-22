param(
    [string]$ApkPath = "$PSScriptRoot\app\build\outputs\apk\release\app-release.apk",
    [string]$Tag = 'v2.1.0'
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
    gh release create $Tag $ApkPath --repo $repository --target main --title 'ماهان ۲.۱.۰ — اتصال آماده و پشتیبانی' --notes 'اتصال آماده بدون انتخاب فایل؛ حفظ ظاهر ساده؛ پشتیبانی با اطلاعات تأییدشده، پرسش‌های متداول، گزارش تشخیصی و بررسی به‌روزرسانی. نصب نیازمند تأیید کاربر و مجوز پیامک است. فایل اتصال و کلید امضا در سورس مخزن قرار ندارند.'
    if ($LASTEXITCODE -ne 0) { throw 'GitHub Release publication failed.' }
} finally {
    if ($existingToken) { $env:GH_TOKEN = $existingToken }
    else { Remove-Item Env:GH_TOKEN -ErrorAction SilentlyContinue }
}
