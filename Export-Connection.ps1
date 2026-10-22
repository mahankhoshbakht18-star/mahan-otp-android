$ErrorActionPreference = 'Stop'
$values = @{}
foreach($line in Get-Content (Join-Path $PSScriptRoot '.private/relay.env')) {
    if($line -match '^([A-Z_]+)=(.*)$') { $values[$matches[1]] = $matches[2].Trim() }
}
if(!$values['RELAY_PHONE_KEY']) { throw 'Real phone key missing' }
$outputFile = Join-Path $PSScriptRoot '.private/Mahan-Connection.private.mahan'
@{endpoint='https://otp.mahanvip.ir/api/v1/sms/otp';device_key=$values['RELAY_PHONE_KEY']} | ConvertTo-Json | Set-Content -LiteralPath $outputFile -Encoding utf8
Write-Output 'Private phone connection file created. Transfer only to the authorized phone.'
