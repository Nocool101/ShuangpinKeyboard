# ─────────────────────────────────────────────────────────────
# 生成 App 图标
#
#   pwsh -File tools/make_icon.ps1
#
# 设计：品牌蓝渐变底 + 白色键帽 + 键帽里的**双层标签**
#       （大号主标签「sh」+ 小号副标签「uo」）
#       —— 这正是这个输入法的招牌界面：26 键常驻，一个键上下两层标签。
#
# 产出：
#   drawable/ic_launcher_background.xml     渐变底（矢量）
#   mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher_foreground.png  前景（键帽+标签，透明底）
#   mipmap-anydpi-v26/ic_launcher.xml       自适应图标
#   mipmap-anydpi-v26/ic_launcher_round.xml
#   mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher*.png              传统 PNG（不吃自适应图标的启动器用）
#
# 为什么前景是位图而不是矢量？
#   键帽上的字形是从字体里取的轮廓。GDI+ 的 GraphicsPath.AddString
#   会把整个字形**展平成一条点串**，轮廓之间的边界在 .NET 里拿不到
#   （PathPointType 是 null、PathTypes 是空数组），
#   直接导出会把「s」和「h」连成一个多边形，图标变成花的。
#   启动器图标最大也就 192px，用 432px 的前景 PNG 缩下来足够锐利，
#   所以前景直接渲染成 PNG——所见即所得，没有坑。
#   背景是纯渐变，没有轮廓问题，继续用矢量。
# ─────────────────────────────────────────────────────────────
param(
    [string]$ResRoot = (Join-Path $PSScriptRoot '..\android\app\src\main\res'),
    [string]$PreviewDir = 'D:\tmp\shuangpin-icon'
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

# ── 几何常量（单位 dp，自适应图标画布 108×108）─────────────────
$CANVAS        = 108.0
$KEY_X         = 26.0      # 键帽左上角
$KEY_Y         = 26.0
$KEY_SIZE      = 56.0      # 键帽边长
$KEY_RADIUS    = 13.0      # 键帽圆角

$BRAND_LIGHT   = '#3D7BFF' # 渐变亮端
$BRAND_DARK    = '#1640B4' # 渐变暗端
$INK_MAIN      = '#1740B8' # 主标签颜色
$INK_SUB       = '#6E96E0' # 副标签颜色
$CAP_WHITE     = '#FFFFFF'

$FONT_MAIN     = 'Segoe UI'
$FONT_MAIN_STYLE = [System.Drawing.FontStyle]::Bold
$FONT_SUB      = 'Segoe UI'
$FONT_SUB_STYLE  = [System.Drawing.FontStyle]::Regular

# 主标签「sh」：墨迹高 19dp，视觉中心 y=44
$MAIN_TEXT     = 'sh'
$MAIN_INK_H    = 19.0
$MAIN_CENTER_Y = 44.0
# 副标签「uo」：墨迹高 10dp，视觉中心 y=68.5
#   与主标签留 10dp 的缝——挨太近的话两个标签会读成一个词（「shuo」）。
$SUB_TEXT      = 'uo'
$SUB_INK_H     = 10.0
$SUB_CENTER_Y  = 68.5
$TEXT_CENTER_X = 54.0

# ── 工具 ──────────────────────────────────────────────────────
function New-RoundedRectPath {
    param([float]$X, [float]$Y, [float]$W, [float]$H, [float]$R)
    $p = New-Object System.Drawing.Drawing2D.GraphicsPath
    $d = $R * 2
    $p.AddArc($X, $Y, $d, $d, 180, 90)
    $p.AddArc($X + $W - $d, $Y, $d, $d, 270, 90)
    $p.AddArc($X + $W - $d, $Y + $H - $d, $d, $d, 0, 90)
    $p.AddArc($X, $Y + $H - $d, $d, $d, 90, 90)
    $p.CloseFigure()
    return $p
}

function New-BackgroundBrush {
    param([int]$Size)
    # Rectangle + ForwardDiagonal：跟矢量背景的 startX/startY → endX/endY 同一口径。
    # 用 (PointF,PointF,...) 构造的话 GDI+ 会留下一道斜向硬边。
    return [System.Drawing.Drawing2D.LinearGradientBrush]::new(
        [System.Drawing.Rectangle]::new(0, 0, $Size, $Size),
        [System.Drawing.ColorTranslator]::FromHtml($BRAND_LIGHT),
        [System.Drawing.ColorTranslator]::FromHtml($BRAND_DARK),
        [System.Drawing.Drawing2D.LinearGradientMode]::ForwardDiagonal)
}

# 在画布上画一个字的轮廓（按墨迹高归一化）
function Draw-Glyph {
    param($G, [string]$Text, [string]$FontName, [System.Drawing.FontStyle]$Style,
          [double]$InkH, [double]$CenterY, [string]$Color, [double]$K)

    $fam = New-Object System.Drawing.FontFamily($FontName)
    $gp = New-Object System.Drawing.Drawing2D.GraphicsPath
    $gp.AddString($Text, $fam, [int]$Style, 100,
        (New-Object System.Drawing.PointF(0, 0)),
        [System.Drawing.StringFormat]::GenericTypographic)
    $b = $gp.GetBounds()
    $scale = ($InkH * $K) / $b.Height
    $w = $b.Width * $scale
    # 缩放后墨迹框的左上角要落在 (中心-半宽, 中心-半高)，再把轮廓自身的 b.X/b.Y 偏移减掉
    $x = (54.0 * $K) - ($w / 2) - ($b.X * $scale)
    $y = ($CenterY * $K) - ($InkH * $K / 2) - ($b.Y * $scale)

    $m = [System.Drawing.Drawing2D.Matrix]::new()
    $m.Translate([single]$x, [single]$y)
    $m.Scale([single]$scale, [single]$scale)
    $gp2 = $gp.Clone()
    $gp2.Transform($m)
    $G.FillPath([System.Drawing.SolidBrush]::new([System.Drawing.ColorTranslator]::FromHtml($Color)), $gp2)
    $gp.Dispose(); $gp2.Dispose(); $m.Dispose()
}

# 前景：白色键帽 + 双层标签（透明底）。画布按 dp→px 的比例铺满。
function New-ForegroundBitmap {
    param([int]$Size)
    $k = $Size / 108.0
    $bmp = New-Object System.Drawing.Bitmap($Size, $Size, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.Clear([System.Drawing.Color]::Transparent)

    $keyPath = New-RoundedRectPath -X ($KEY_X * $k) -Y ($KEY_Y * $k) `
        -W ($KEY_SIZE * $k) -H ($KEY_SIZE * $k) -R ($KEY_RADIUS * $k)
    $g.FillPath([System.Drawing.SolidBrush]::new([System.Drawing.Color]::White), $keyPath)

    Draw-Glyph -G $g -Text $MAIN_TEXT -FontName $FONT_MAIN -Style $FONT_MAIN_STYLE `
        -InkH $MAIN_INK_H -CenterY $MAIN_CENTER_Y -Color $INK_MAIN -K $k
    Draw-Glyph -G $g -Text $SUB_TEXT -FontName $FONT_SUB -Style $FONT_SUB_STYLE `
        -InkH $SUB_INK_H -CenterY $SUB_CENTER_Y -Color $INK_SUB -K $k

    $g.Dispose()
    return $bmp
}

# 传统 PNG：背景铺满 + 前景叠上去（不吃自适应图标的启动器用这个）
function New-LegacyBitmap {
    param([int]$Size, [bool]$Round)
    $k = $Size / 108.0
    $bmp = New-Object System.Drawing.Bitmap($Size, $Size, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.Clear([System.Drawing.Color]::Transparent)

    $brush = New-BackgroundBrush -Size $Size
    if ($Round) {
        $g.FillEllipse($brush, 0, 0, $Size, $Size)
    } else {
        $g.FillPath($brush, (New-RoundedRectPath -X 0 -Y 0 -W $Size -H $Size -R ($Size * 0.22)))
    }

    $fg = New-ForegroundBitmap -Size $Size
    $g.DrawImage($fg, 0, 0, $Size, $Size)
    $fg.Dispose()

    $g.Dispose()
    return $bmp
}

# ── 1. 背景矢量（渐变）───────────────────────────────────────
$drawDir = Join-Path $ResRoot 'drawable'
New-Item -ItemType Directory -Force -Path $drawDir | Out-Null

$bgXml = @"
<?xml version="1.0" encoding="utf-8"?>
<!--
  App 图标背景：品牌蓝线性渐变。

  自适应图标的两层：背景铺满整块画布（108dp），
  前景只画中间的「键帽」，由系统按机型裁成圆形/方形/水滴形。
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">

    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient
                android:type="linear"
                android:startX="0"
                android:startY="0"
                android:endX="108"
                android:endY="108">
                <item android:offset="0.0" android:color="$BRAND_LIGHT" />
                <item android:offset="1.0" android:color="$BRAND_DARK" />
            </gradient>
        </aapt:attr>
    </path>
</vector>
"@
[IO.File]::WriteAllText((Join-Path $drawDir 'ic_launcher_background.xml'), $bgXml, (New-Object Text.UTF8Encoding($false)))
Write-Host '✔ drawable/ic_launcher_background.xml'

# ── 2. 前景 PNG（自适应图标用）────────────────────────────────
# 自适应图标的「前景」按 108dp 画布渲染，各密度对应各自的 px。
$fgDpi = @{ 'mdpi' = 108; 'hdpi' = 162; 'xhdpi' = 216; 'xxhdpi' = 324; 'xxxhdpi' = 432 }
foreach ($dpi in $fgDpi.Keys) {
    $dir = Join-Path $ResRoot "mipmap-$dpi"
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $bmp = New-ForegroundBitmap -Size $fgDpi[$dpi]
    $bmp.Save((Join-Path $dir 'ic_launcher_foreground.png'), [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
}
Write-Host '✔ mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher_foreground.png'

# ── 3. 自适应图标 XML ───────────────────────────────────────
$anyDir = Join-Path $ResRoot 'mipmap-anydpi-v26'
New-Item -ItemType Directory -Force -Path $anyDir | Out-Null
$adaptive = @"
<?xml version="1.0" encoding="utf-8"?>
<!--
  自适应图标：品牌蓝渐变底 + 白色键帽（双层标签）。
  系统按机型把这一层裁成圆形/方形/水滴形。
-->
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
"@
foreach ($n in @('ic_launcher', 'ic_launcher_round')) {
    [IO.File]::WriteAllText((Join-Path $anyDir "$n.xml"), $adaptive, (New-Object Text.UTF8Encoding($false)))
}
Write-Host '✔ mipmap-anydpi-v26/ic_launcher.xml, ic_launcher_round.xml'

# ── 4. 传统 PNG ──────────────────────────────────────────────
$legacyDpi = @{ 'mdpi' = 48; 'hdpi' = 72; 'xhdpi' = 96; 'xxhdpi' = 144; 'xxxhdpi' = 192 }
foreach ($dpi in $legacyDpi.Keys) {
    $dir = Join-Path $ResRoot "mipmap-$dpi"
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $px = $legacyDpi[$dpi]
    $bmp = New-LegacyBitmap -Size $px -Round $false
    $bmp.Save((Join-Path $dir 'ic_launcher.png'), [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    $bmpR = New-LegacyBitmap -Size $px -Round $true
    $bmpR.Save((Join-Path $dir 'ic_launcher_round.png'), [System.Drawing.Imaging.ImageFormat]::Png)
    $bmpR.Dispose()
}
Write-Host '✔ mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher.png + ic_launcher_round.png'

# ── 5. 预览 ──────────────────────────────────────────────────
New-Item -ItemType Directory -Force -Path $PreviewDir | Out-Null

# 自适应图标的真实效果：矢量背景 + PNG 前景叠在一起
$adaptivePreview = New-Object System.Drawing.Bitmap(512, 512, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$ag = [System.Drawing.Graphics]::FromImage($adaptivePreview)
$ag.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$ag.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$ag.FillRectangle((New-BackgroundBrush -Size 512), 0, 0, 512, 512)
$fg = New-ForegroundBitmap -Size 512
$ag.DrawImage($fg, 0, 0, 512, 512)
$fg.Dispose()
$ag.Dispose()
$adaptivePreview.Save((Join-Path $PreviewDir 'icon-512.png'), [System.Drawing.Imaging.ImageFormat]::Png)
$adaptivePreview.Dispose()

# 圆形遮罩（模拟圆形启动器图标）
$roundPreview = New-Object System.Drawing.Bitmap(512, 512, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$rg = [System.Drawing.Graphics]::FromImage($roundPreview)
$rg.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$rg.SetClip([System.Drawing.Drawing2D.GraphicsPath]::new())
$rp = New-RoundedRectPath -X 0 -Y 0 -W 512 -H 512 -R 512
$rg.SetClip($rp)
$rg.FillRectangle((New-BackgroundBrush -Size 512), 0, 0, 512, 512)
$fg2 = New-ForegroundBitmap -Size 512
$rg.DrawImage($fg2, 0, 0, 512, 512)
$fg2.Dispose()
$rg.Dispose()
$roundPreview.Save((Join-Path $PreviewDir 'icon-round-512.png'), [System.Drawing.Imaging.ImageFormat]::Png)
$roundPreview.Dispose()

# 各种尺寸下什么样
$strip = New-Object System.Drawing.Bitmap(760, 210, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$gs = [System.Drawing.Graphics]::FromImage($strip)
$gs.Clear([System.Drawing.Color]::FromArgb(255, 238, 241, 246))
$gs.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$gs.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$x = 16
foreach ($px in @(48, 72, 96, 144, 192)) {
    $bmp = New-LegacyBitmap -Size $px -Round $false
    $gs.DrawImage($bmp, $x, 30, $px, $px)
    $bmp.Dispose()
    $x += $px + 20
}
$strip.Save((Join-Path $PreviewDir 'icon-sizes.png'), [System.Drawing.Imaging.ImageFormat]::Png)
$gs.Dispose(); $strip.Dispose()

Write-Host ''
Write-Host "预览：$PreviewDir\icon-512.png  /  icon-round-512.png  /  icon-sizes.png"
