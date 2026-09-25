Add-Type -AssemblyName System.Drawing
$src = 'D:\DevTools\top-icons\top-noir-1024.png'
if (-not (Test-Path $src)) { Write-Error "source not found: $src"; exit 1 }

$res = 'D:\DevTools\Codeartswork\TESE\app\src\main\res'
$img = [System.Drawing.Image]::FromFile($src)

# 源图背景为纯色 #09090B，logo 实际像素范围（扫描得到）
$logoX0 = 158.0; $logoY0 = 348.0; $logoX1 = 864.0; $logoY1 = 698.0
$logoW = $logoX1 - $logoX0
$logoCx = ($logoX0 + $logoX1) / 2.0
$logoCy = ($logoY0 + $logoY1) / 2.0

# 自适应图标前景层为 108dp，圆形遮罩安全区约 66dp
# 让 logo 宽度只占 52%，保证任何遮罩下都不会被切掉
$fgRatio = 0.52

# 老式方形图标（Android 8 以下才用，minSdk 29 实际已不走这条路，保留做兜底）
$legacyMap = @{ 'mdpi' = 48; 'hdpi' = 72; 'xhdpi' = 96; 'xxhdpi' = 144; 'xxxhdpi' = 192 }
# 自适应图标前景层尺寸 = 108dp
$fgMap = @{ 'mdpi' = 108; 'hdpi' = 162; 'xhdpi' = 216; 'xxhdpi' = 324; 'xxxhdpi' = 432 }

foreach ($k in $legacyMap.Keys) {
    $s = $legacyMap[$k]
    $dir = "$res\mipmap-$k"
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    foreach ($name in @('ic_launcher', 'ic_launcher_round')) {
        $bmp = New-Object System.Drawing.Bitmap($s, $s)
        $g = [System.Drawing.Graphics]::FromImage($bmp)
        $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
        $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
        $g.DrawImage($img, 0, 0, $s, $s)
        $g.Dispose()
        $bmp.Save("$dir\$name.png", [System.Drawing.Imaging.ImageFormat]::Png)
        $bmp.Dispose()
    }
    Write-Host "legacy   -> $dir (${s}px)"
}

foreach ($k in $fgMap.Keys) {
    $s = $fgMap[$k]
    $dir = "$res\mipmap-$k"
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $bmp = New-Object System.Drawing.Bitmap($s, $s)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
    # 铺满 #09090B，和自适应图标背景层同色，缩放后边缘接缝不可见
    $g.Clear([System.Drawing.Color]::FromArgb(255, 9, 9, 11))
    $scale = ($fgRatio * $s) / $logoW
    $dw = $img.Width * $scale
    $dh = $img.Height * $scale
    $dx = $s / 2.0 - $logoCx * $scale
    $dy = $s / 2.0 - $logoCy * $scale
    $g.DrawImage($img, [single]$dx, [single]$dy, [single]$dw, [single]$dh)
    $g.Dispose()
    $bmp.Save("$dir\ic_launcher_foreground.png", [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    Write-Host "foreground -> $dir (${s}px, logo $([int]($logoW * $scale))px wide)"
}

$img.Dispose()
Write-Host "done"
