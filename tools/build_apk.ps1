<#
.SYNOPSIS
    在 D:\tmp 下构建 APK（打包/产物类工作一律放 D:\tmp）。

.DESCRIPTION
    源码本体留在项目目录，构建时复制一份到 D:\tmp\shuangpin-ime\src 再编译：
    产物生成在干净、可复现的环境里，不污染源码树。

.PARAMETER Task
    Gradle 任务，默认 assembleDebug。可传 test / assembleRelease 等。

.PARAMETER SkipSync
    跳过源码同步（已经在 D:\tmp 里改好了的时候用）。

.EXAMPLE
    pwsh -File tools/build_apk.ps1
    pwsh -File tools/build_apk.ps1 -Task test
#>
param(
    [string]$Task = 'assembleDebug',
    [switch]$SkipSync
)

$ErrorActionPreference = 'Stop'

$repo    = Split-Path -Parent $PSScriptRoot
$workDir = 'D:\tmp\shuangpin-ime\src'
$outDir  = 'D:\tmp\shuangpin-ime'

$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot'
$env:ANDROID_HOME = 'C:\Android\Sdk'
$env:ANDROID_SDK_ROOT = 'C:\Android\Sdk'

if (-not $SkipSync) {
    Write-Host ">> 同步源码 → $workDir" -ForegroundColor Cyan
    if (Test-Path $workDir) { Remove-Item $workDir -Recurse -Force }
    New-Item -ItemType Directory -Path $workDir -Force | Out-Null
    # .git / build / .gradle 都不带过去：源码树 + 数据资源就够构建了
    # PATH 很精简，robocopy 得用全路径
    $robocopy = Join-Path $env:SystemRoot 'System32\Robocopy.exe'
    & $robocopy $repo $workDir /MIR /NFL /NDL /NJH /NJS /NP `
        /XD '.git' 'build' '.gradle' '.idea' | Out-Null
    if ($LASTEXITCODE -ge 8) { throw "robocopy 失败（$LASTEXITCODE）" }
}

Write-Host ">> gradlew $Task" -ForegroundColor Cyan
Push-Location (Join-Path $workDir 'android')
try {
    & .\gradlew.bat $Task --console=plain -q
    if ($LASTEXITCODE -ne 0) { throw "gradle 失败（$LASTEXITCODE）" }
} finally {
    Pop-Location
}

$apk = Join-Path $workDir "android\app\build\outputs\apk\debug\app-debug.apk"
if (Test-Path $apk) {
    $stamp = Get-Date -Format 'yyyyMMdd-HHmm'
    $dest  = Join-Path $outDir "app-debug-$stamp.apk"
    Copy-Item $apk $dest -Force
    $mb = [math]::Round((Get-Item $dest).Length / 1MB, 1)
    Write-Host ">> APK: $dest  ($mb MB)" -ForegroundColor Green
} elseif ($Task -eq 'assembleDebug') {
    throw "没找到 APK：$apk"
}
