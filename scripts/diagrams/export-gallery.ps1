param(
    [string]$SampleDirectory = (Join-Path $PSScriptRoot '../../app/build/diagram-samples'),
    [string]$Destination = ''
)
$ErrorActionPreference = 'Stop'
$sampleDir = (Resolve-Path -LiteralPath $SampleDirectory).Path
$manifest = @(Get-Content -LiteralPath (Join-Path $sampleDir 'manifest.json') -Raw -Encoding utf8 | ConvertFrom-Json)
Add-Type -AssemblyName System.Drawing

# Small, numbered sheets make visual review bounded even for the large banks.
for ($batch = 0; $batch -lt [Math]::Ceiling($manifest.Count / 4); $batch++) {
    $sheet = [System.Drawing.Bitmap]::new(1500,1000)
    $graphics = [System.Drawing.Graphics]::FromImage($sheet)
    $font = [System.Drawing.Font]::new('Microsoft YaHei',16)
    try {
        $graphics.Clear([System.Drawing.Color]::FromArgb(240,242,245))
        $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        for ($slot = 0; $slot -lt 4; $slot++) {
            $index = $batch * 4 + $slot
            if ($index -ge $manifest.Count) { break }
            $item = $manifest[$index]
            $img = [System.Drawing.Image]::FromFile((Join-Path $sampleDir ($item.slug + '.png')))
            try {
                $x = ($slot % 2) * 750
                $y = [Math]::Floor($slot / 2) * 500
                $scale = [Math]::Min(730 / $img.Width, 445 / $img.Height)
                $width = [int]($img.Width * $scale)
                $height = [int]($img.Height * $scale)
                $graphics.DrawImage($img,$x+[int]((750-$width)/2),$y+45+[int]((445-$height)/2),$width,$height)
                $graphics.DrawString(('{0:00}  {1}' -f ($index+1),$item.title),$font,[System.Drawing.Brushes]::Black,$x+14,$y+8)
            } finally { $img.Dispose() }
        }
        $sheet.Save((Join-Path $sampleDir ('overview-{0}.jpg' -f ($batch+1))),[System.Drawing.Imaging.ImageFormat]::Jpeg)
    } finally { $font.Dispose(); $graphics.Dispose(); $sheet.Dispose() }
}

$cards = foreach ($item in $manifest) {
    $title = [System.Net.WebUtility]::HtmlEncode($item.title)
    $slug = [System.Net.WebUtility]::HtmlEncode($item.slug)
    $references = $item.references -join '、'
    @"
<article><header><h2>$title</h2><span>参考图 $references</span></header><a class="preview" href="$slug.png" target="_blank"><img loading="lazy" src="$slug.png" data-slug="$slug" alt="$title"></a><footer><a class="original" href="$slug.png" target="_blank">打开原图</a><a href="$slug.json" target="_blank">拓扑 JSON</a></footer></article>
"@
}
$html = @"
<!doctype html>
<html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>通信框图 · 生成样图</title>
<style>
:root{color-scheme:light;--paper:#f3f5f7;--card:#fff;--ink:#202b38;--muted:#627080;--border:#dfe5eb}
*{box-sizing:border-box}body{margin:0;background:var(--paper);color:var(--ink);font:16px/1.65 system-ui,"Microsoft YaHei",sans-serif}
main{max-width:1600px;margin:auto;padding:30px}h1{font-size:30px;margin:0}p{color:var(--muted);margin:6px 0 18px}
.toolbar{display:flex;gap:14px;align-items:center;flex-wrap:wrap;margin-bottom:24px}button,a{color:inherit}
button{border:1px solid var(--border);background:var(--card);padding:9px 18px;border-radius:18px;cursor:pointer;font:inherit}
.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,600px),1fr));gap:20px}article{background:var(--card);border:1px solid var(--border);border-radius:20px;overflow:hidden}
article header{display:flex;justify-content:space-between;gap:14px;align-items:baseline;padding:16px 20px}h2{font-size:18px;margin:0}span{color:var(--muted);font-size:13px;white-space:nowrap}
.preview{display:flex;align-items:center;min-height:240px;padding:12px}img{width:100%;height:auto;max-height:640px;object-fit:contain}footer{display:flex;gap:20px;padding:12px 20px;border-top:1px solid var(--border);font-size:14px}
body.chalk{color-scheme:dark;--paper:#19241f;--card:#24332d;--ink:#c9c9ce;--muted:#9aa8a0;--border:#3c5045}
</style>
<main><h1>通信框图 · 16 张生成样图</h1><p>覆盖桌面“框图”文件夹的 17 张参考图。每张样图通过应用的 JSON 解析、布局和原生渲染流程生成；重复样式合并，OFDM 发送与接收分别展示。</p>
<div class="toolbar"><button id="theme" aria-pressed="false">切换黑板主题</button><span>点击样图可打开原始分辨率图片；JSON 可用于重复生成。</span></div><div class="grid">$($cards -join "`n")</div></main>
<script>
document.querySelector('#theme').addEventListener('click',function(){const dark=document.body.classList.toggle('chalk');this.setAttribute('aria-pressed',String(dark));this.textContent=dark?'切换白纸主题':'切换黑板主题';document.querySelectorAll('img').forEach(img=>{const url=img.dataset.slug+(dark?'-chalk':'')+'.png';img.src=url;img.closest('article').querySelector('.original').href=url;img.parentElement.href=url;});});
</script></html>
"@
[System.IO.File]::WriteAllText((Join-Path $sampleDir 'index.html'),$html,[System.Text.UTF8Encoding]::new($false))

if ($Destination) {
    $outputDir = [System.IO.Path]::GetFullPath($Destination)
    New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
    foreach ($item in $manifest) {
        foreach ($suffix in @('.png','-chalk.png','.json')) {
            Copy-Item -LiteralPath (Join-Path $sampleDir ($item.slug + $suffix)) -Destination $outputDir
        }
    }
    foreach ($name in @('manifest.json','index.html')) { Copy-Item -LiteralPath (Join-Path $sampleDir $name) -Destination $outputDir }
    Get-ChildItem -LiteralPath $sampleDir -Filter 'overview-*.jpg' | Copy-Item -Destination $outputDir
    Write-Output (Join-Path $outputDir 'index.html')
} else { Write-Output (Join-Path $sampleDir 'index.html') }
