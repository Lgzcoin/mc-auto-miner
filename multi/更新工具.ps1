# 多开工具一键更新器
# 作用：去 GitHub 看有没有新版本，有就下载、备份、替换脚本和模组，保留你自己的配置。
# 用法：双击 一键更新.bat；或 powershell -File 更新工具.ps1 -CheckOnly
[CmdletBinding()]
param(
    [string]$Repo = "Lgzcoin/mc-auto-miner",
    [switch]$CheckOnly,
    [switch]$Force,
    [switch]$NoBackup,
    [switch]$SelfTest
)

$ErrorActionPreference = "Stop"
try { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 } catch { }

$multi = Split-Path -Parent $MyInvocation.MyCommand.Path
$logFile = Join-Path $multi "更新日志.txt"

function Say([string]$text, [string]$color = "Gray") {
    Write-Host $text -ForegroundColor $color
}

function Log([string]$text) {
    try {
        $line = "[" + (Get-Date -Format "yyyy-MM-dd HH:mm:ss") + "] " + $text
        Add-Content -Path $logFile -Value $line -Encoding UTF8
    } catch { }
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
            $v = $Matches[1]
            if ($null -eq $bestVer -or ([version]$v -gt [version]$bestVer)) {
                $bestVer = $v
                $best = $j
            }
        }
    }
    if ($null -eq $bestVer) { return $null }
    return [pscustomobject]@{ Version = $bestVer; File = $best }
}

function ConvertTo-Version([string]$text) {
    if ($text -match "(\d+\.\d+\.\d+)") { return [version]$Matches[1] }
    return [version]"0.0.0"
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
        try { return ((Get-Content $p -Raw).Trim()) } catch { }
    }
    if ($env:GITHUB_TOKEN) { return $env:GITHUB_TOKEN }
    return $null
}

# 两条路：有 token 走 API；没有 token 就扒 releases 页面的最新 tag（api.github.com 在部分网络下会被 403）
function Get-LatestInfo([string]$repo) {
    $token = Get-Token
    if ($token) {
        $headers = @{ "User-Agent" = "mc-auto-miner-updater"; "Accept" = "application/vnd.github+json"; "Authorization" = ("token " + $token) }
        $rel = Invoke-RestMethod -Uri ("https://api.github.com/repos/" + $repo + "/releases/latest") -Headers $headers -TimeoutSec 25
        $tag = $rel.tag_name
        $ver = ConvertTo-Version $tag
        $zipUrl = "https://github.com/" + $repo + "/releases/download/" + $tag + "/multi-launcher-" + $ver + ".zip"
        $jarUrl = "https://github.com/" + $repo + "/releases/download/" + $tag + "/autominer-" + $ver + ".jar"
        foreach ($a in $rel.assets) {
            if ($a.name -like "multi-launcher-*.zip") { $zipUrl = $a.browser_download_url }
            if ($a.name -like "autominer-*.jar") { $jarUrl = $a.browser_download_url }
        }
        return [pscustomobject]@{ Tag = $tag; Version = $ver; ZipUrl = $zipUrl; JarUrl = $jarUrl; Body = $rel.body }
    }

    $page = Invoke-WebRequest -Uri ("https://github.com/" + $repo + "/releases") -UseBasicParsing -TimeoutSec 25
    $html = $page.Content
    $tag = $null
    if ($html -match '/releases/tag/([^"?#<> ]+)') { $tag = $Matches[1] }
    if (-not $tag) { throw "页面上没找到任何 Release，可能还没发布过版本。" }
    $ver = ConvertTo-Version $tag
    $zipUrl = "https://github.com/" + $repo + "/releases/download/" + $tag + "/multi-launcher-" + $ver + ".zip"
    $jarUrl = "https://github.com/" + $repo + "/releases/download/" + $tag + "/autominer-" + $ver + ".jar"
    return [pscustomobject]@{ Tag = $tag; Version = $ver; ZipUrl = $zipUrl; JarUrl = $jarUrl; Body = "" }
}

function Show-Header {
    Clear-Host
    Say "==============================================" "Cyan"
    Say "        多开工具 · 一键更新" "Cyan"
    Say "==============================================" "Cyan"
    Say ("  目录：" + $multi) "DarkGray"
    $local = Get-LocalModVersion
    if ($null -eq $local) {
        Say "  本机版本：没找到 mods\autominer-*.jar" "Yellow"
    } else {
        Say ("  本机版本：v" + $local.Version + "（" + $local.File.Name + "）") "DarkGray"
    }
    Say ""
}

