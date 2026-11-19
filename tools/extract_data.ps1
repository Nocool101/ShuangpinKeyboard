# ============================================================
# extract_data.ps1
# 从 index.html（原型唯一真源）抽取数据层，生成 engine 模块使用的资源文件。
#
#   index.html  ->  android/engine/src/main/resources/ime/*.txt
#
# 用法（仓库根目录）：
#   pwsh -File tools/extract_data.ps1
# ============================================================
param(
    [string]$Root = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = 'Stop'
$src = Join-Path $Root 'index.html'
$out = Join-Path $Root 'android\engine\src\main\resources\ime'

if (-not (Test-Path $src)) { throw "找不到 index.html: $src" }
New-Item -ItemType Directory -Force -Path $out | Out-Null

$js = Get-Content -Raw -Encoding UTF8 $src

function Get-Block([string]$text, [string]$startPattern) {
    $m = [regex]::Match($text, $startPattern, 'Singleline')
    if (-not $m.Success) { throw "匹配不到代码块: $startPattern" }
    return $m.Groups[1].Value
}

# 源数据里「键」是裸标识符，「值」才是带引号的字符串：
#   ya:'ia'      （不是 'ya':'ia'）
# 所以键值对的统一模式是  (\w+)\s*:\s*'([^']+)'
$KV = "(\w+)\s*:\s*'([^']+)'"

# ---------- 1. 双拼方案 ----------
$schemesBlock = Get-Block $js 'const\s+SCHEMES\s*=\s*\{([\s\S]*?)\n\};'
$schemeRe = [regex]'(?s)(\w+):\{\s*name:''([^'']+)'',\s*initial:\{([^}]*)\},\s*final:\{([^}]*)\}\s*\}'

$schemeIds   = @()
$schemeNames = @{}
$initials    = @()   # scheme, initial, key
$finals      = @()   # scheme, final, key

foreach ($m in $schemeRe.Matches($schemesBlock)) {
    $id   = $m.Groups[1].Value
    $name = $m.Groups[2].Value
    $schemeIds += $id
    $schemeNames[$id] = $name
    foreach ($p in [regex]::Matches($m.Groups[3].Value, $KV)) {
        $initials += "$id`t$($p.Groups[1].Value)`t$($p.Groups[2].Value)"
    }
    foreach ($p in [regex]::Matches($m.Groups[4].Value, $KV)) {
        $finals += "$id`t$($p.Groups[1].Value)`t$($p.Groups[2].Value)"
    }
}
if ($schemeIds.Count -eq 0) { throw "SCHEMES 解析为空" }

$sb = New-Object System.Text.StringBuilder
[void]$sb.AppendLine("# scheme`t方案名")
foreach ($id in $schemeIds) { [void]$sb.AppendLine("scheme`t$id`t$($schemeNames[$id])") }
Set-Content -Path (Join-Path $out 'schemes.txt') -Value $sb.ToString() -Encoding UTF8 -NoNewline

$sb = New-Object System.Text.StringBuilder
[void]$sb.AppendLine("# scheme`t声母`t键位")
foreach ($l in $initials) { [void]$sb.AppendLine("initial`t$l") }
Set-Content -Path (Join-Path $out 'initials.txt') -Value $sb.ToString() -Encoding UTF8 -NoNewline

$sb = New-Object System.Text.StringBuilder
[void]$sb.AppendLine("# scheme`t韵母`t键位")
foreach ($l in $finals) { [void]$sb.AppendLine("final`t$l") }
Set-Content -Path (Join-Path $out 'finals.txt') -Value $sb.ToString() -Encoding UTF8 -NoNewline

# ---------- 2. 零声母二段表 ----------
# 结构: zeroKey:{ secondKey:{fin:'x',syl:'y'}, ... }
$zeroBlock = Get-Block $js 'const\s+ZERO_STAGE\s*=\s*\{([\s\S]*?)\n\};'
$outerRe = [regex]"(\w+):\{(\w+:\{fin:'[^']+',syl:'[^']+'\}(?:,\s*\w+:\{fin:'[^']+',syl:'[^']+'\})*)\}"
$innerRe = [regex]"(\w+):\{fin:'([^']+)',syl:'([^']+)'\}"
$zero = @()
foreach ($id in $schemeIds) {
    foreach ($g in $outerRe.Matches($zeroBlock)) {
        foreach ($i in $innerRe.Matches($g.Groups[2].Value)) {
            $zero += "$id`t$($g.Groups[1].Value)`t$($i.Groups[1].Value)`t$($i.Groups[2].Value)`t$($i.Groups[3].Value)"
        }
    }
}

$sb = New-Object System.Text.StringBuilder
[void]$sb.AppendLine("# scheme`t零声母入口键`t第二键`t韵母`t音节")
foreach ($l in $zero) { [void]$sb.AppendLine("zero`t$l") }
Set-Content -Path (Join-Path $out 'zero_stage.txt') -Value $sb.ToString() -Encoding UTF8 -NoNewline

# ---------- 3. 音节表 ----------
$syllText = Get-Block $js 'const\s+SYL_TEXT\s*=\s*`([\s\S]*?)`'
$sb = New-Object System.Text.StringBuilder
[void]$sb.AppendLine("# init`t音节列表（空格分隔）；init=z0 表示零声母")
foreach ($line in ($syllText -split "`r?`n")) {
    $t = $line.Trim()
    if ($t) { [void]$sb.AppendLine($t) }
}
Set-Content -Path (Join-Path $out 'syllables.txt') -Value $sb.ToString() -Encoding UTF8 -NoNewline

# ---------- 4. 整体认读 y/w 韵母映射 ----------
$ywBlock = Get-Block $js 'const\s+YW_FIN\s*=\s*\{([\s\S]*?)\};'
$sb = New-Object System.Text.StringBuilder
[void]$sb.AppendLine("# 整体认读音节`t实际韵母")
foreach ($p in [regex]::Matches($ywBlock, $KV)) {
    [void]$sb.AppendLine("$($p.Groups[1].Value)`t$($p.Groups[2].Value)")
}
Set-Content -Path (Join-Path $out 'yw_finals.txt') -Value $sb.ToString() -Encoding UTF8 -NoNewline

# ---------- 5. 声母匹配顺序 ----------
$initsBlock = Get-Block $js 'const\s+INITS\s*=\s*\[([\s\S]*?)\];'
$inits = [regex]::Matches($initsBlock, "'([^']+)'") | ForEach-Object { $_.Groups[1].Value }
$sb = New-Object System.Text.StringBuilder
[void]$sb.AppendLine("# 声母匹配顺序（先匹配者胜出，故 zh/ch/sh 必须靠前）")
foreach ($i in $inits) { [void]$sb.AppendLine($i) }
Set-Content -Path (Join-Path $out 'initials_order.txt') -Value $sb.ToString() -Encoding UTF8 -NoNewline

# ---------- 6. 字表（占位，不覆盖真词库） ----------
# 字表/词表由 tools/import_dict.ps1 从 rime-ice 生成，这个脚本不碰它们，
# 避免「原型抽取」把真正的词库覆盖回几十条的原型规模。
# 若确实需要重建占位表，先删掉 import_dict.ps1 产物再运行。

# ---------- 7. 词表（占位，不覆盖真词库） ----------

# ---------- 8. 校验用例 ----------
# 结构: xiaohe:[['拼','pin','pb'],['平','ping','pk'], ...]
$ccBlock = Get-Block $js 'const\s+CHECK_CASES\s*=\s*\{([\s\S]*?)\n\};'
$schemeArrRe = [regex]"(\w+)\s*:\s*\[((?:\[[^\[\]]*\],?\s*)+)\]"
$tripleRe = [regex]"\[([^\[\]]*)\]"
$sb = New-Object System.Text.StringBuilder
[void]$sb.AppendLine("# scheme`t汉字`t拼音`t期望编码")
$ccCount = 0
foreach ($sm in $schemeArrRe.Matches($ccBlock)) {
    $sid = $sm.Groups[1].Value
    foreach ($tm in $tripleRe.Matches($sm.Groups[2].Value)) {
        $parts = $tm.Groups[1].Value -split ',' | ForEach-Object { $_.Trim().Trim("'") }
        if ($parts.Count -eq 3) {
            [void]$sb.AppendLine("$sid`t$($parts[0])`t$($parts[1])`t$($parts[2])")
            $ccCount++
        }
    }
}
Set-Content -Path (Join-Path $out 'check_cases.txt') -Value $sb.ToString() -Encoding UTF8 -NoNewline

Write-Host "抽取完成 -> $out"
Get-ChildItem $out | Sort-Object Name | ForEach-Object {
    $n = (Get-Content $_.FullName -Encoding UTF8 | Where-Object { $_ -and -not $_.StartsWith('#') }).Count
    Write-Host ("  {0,-20} {1,6} 条" -f $_.Name, $n)
}
