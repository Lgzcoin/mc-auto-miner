# 多开工具一键更新器
# 作用：去 GitHub 看有没有新版本，有就下载、备份、替换脚本和模组，保留你自己的配置。
# 用法：双击 一键更新.bat；或 powershell -File 更新工具.ps1 -CheckOnly
[CmdletBinding()]
param(
    [string]$Repo = "Lgzcoin/mc-auto-miner",
    [switch]$CheckOnly,
    [switch]$Force,
    [switch]$NoBackup,
    [switch]$Yes,
    [switch]$NoPause,
    [switch]$SelfTest
)

$ErrorActionPreference = "Stop"
try { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 } catch { }

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$multi = $scriptDir
$nested = Join-Path $scriptDir "multi"
if (Test-Path (Join-Path $nested "一键多开.ps1")) { $multi = $nested }

$logFile = Join-Path $multi "更新日志.txt"
$rootLog = Join-Path (Split-Path -Parent $multi) "更新日志.txt"
if ($rootLog -eq $logFile) { $rootLog = $null }
$versionFile = Join-Path $multi "版本.txt"

function Say([string]$text, [string]$color = "Gray") {
    Write-Host $text -ForegroundColor $color
}

function Log([string]$text) {
    $line = "[" + (Get-Date -Format "yyyy-MM-dd HH:mm:ss") + "] " + $text
    foreach ($p in @($logFile, $rootLog)) {
        if (-not $p) { continue }
        try { Add-Content -Path $p -Value $line -Encoding UTF8 } catch { }
    }
}

function ConvertTo-Version([string]$text) {
    if ($text -match "(\d+\.\d+\.\d+)") { return [version]$Matches[1] }
    return [version]"0.0.0"
}

function Get-LocalModVersion {
    $dir = Join-Path $multi "mods"
    if (-not (Test-Path $dir)) { return $null }
    $jars = @(Get-ChildItem -Path $dir -Filter "autominer-*.jar" -ErrorAction SilentlyContinue)
    if ($jars.Count -eq 0) { return $null }
    $best = $null
    $bestVer = $null
    foreach ($j in $jars) {
        if ($j.Name -match "autominer-(\d+\.\d+\.\d+)\.jar") {
            $v = [version]$Matches[1]
            if ($null -eq $bestVer -or $v -gt $bestVer) {
                $bestVer = $v
                $best = $j
            }
        }
    }
    if ($null -eq $bestVer) { return $null }
    return [pscustomobject]@{ Version = $bestVer; File = $best }
}

function Get-LocalToolVersion {
    if (Test-Path $versionFile) {
        try {
            $text = (Get-Content -Path $versionFile -Raw -ErrorAction Stop).Trim()
            if ($text -match "(\d+\.\d+\.\d+)") { return [version]$Matches[1] }
        } catch { }
    }
    return $null
}

function Get-LocalVersion {
    $tool = Get-LocalToolVersion
    if ($null -ne $tool) {
        return [pscustomobject]@{ Version = $tool; Source = "版本.txt" }
    }
    $mod = Get-LocalModVersion
    if ($null -ne $mod) {
        return [pscustomobject]@{ Version = $mod.Version; Source = "mods 里的模组" }
    }
    return [pscustomobject]@{ Version = [version]"0.0.0"; Source = "未知" }
}

function Test-Scripts([string]$root, [switch]$Quiet) {
    $files = @("一键多开.ps1", "控制台.ps1")
    $bad = 0
    foreach ($f in $files) {
        $p = Join-Path $root $f
        if (-not (Test-Path $p)) {
            if (-not $Quiet) { Say ("缺少脚本：" + $f) "Red" }
            $bad++
            continue
        }
        $tokens = $null
        $errors = $null
        [System.Management.Automation.Language.Parser]::ParseFile($p, [ref]$tokens, [ref]$errors) | Out-Null
        if ($errors -and $errors.Count -gt 0) {
            if (-not $Quiet) {
                Say ("脚本有语法错误：" + $f) "Red"
                foreach ($e in $errors) { Say ("  " + $e.Message) "DarkRed" }
            }
            $bad++
        }
    }
    return $bad
}

