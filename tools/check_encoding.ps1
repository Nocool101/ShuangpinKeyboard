<#
.SYNOPSIS
    检查源码里有没有「乱码字符」（U+FFFD 替换字符）。

.DESCRIPTION
    写文件时偶尔会有个别汉字被替换成 U+FFFD（一个汉字变 3 个替换符）。
    这类字符不影响编译，但读起来是「坏字」，必须揪出来。

    有问题时直接打印出文件:行号:行内容，照着改就行。
#>
param(
    [string]$Root = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = 'Stop'

$ext = @('*.kt', '*.java', '*.ps1', '*.mjs', '*.js', '*.md', '*.html', '*.xml', '*.txt', '*.yaml')
$files = Get-ChildItem $Root -Recurse -File -Include $ext -ErrorAction SilentlyContinue |
    Where-Object {
        $_.FullName -notmatch '[\\/]\.git[\\/]' -and
        $_.FullName -notmatch '[\\/]build[\\/]' -and
        $_.FullName -notmatch '[\\/]\.gradle[\\/]'
    }

$bad = @()
foreach ($f in $files) {
    try {
        $lines = [System.IO.File]::ReadAllLines($f.FullName, [System.Text.Encoding]::UTF8)
        for ($i = 0; $i -lt $lines.Count; $i++) {
            if ($lines[$i].Contains([char]0xFFFD)) {
                $bad += '{0}:{1}: {2}' -f $f.FullName, ($i + 1), $lines[$i].Trim()
            }
        }
    } catch {
        # 读不了的（二进制之类）跳过
    }
}

if ($bad.Count -eq 0) {
    Write-Host "OK: 没有乱码字符（扫了 $($files.Count) 个文件）" -ForegroundColor Green
    exit 0
} else {
    Write-Host "发现 $($bad.Count) 处乱码：" -ForegroundColor Yellow
    $bad | ForEach-Object { Write-Host "  $_" }
    exit 1
}