function Do-SelfTest {
    Show-Header
    $bad = 0
    $bad += (Test-Scripts $multi)
    $local = Get-LocalModVersion
    if ($null -eq $local) {
        Say "自检：没找到本机模组 jar（不影响更新，但会当成旧版本）" "Yellow"
    } else {
        Say ("自检：本机模组版本 v" + $local.Version) "Green"
    }
    if (-not (Test-Path (Join-Path $multi "bots"))) {
        Say "自检：bots 目录不存在（先跑一次 启动多开.bat 就会生成）" "Yellow"
    }
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
$local = Get-LocalModVersion
$localVer = [version]"0.0.0"
if ($null -ne $local) { $localVer = [version]$local.Version }

Say ("最新版本：v" + $remoteVer + "（" + $release.Tag + "）") "Green"
Say ("本机版本：v" + $localVer) "Gray"
if ($release.Body) {
    $note = ($release.Body -split "`n" | Select-Object -First 4) -join " / "
    Say ("更新说明：" + $note) "DarkGray"
}
Say ""

if (-not $CheckOnly -and -not $Force -and $remoteVer -le $localVer) {
    Say "已经是最新版本，不用更新。" "Green"
    Log "已是最新"
    Say ""
    Read-Host "按回车关闭"
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
Say "  3. 保留 矿工配置.txt、服务器地址.txt、游戏目录.txt、启动模板.bat、bots" "Gray"
Say ""
$yes = Read-Host "开始更新？(y/n)"
if ($yes -ne "y" -and $yes -ne "Y") {
    Say "已取消。" "Yellow"
    exit 0
}

$baseTmp = $env:TEMP
if (-not $baseTmp) { $baseTmp = [System.IO.Path]::GetTempPath() }
if (-not $baseTmp) { $baseTmp = $multi }
$tmpRoot = Join-Path $baseTmp ("amm_update_" + (Get-Date -Format "yyyyMMddHHmmss"))
$extract = Join-Path $tmpRoot "pkg"
New-Item -ItemType Directory -Path $tmpRoot -Force | Out-Null

try {
    # ---------- 下载 ----------
    $headers = @{ "User-Agent" = "mc-auto-miner-updater" }
    $zipPath = Join-Path $tmpRoot ("multi-launcher-" + $remoteVer + ".zip")
    $jarPath = Join-Path $tmpRoot ("autominer-" + $remoteVer + ".jar")

    Say ("下载脚本包：" + (Split-Path -Leaf $zipPath)) "Gray"
    Invoke-WebRequest -Uri $release.ZipUrl -OutFile $zipPath -Headers $headers -TimeoutSec 240
    if (-not (Test-Path $zipPath)) { throw "脚本包没下载下来。" }
    if ((Get-Item $zipPath).Length -lt 1000) { throw "脚本包太小，可能下载失败。" }

    Say ("下载模组：" + (Split-Path -Leaf $jarPath)) "Gray"
    Invoke-WebRequest -Uri $release.JarUrl -OutFile $jarPath -Headers $headers -TimeoutSec 240
    if (-not (Test-Path $jarPath)) { throw "模组 jar 没下载下来。" }
    if ((Get-Item $jarPath).Length -lt 10000) { throw "模组 jar 太小，可能下载失败。" }

    # ---------- 解包 ----------
    Say "解包…" "Gray"
    Expand-Archive -Path $zipPath -DestinationPath $extract -Force
    # 包里可能多套一层目录（例如 multi\），找真正放脚本的那一层
    $pkgRoot = $extract
    $found = @(Get-ChildItem -Path $extract -Recurse -Filter "一键多开.ps1" -ErrorAction SilentlyContinue)
    if ($found.Count -gt 0) { $pkgRoot = $found[0].Directory.FullName }
    if ((Test-Scripts $pkgRoot) -gt 0) { throw "新包里的脚本语法有问题，先不替换。" }

    # ---------- 备份 ----------
    $bak = $null
    if (-not $NoBackup) {
        $bak = Join-Path $multi ("_备份_" + (Get-Date -Format "yyyyMMdd-HHmmss"))
        New-Item -ItemType Directory -Path $bak -Force | Out-Null
        foreach ($f in @("一键多开.ps1", "控制台.ps1", "启动多开.bat", "控制台.bat", "启动方式说明.txt")) {
            $p = Join-Path $multi $f
            if (Test-Path $p) { Copy-Item $p (Join-Path $bak $f) -Force }
        }
        $modsDir = Join-Path $multi "mods"
        if (Test-Path $modsDir) {
            Copy-Item $modsDir (Join-Path $bak "mods") -Recurse -Force
        }
        Say ("已备份到：" + (Split-Path -Leaf $bak)) "DarkGray"
    }

    # ---------- 替换脚本 ----------
    $scriptFiles = @("一键多开.ps1", "控制台.ps1", "启动方式说明.txt", "启动多开.bat", "控制台.bat")
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
    $newJar = $jarPath
    if ($null -eq $newJar) {
        $cand = @(Get-ChildItem -Path (Join-Path $pkgRoot "mods") -Filter "autominer-*.jar" -ErrorAction SilentlyContinue)
        if ($cand.Count -gt 0) { $newJar = $cand[0].FullName }
    }
    if ($null -ne $newJar -and (Test-Path $newJar)) {
        Copy-Item $newJar (Join-Path $mods (Split-Path -Leaf $newJar)) -Force
    } else {
        throw "没拿到新模组 jar。"
    }

    # ---------- 复查 ----------
    if ((Test-Scripts $multi) -gt 0) { throw "替换后脚本语法检查没过。" }
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

    # 有备份就回滚脚本
    if ($null -ne $bak -and (Test-Path $bak)) {
        Say "正在回滚到备份…" "Yellow"
        foreach ($f in @("一键多开.ps1", "控制台.ps1", "启动方式说明.txt", "启动多开.bat", "控制台.bat")) {
            $p = Join-Path $bak $f
            if (Test-Path $p) { Copy-Item $p (Join-Path $multi $f) -Force }
        }
        Say "已回滚，还是原来的版本。" "Green"
    }
} finally {
    try { if (Test-Path $tmpRoot) { Remove-Item $tmpRoot -Recurse -Force } } catch { }
}

Say ""
Read-Host "按回车关闭"