function Get-Token {
    $p = Join-Path $multi "github_token.txt"
    if (Test-Path $p) {
        try { return ((Get-Content -Path $p -Raw).Trim()) } catch { }
    }
    if ($env:GITHUB_TOKEN) { return $env:GITHUB_TOKEN }
    return $null
}

function Get-ApiRelease([string]$repo) {
    $headers = @{
        "User-Agent" = "mc-auto-miner-updater"
        "Accept" = "application/vnd.github+json"
    }
    $token = Get-Token
    if ($token) { $headers["Authorization"] = "token " + $token }
    return Invoke-RestMethod -Uri ("https://api.github.com/repos/" + $repo + "/releases/latest") -Headers $headers -TimeoutSec 25
}

function Get-ExpandedAssets([string]$repo, [string]$tag) {
    $uri = "https://github.com/" + $repo + "/releases/expanded_assets/" + $tag
    $page = Invoke-WebRequest -Uri $uri -UseBasicParsing -TimeoutSec 25
    $list = @()
    foreach ($m in [regex]::Matches($page.Content, 'href="([^"]*?/releases/download/[^"]+)"')) {
        $href = $m.Groups[1].Value
        if ($href -notmatch '^https?://') { $href = "https://github.com" + $href }
        $name = ($href -split '/')[-1]
        if ($name) { $name = [uri]::UnescapeDataString($name) }
        $list += [pscustomobject]@{ name = $name; browser_download_url = $href }
    }
    return $list
}

function Get-LatestInfo([string]$repo) {
    $rel = $null
    try { $rel = Get-ApiRelease $repo } catch { }

    $assets = @()
    if ($null -ne $rel) {
        $tag = $rel.tag_name
        $ver = ConvertTo-Version $tag
        $assets = @($rel.assets)
        $body = $rel.body
    } else {
        # API 被挡时，退回 releases 页面和 expanded_assets，不依赖 API。
        $page = Invoke-WebRequest -Uri ("https://github.com/" + $repo + "/releases") -UseBasicParsing -TimeoutSec 25
        $tag = $null
        if ($page.Content -match '/releases/tag/([^"''?#<> ]+)') { $tag = $Matches[1] }
        if (-not $tag) { throw "页面上没找到任何 Release，可能还没发布过版本。" }
        $ver = ConvertTo-Version $tag
        try { $assets = @(Get-ExpandedAssets $repo $tag) } catch { $assets = @() }
        $body = ""
    }

    $zipAsset = $null
    $jarAsset = $null
    $bestJarVer = [version]"0.0.0"
    foreach ($a in $assets) {
        if ($null -eq $zipAsset -and $a.name -like "multi-launcher-*.zip") { $zipAsset = $a }
        if ($a.name -match "autominer-(\d+\.\d+\.\d+)\.jar") {
            $v = [version]$Matches[1]
            if ($v -gt $bestJarVer) {
                $bestJarVer = $v
                $jarAsset = $a
            }
        }
    }

    $zipUrl = "https://github.com/" + $repo + "/releases/download/" + $tag + "/multi-launcher-" + $ver + ".zip"
    if ($null -ne $zipAsset -and $zipAsset.browser_download_url) { $zipUrl = $zipAsset.browser_download_url }

    $jarUrl = $null
    if ($null -ne $jarAsset -and $jarAsset.browser_download_url) { $jarUrl = $jarAsset.browser_download_url }

    return [pscustomobject]@{ Tag = $tag; Version = $ver; ZipUrl = $zipUrl; JarUrl = $jarUrl; Body = $body }
}

