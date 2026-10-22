$ErrorActionPreference = 'Stop'
foreach($artifact in @('intellij-core','kotlin-compiler')) {
    $repositoryPath = "com/android/tools/external/com-intellij/$artifact/31.9.2"
    $directory = Join-Path $PSScriptRoot "build-support/maven/$repositoryPath"
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    foreach($extension in @('pom','jar')) {
        $file = Join-Path $directory "$artifact-31.9.2.$extension"
        if(!(Test-Path -LiteralPath $file) -or ($extension -eq 'jar' -and (Get-Item -LiteralPath $file).Length -lt 1024)) {
            $downloaded = $false
            foreach($repository in @('https://repo.huaweicloud.com/repository/maven','https://dl.google.com/android/maven2','https://maven.aliyun.com/repository/google')) {
                try {
                    Invoke-WebRequest -Uri "$repository/$repositoryPath/$artifact-31.9.2.$extension" -OutFile $file -TimeoutSec 180
                    $downloaded = $true
                    break
                } catch { Write-Output "Trying alternate repository for $artifact.$extension" }
            }
            if(!$downloaded){ throw "Artifact unavailable: $artifact.$extension" }
        }
    }
    $checksumResponse = (Invoke-WebRequest -Uri "https://repo.huaweicloud.com/repository/maven/$repositoryPath/$artifact-31.9.2.jar.sha1" -TimeoutSec 30).Content
    $checksum = if($checksumResponse -is [byte[]]){ [System.Text.Encoding]::ASCII.GetString($checksumResponse).Trim() }else{ [string]$checksumResponse }
    $checksum = ($checksum.Trim() -split '\s+')[0]
    $actual = (Get-FileHash (Join-Path $directory "$artifact-31.9.2.jar") -Algorithm SHA1).Hash.ToLowerInvariant()
    if($actual -ne $checksum.ToLowerInvariant()) {
        $jarPath = Join-Path $directory "$artifact-31.9.2.jar"
        Invoke-WebRequest -Uri "https://repo.huaweicloud.com/repository/maven/$repositoryPath/$artifact-31.9.2.jar" -Resume -OutFile $jarPath -TimeoutSec 600
        $actual = (Get-FileHash $jarPath -Algorithm SHA1).Hash.ToLowerInvariant()
        if($actual -ne $checksum.ToLowerInvariant()) { throw "Checksum mismatch: $artifact; downloaded file must not be used" }
    }
    Write-Output "$artifact verified against repository checksum"
}
