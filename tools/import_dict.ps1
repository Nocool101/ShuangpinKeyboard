# ============================================================
# import_dict.ps1
# 把 rime-ice 的 Rime 词库转成本项目的 chars.txt / phrases.txt。
#
#   rime-ice/cn_dicts/*.dict.yaml  ->  android/engine/src/main/resources/ime/{chars,phrases}.txt
#
# 词库来源：https://github.com/iDvel/rime-ice  （GPL-3.0）
#
# 实际转换逻辑在 import_dict.mjs（Node 实现，比 PowerShell 快一个数量级），
# 这个脚本只是包一层，方便统一用 pwsh 调用。
#
# 用法：
#   pwsh -File tools/import_dict.ps1 -RimeDir "D:\tmp\rime-ice-dict\full"
# ============================================================
param(
    [string]$RimeDir = 'D:\tmp\rime-ice-dict\full',
    [string]$Root = (Split-Path -Parent $PSScriptRoot),
    [int]$MaxCharsPerSyllable = 300,
    [int]$MaxPhrasesPerKey = 30
)

$ErrorActionPreference = 'Stop'

$node = (Get-Command node -ErrorAction SilentlyContinue).Source
if (-not $node) {
    foreach ($c in @('C:\Program Files\nodejs\node.exe', 'C:\Program Files (x86)\nodejs\node.exe')) {
        if (Test-Path $c) { $node = $c; break }
    }
}
if (-not $node) { throw "找不到 node，转换脚本需要 Node.js" }

$script = Join-Path $Root 'tools\import_dict.mjs'
$args = @(
    $script,
    '--rime', $RimeDir,
    '--max-chars', $MaxCharsPerSyllable,
    '--max-phrases', $MaxPhrasesPerKey
)

& $node @args
if ($LASTEXITCODE -ne 0) { throw "转换失败，退出码 $LASTEXITCODE" }
