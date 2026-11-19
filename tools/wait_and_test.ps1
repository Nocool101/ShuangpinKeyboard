# ============================================================
# wait_and_test.ps1
# 等手机连上 adb，然后自动：装 APK -> 切输入法 -> 打开输入框 ->
# 打一个词 -> 截图 -> 量内存。
#
# 用法：
#   pwsh -File tools/wait_and_test.ps1
# ============================================================
param(
    [string]$Apk = 'C:\works\ShuRuFa\android\app\build\outputs\apk\debug\app-debug.apk',
    [string]$Shots = 'D:\tmp\shuangpin-ime-shots',
    [int]$TimeoutMin = 30
)

$ErrorActionPreference = 'Continue'
$adb = 'C:\Android\Sdk\platform-tools\adb.exe'
New-Item -ItemType Directory -Force -Path $Shots | Out-Null

function Dev {
    $out = & $adb devices 2>&1
    $m = $out | Select-String -Pattern '\tdevice$'
    if ($null -eq $m) { return $null }
    return ($m.ToString() -split "`t")[0]
}

Write-Host "等待手机连接 adb（最多 $TimeoutMin 分钟）..."
$deadline = (Get-Date).AddMinutes($TimeoutMin)
$serial = $null
while ((Get-Date) -lt $deadline) {
    $serial = Dev
    if ($serial) { break }
    Start-Sleep -Seconds 5
}
if (-not $serial) {
    Write-Host "超时：没有等到设备。"
    exit 1
}
Write-Host "设备已连接：$serial"
Start-Sleep -Seconds 2

Write-Host "`n[1/6] 安装 APK ..."
& $adb install -r -t $Apk 2>&1

Write-Host "`n[2/6] 设为默认输入法 ..."
& $adb shell ime enable com.shuangpin.ime/.ShuangpinImeService 2>&1
& $adb shell ime set com.shuangpin.ime/.ShuangpinImeService 2>&1
& $adb logcat -c
& $adb shell am force-stop com.shuangpin.ime

Write-Host "`n[3/6] 打开设置搜索框 ..."
& $adb shell am start -a android.settings.SETTINGS 2>&1 | Out-Null
Start-Sleep -Seconds 3
& $adb shell input tap 1110 235
Start-Sleep -Seconds 3

Write-Host "`n[4/6] 打字：guo（过）—— 这是原型音节表原先缺失的音节 ..."
# g=35 u=49 o=43  -> guo
& $adb shell input keyevent 35
& $adb shell input keyevent 49
& $adb shell input keyevent 43
Start-Sleep -Seconds 2
& $adb exec-out screencap -p > "$Shots\20-guo.png"

Write-Host "`n[5/6] 再打 nihao 并上屏 ..."
& $adb shell input keyevent 67; Start-Sleep -Milliseconds 300
& $adb shell input keyevent 67; Start-Sleep -Milliseconds 300
& $adb shell input keyevent 42; & $adb shell input keyevent 37
& $adb shell input keyevent 36; & $adb shell input keyevent 31
Start-Sleep -Seconds 2
& $adb exec-out screencap -p > "$Shots\21-nihao.png"
& $adb shell input keyevent 62   # 空格上屏
Start-Sleep -Seconds 1
& $adb exec-out screencap -p > "$Shots\22-committed.png"

Write-Host "`n[6/6] 内存与崩溃检查 ..."
& $adb shell dumpsys meminfo com.shuangpin.ime 2>&1 |
    Select-String -Pattern 'TOTAL PSS|TOTAL RSS|Java Heap|Native Heap|EGL mtrack' |
    Select-Object -First 6 | ForEach-Object { Write-Host "  $_" }

$crash = & $adb logcat -d -v brief 2>&1 | Select-String -Pattern 'FATAL EXCEPTION'
if ($crash) {
    Write-Host "  !! 有崩溃：" -ForegroundColor Red
    $crash | Select-Object -Last 5 | ForEach-Object { Write-Host "  $_" }
} else {
    Write-Host "  无崩溃" -ForegroundColor Green
}

Write-Host "`n截图已保存到 $Shots"