function Download-File([string]$Url, [string]$OutFile) {
    $lastError = "下载失败"
    for ($i = 1; $i -le 3; $i++) {
        try {
            if (Test-Path $OutFile) { Remove-Item $OutFile -Force }
            Invoke-WebRequest -Uri $Url -OutFile $OutFile -Headers @{ "User-Agent" = "mc-auto-miner-updater" } -TimeoutSec 240 -UseBasicParsing
            if ((Test-Path $OutFile) -and (Get-Item $OutFile).Length -gt 1000) { return }
            throw "下载文件为空或过小"
        } catch {
            $lastError = $_.Exception.Message
            if ($i -lt 3) { Start-Sleep -Seconds (2 * $i) }
        }
    }

    # Invoke-WebRequest 在某些网络下会被中途掐断，用系统自带 curl 再兜底一次。
    $curl = Get-Command curl.exe -ErrorAction SilentlyContinue
    if ($null -ne $curl) {
        if (Test-Path $OutFile) { Remove-Item $OutFile -Force }
        & $curl.Source -L --fail --retry 2 --connect-timeout 20 --silent --show-error -o $OutFile $Url
        if ($LASTEXITCODE -eq 0 -and (Test-Path $OutFile) -and (Get-Item $OutFile).Length -gt 1000) { return }
    }
    throw ("下载失败：" + $lastError)
}

function Show-Header {
    Clear-Host
    Say "==============================================" "Cyan"
    Say "        多开工具 · 一键更新" "Cyan"
    Say "==============================================" "Cyan"
    Say ("  目录：" + $multi) "DarkGray"
    $local = Get-LocalVersion
    Say ("  本机版本：v" + $local.Version + "（" + $local.Source + "）") "DarkGray"
    $mod = Get-LocalModVersion
    if ($null -eq $mod) {
        Say "  模组：没找到 mods\autominer-*.jar" "Yellow"
    } else {
        Say ("  模组：v" + $mod.Version + "（" + $mod.File.Name + "）") "DarkGray"
    }
    Say ""
}

function Do-SelfTest {
    Show-Header
    $bad = 0
    $bad += (Test-Scripts $multi)
    $local = Get-LocalVersion
    Say ("自检：本机版本 v" + $local.Version) "Green"
    if ($bad -eq 0) {
        Say "自检通过：脚本语法 0 错误" "Green"
        return 0
    }
    Say ("自检失败：脚本问题 " + $bad + " 处") "Red"
    return 2
}

if ($SelfTest) {
    exit (Do-SelfTest)
}

Show-Header

# ---------- 查最新版本 ----------
Say "正在查询 GitHub 最新版本…" "Gray"
$release = $null
try {
    $release = Get-LatestInfo $Repo
} catch {
    Say "查不到最新版本（网络被墙/断网，或仓库还没发布过 Release）：" "Red"
    Say ("  " + $_.Exception.Message) "DarkRed"
    Say "如果只是 api.github.com 被挡，可以在本目录放一个 github_token.txt 再试。" "Yellow"
    Log ("查询失败：" + $_.Exception.Message)
    exit 3
}

$remoteVer = [version]$release.Version
$localInfo = Get-LocalVersion
$localVer = [version]$localInfo.Version

Say ("最新版本：v" + $remoteVer + "（" + $release.Tag + "）") "Green"
Say ("本机版本：v" + $localVer + "（" + $localInfo.Source + "）") "Gray"
if ($release.Body) {
    $note = ($release.Body -split "`n" | Select-Object -First 4) -join " / "
    Say ("更新说明：" + $note) "DarkGray"
}
Say ""

if (-not $CheckOnly -and -not $Force -and $remoteVer -le $localVer) {
    Say "已经是最新版本，不用更新。" "Green"
    Log "已是最新"
    Say ""
    if (-not $NoPause) { Read-Host "按回车关闭" }
    exit 0
}
if ($CheckOnly) {
    if ($remoteVer -gt $localVer) {
        Say "有新版本，可以更新。" "Yellow"
    } else {
        Say "已是最新。" "Green"
    }
    exit 0
}

# ---------- 确认 ----------
Say "更新会做这些事：" "White"
Say "  1. 先把当前脚本和 mods 备份一份（除非加 -NoBackup）" "Gray"
Say "  2. 用新版本的 一键多开.ps1、控制台.ps1、bat、模组替换" "Gray"
Say "  3. 保留 矿工配置.txt、服务器地址.txt、游戏目录.txt、启动模板.bat、bots、宏" "Gray"
Say ""
$answer = "y"
if (-not $Yes) { $answer = Read-Host "开始更新？(y/n)" }
if ($answer -ne "y" -and $answer -ne "Y") {
    Say "已取消。" "Yellow"
    exit 0
}

