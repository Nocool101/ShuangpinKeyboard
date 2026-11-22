# ============================================================
# make_t2s.ps1
# 生成「繁 → 简」对照表，给 import_dict.mjs 过滤繁体字用。
#
#   Unihan(kSimplifiedVariant) + OpenCC(TSCharacters)  ->  t2s.txt
#
# 为什么要它：大字表（rime-ice 41448）里有大量**繁体字**
# （鴛/鶻/鷀/鵴…），用户明确要求「不要繁体文字，只要简体」。
# 而「哪些是繁体」需要一张对照表——没有表就只能靠猜。
#
# 数据来源（都是公开标准）：
#   - Unihan  https://www.unicode.org/Public/UCD/latest/ucd/Unihan.zip
#     取 Unihan_Variants.txt 里的 kTraditionalVariant 字段
#     （字段名有点绕：它是「简体 → 繁体」，所以要**反过来**用）
#   - OpenCC  https://raw.githubusercontent.com/BYVoid/OpenCC/master/data/dictionary/TSCharacters.txt
#     格式「繁体 <TAB> 简体」，方向正好对上
#
# 用法：
#   pwsh -File tools/make_t2s.ps1 [-Out "D:\tmp\rime-ice-dict\t2s.txt"]
# ============================================================
param(
    [string]$Out = 'D:\tmp\rime-ice-dict\t2s.txt',
    [string]$Cache = 'D:\tmp\rime-ice-dict'
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

New-Item -ItemType Directory -Force -Path $Cache | Out-Null

# ---------- 规范简体字表（判「是不是繁体」的依据） ----------
$simple = @{}
$dict8105 = Join-Path $Cache 'full\cn_dicts\8105.dict.yaml'
if (-not (Test-Path $dict8105)) {
    Write-Host "⚠ 找不到 8105 字表 $dict8105 —— Unihan 那部分不按「简体形式」过滤了"
} else {
    Select-String -Path $dict8105 -Pattern '^\S+\t' -Encoding UTF8 | ForEach-Object {
        $w = $_.Line.Split("`t")[0]
        if ($w.Length -eq 1) { $simple[$w] = $true }
    }
    Write-Host "规范简体字表: $($simple.Count) 个字"
}

# ---------- 笔画数（判「古字→正字」还是「繁体→简体」） ----------
$strokes = @{}
$irg = Join-Path $Cache 'Unihan_IRGSources.txt'
if (-not (Test-Path $irg)) {
    $zip = Join-Path $Cache 'Unihan.zip'
    if (Test-Path $zip) {
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $z = [System.IO.Compression.ZipFile]::OpenRead($zip)
        try {
            $e = $z.Entries | Where-Object { $_.FullName -like '*IRGSources*' } | Select-Object -First 1
            [System.IO.Compression.ZipFileExtensions]::ExtractToFile($e, $irg, $true)
        } finally { $z.Dispose() }
    }
}
if (Test-Path $irg) {
    Select-String -Path $irg -Pattern 'kTotalStrokes' -Encoding UTF8 | ForEach-Object {
        $m = [regex]::Match($_.Line, 'U\+([0-9A-F]+)\s+kTotalStrokes\s+(\d+)')
        if ($m.Success) {
            $cp = [Convert]::ToInt32($m.Groups[1].Value, 16)
            if ($cp -le 0xFFFF) { $strokes[[char]$cp] = [int]$m.Groups[2].Value }
        }
    }
    Write-Host "笔画数: $($strokes.Count) 个字"
}

# ---------- Unihan ----------
$zip = Join-Path $Cache 'Unihan.zip'
if (-not (Test-Path $zip)) {
    Write-Host "下载 Unihan.zip ..."
    Invoke-WebRequest -Uri 'https://www.unicode.org/Public/UCD/latest/ucd/Unihan.zip' -OutFile $zip -TimeoutSec 180
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$variants = Join-Path $Cache 'Unihan_Variants.txt'
if (-not (Test-Path $variants)) {
    $z = [System.IO.Compression.ZipFile]::OpenRead($zip)
    try {
        $e = $z.Entries | Where-Object { $_.FullName -like '*Variants*' } | Select-Object -First 1
        [System.IO.Compression.ZipFileExtensions]::ExtractToFile($e, $variants, $true)
    } finally { $z.Dispose() }
}

# 繁 -> 简
#
# ⚠ Unihan 这个字段里混着三类东西：
#   ① 真正的「繁体 → 简体」：龍 → 龙
#   ② 简繁同形的**自映射**：了 → 了、家 → 家（不是繁体）
#   ③ 「古字/俗字 → 正字」：么 → 麼（把「么」当成了繁体 ✗✗✗
#      ——「什么」这个词整个没了）
#
# 判据（两条都满足才算繁体）：
#   - 值 ≠ 键（排掉自映射）
#   - 值是**规范简体字**（在 8105 里），**或者** 键的笔画比值多
#     （繁体字的笔画一定比简体多；古字→正字是反过来的）
$map = @{}
$skipped = 0
Select-String -Path $variants -Pattern 'kSimplifiedVariant' -Encoding UTF8 | ForEach-Object {
    $m = [regex]::Match($_.Line, 'U\+([0-9A-F]+)\s+kSimplifiedVariant\s+U\+([0-9A-F]+)')
    if ($m.Success) {
        $cp1 = [Convert]::ToInt32($m.Groups[1].Value, 16)
        $cp2 = [Convert]::ToInt32($m.Groups[2].Value, 16)
        $t = [System.Text.Rune]::new($cp1).ToString()
        $s = [System.Text.Rune]::new($cp2).ToString()
        if ($t -eq $s) { $skipped++; return }
        $isTrad = $simple.ContainsKey($s)
        if (-not $isTrad -and $cp1 -le 0xFFFF -and $cp2 -le 0xFFFF) {
            $k1 = $strokes[[char]$cp1]; $k2 = $strokes[[char]$cp2]
            if ($k1 -and $k2 -and $k1 -gt $k2) { $isTrad = $true }
        }
        if ($isTrad) { $map[$t] = $s } else { $skipped++ }
    }
}
Write-Host "Unihan kSimplifiedVariant -> 繁体 $($map.Count) 条（排除 $skipped 条自映射/古字）"

# ---------- OpenCC ----------
$tsc = Join-Path $Cache 'TSCharacters.txt'
if (-not (Test-Path $tsc)) {
    Write-Host "下载 OpenCC TSCharacters.txt ..."
    Invoke-WebRequest -Uri 'https://raw.githubusercontent.com/BYVoid/OpenCC/master/data/dictionary/TSCharacters.txt' -OutFile $tsc -TimeoutSec 120
}
Get-Content $tsc -Encoding UTF8 | ForEach-Object {
    if ($_ -and -not $_.StartsWith('#')) {
        $p = $_ -split "`t"
        if ($p.Count -ge 2 -and $p[1].Trim()) {
            $v = $p[1].Split(' ')[0]
            # OpenCC 是人工整理的「繁→简」，方向可信；自映射照样排掉
            if ($p[0] -ne $v -and -not $map.ContainsKey($p[0])) { $map[$p[0]] = $v }
        }
    }
}
Write-Host "合并 OpenCC 后: $($map.Count) 条"

# ---------- Unihan 的另一个方向：kTraditionalVariant（简 → 繁） ----------
#
# 这个字段的**值**就是「繁体字」——某个简体字的繁体形式。
# 它能补上 kSimplifiedVariant 漏掉的生僻繁体，而且**不会误伤简体俗字**
# （蚖 这种字在 Unihan 里根本没有变体条目 → 稳稳保留 ✓）。
#
# ⚠ **不要用「Big5 码表（kIRG_TSource）」当繁体判据**：
#   Big5 收的 24000 个字里混着**简体俗字**（蚖 就在里面），
#   照着滤会把「蚖」这种字误杀——用户点名要能打出来的字。
#   宁可漏几个生僻繁体，也不能误伤生僻简体。
Select-String -Path $variants -Pattern 'kTraditionalVariant' -Encoding UTF8 | ForEach-Object {
    $m = [regex]::Match($_.Line, 'U\+([0-9A-F]+)\s+kTraditionalVariant\s+U\+([0-9A-F]+)')
    if ($m.Success) {
        $cpSimple = [Convert]::ToInt32($m.Groups[1].Value, 16)
        $cpTrad = [Convert]::ToInt32($m.Groups[2].Value, 16)
        if ($cpSimple -eq $cpTrad) { return }          # 同形，不是繁体
        $simpleChar = [System.Text.Rune]::new($cpSimple).ToString()
        $tradChar = [System.Text.Rune]::new($cpTrad).ToString()
        if (-not $map.ContainsKey($tradChar)) { $map[$tradChar] = $simpleChar }
    }
}
Write-Host "合并 Unihan kTraditionalVariant 后: $($map.Count) 条"

$lines = $map.GetEnumerator() | Sort-Object Name | ForEach-Object { "$($_.Key)`t$($_.Value)" }
Set-Content -Path $Out -Value $lines -Encoding UTF8
Write-Host "已写出 $Out ($([math]::Round((Get-Item $Out).Length / 1KB, 1)) KB，共 $($map.Count) 条)"