$baseTmp = $env:TEMP
if (-not $baseTmp) { $baseTmp = [System.IO.Path]::GetTempPath() }
if (-not $baseTmp) { $baseTmp = $multi }
$tmpRoot = Join-Path $baseTmp ("amm_update_" + (Get-Date -Format "yyyyMMddHHmmss"))
$extract = Join-Path $tmpRoot "pkg"
New-Item -ItemType Directory -Path $tmpRoot -Force | Out-Null

$bak = $null
try {
    # ---------- 下载脚本包（模组优先用包里自带的，避免单独下载 404） ----------
    $headers = @{ "User-Agent" = "mc-auto-miner-updater" }
    $zipPath = Join-Path $tmpRoot ("multi-launcher-" + $remoteVer + ".zip")

    Say ("下载脚本包：" + (Split-Path -Leaf $zipPath)) "Gray"
    Download-File $release.ZipUrl $zipPath
    if (-not (Test-Path $zipPath)) { throw "脚本包没下载下来。" }
    if ((Get-Item $zipPath).Length -lt 1000) { throw "脚本包太小，可能下载失败。" }

    # ---------- 解包 ----------
    Say "解包…" "Gray"
    Expand-Archive -Path $zipPath -DestinationPath $extract -Force
    $pkgRoot = $extract
    $found = @(Get-ChildItem -Path $extract -Recurse -Filter "一键多开.ps1" -ErrorAction SilentlyContinue)
    if ($found.Count -gt 0) { $pkgRoot = $found[0].Directory.FullName }
    if ((Test-Scripts $pkgRoot) -gt 0) { throw "新包里的脚本语法有问题，先不替换。" }

    # 新包里的模组版本可能跟 tag 不一样（例如 v1.15.7 里带的是 autominer-1.15.6.jar）。
    $newJar = $null
    $pkgMods = Join-Path $pkgRoot "mods"
    if (Test-Path $pkgMods) {
        $bestJarVer = [version]"0.0.0"
        foreach ($j in @(Get-ChildItem -Path $pkgMods -Filter "autominer-*.jar" -ErrorAction SilentlyContinue)) {
            if ($j.Name -match "autominer-(\d+\.\d+\.\d+)\.jar") {
                $v = [version]$Matches[1]
                if ($v -gt $bestJarVer) {
                    $bestJarVer = $v
                    $newJar = $j.FullName
                }
            }
        }
    }
    if ($null -eq $newJar -and $release.JarUrl) {
        $jarPath = Join-Path $tmpRoot ("autominer-" + $remoteVer + ".jar")
        Say ("包里没带模组，单独下载：" + (Split-Path -Leaf $jarPath)) "Gray"
        Download-File $release.JarUrl $jarPath
        if ((Test-Path $jarPath) -and (Get-Item $jarPath).Length -gt 10000) { $newJar = $jarPath }
    }
    if ($null -eq $newJar -or -not (Test-Path $newJar)) { throw "新包里没有 autominer 模组，也没有可用的下载地址。" }

    # ---------- 备份 ----------
    if (-not $NoBackup) {
        $bak = Join-Path $multi ("_备份_" + (Get-Date -Format "yyyyMMdd-HHmmss"))
        New-Item -ItemType Directory -Path $bak -Force | Out-Null
        foreach ($f in @("一键多开.ps1", "控制台.ps1", "启动多开.bat", "控制台.bat", "start.bat", "console.bat", "启动方式说明.txt", "指令生成器.html", "更新工具.ps1", "一键更新.bat", "版本.txt")) {
            $p = Join-Path $multi $f
            if (Test-Path $p) { Copy-Item $p (Join-Path $bak $f) -Force }
        }
        $modsDir = Join-Path $multi "mods"
        if (Test-Path $modsDir) {
            Copy-Item $modsDir (Join-Path $bak "mods") -Recurse -Force
        }
        Say ("已备份到：" + (Split-Path -Leaf $bak)) "DarkGray"
    }

    # ---------- 替换脚本（不碰 启动模板.bat 和 矿工配置.txt） ----------
    # 更新器本身不在替换列表里：否则会被旧包里的旧更新器覆盖，下次又会坏。
    $scriptFiles = @("一键多开.ps1", "控制台.ps1", "启动方式说明.txt", "启动多开.bat", "控制台.bat", "start.bat", "console.bat", "指令生成器.html", "回箱前脚本示例.txt", "Minecraft工具使用教程.md")
    foreach ($f in $scriptFiles) {
        $src = Join-Path $pkgRoot $f
        if (Test-Path $src) {
            Copy-Item $src (Join-Path $multi $f) -Force
        }
    }

    # ---------- 替换模组（只动 autominer，别的不碰） ----------
    $mods = Join-Path $multi "mods"
    if (-not (Test-Path $mods)) { New-Item -ItemType Directory -Path $mods -Force | Out-Null }
    Get-ChildItem -Path $mods -Filter "autominer-*.jar" -ErrorAction SilentlyContinue | Remove-Item -Force
    Copy-Item $newJar (Join-Path $mods (Split-Path -Leaf $newJar)) -Force

    # ---------- 替换信息工具（新包里带 infotools 才换，没带就保留） ----------
    $newInfo = @(Get-ChildItem -Path (Join-Path $pkgRoot "mods") -Filter "infotools-*.jar" -ErrorAction SilentlyContinue | Sort-Object Name -Descending)
    if ($newInfo.Count -gt 0) {
        Get-ChildItem -Path $mods -Filter "infotools-*.jar" -ErrorAction SilentlyContinue | Remove-Item -Force
        Copy-Item $newInfo[0].FullName (Join-Path $mods $newInfo[0].Name) -Force
        Say ("已更新信息工具：" + $newInfo[0].Name) "Green"
    }

    # ---------- 复查并记版本 ----------
    if ((Test-Scripts $multi) -gt 0) { throw "替换后脚本语法检查没过。" }
    Set-Content -Path $versionFile -Value ("v" + $remoteVer) -Encoding UTF8
    $after = Get-LocalModVersion
    Say ""
    Say ("更新完成：v" + $localVer + " → v" + $remoteVer) "Green"
    if ($null -ne $after) { Say ("当前 mods 里：" + $after.File.Name) "Gray" }
    if ($null -ne $bak) { Say ("旧版本在：" + $bak) "DarkGray" }
    Say "记得先关掉已打开的多开/控制台窗口，再重新双击 启动多开.bat。" "Yellow"
    Log ("更新成功 " + $localVer + " -> " + $remoteVer)
} catch {
    Say ""
    Say ("更新失败：" + $_.Exception.Message) "Red"
    Log ("更新失败：" + $_.Exception.Message)

    # 有备份就回滚脚本和模组
    if ($null -ne $bak -and (Test-Path $bak)) {
        Say "正在回滚到备份…" "Yellow"
        foreach ($f in @("一键多开.ps1", "控制台.ps1", "启动方式说明.txt", "启动多开.bat", "控制台.bat", "start.bat", "console.bat", "指令生成器.html", "更新工具.ps1", "一键更新.bat", "回箱前脚本示例.txt", "Minecraft工具使用教程.md", "版本.txt")) {
            $p = Join-Path $bak $f
            if (Test-Path $p) { Copy-Item $p (Join-Path $multi $f) -Force }
        }
        $bakMods = Join-Path $bak "mods"
        if (Test-Path $bakMods) {
            $mods = Join-Path $multi "mods"
            if (-not (Test-Path $mods)) { New-Item -ItemType Directory -Path $mods -Force | Out-Null }
            Get-ChildItem -Path $mods -Filter "autominer-*.jar" -ErrorAction SilentlyContinue | Remove-Item -Force
            Get-ChildItem -Path $mods -Filter "infotools-*.jar" -ErrorAction SilentlyContinue | Remove-Item -Force
            foreach ($j in @(Get-ChildItem -Path $bakMods -Filter "*.jar" -ErrorAction SilentlyContinue)) {
                Copy-Item $j.FullName (Join-Path $mods $j.Name) -Force
            }
        }
        Say "已回滚，还是原来的版本。" "Green"
    }
} finally {
    try { if (Test-Path $tmpRoot) { Remove-Item $tmpRoot -Recurse -Force } } catch { }
}

Say ""
if (-not $NoPause) { Read-Host "按回车关闭" }
