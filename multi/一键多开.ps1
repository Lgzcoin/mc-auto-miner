param(
    [string]$Bots = "矿工1,矿工2",
    [string]$Server = "",
    [string]$Template = "",
    [string]$GameRoot = "",
    [string]$Version = "",
    [string]$JavaExe = "",
    [string]$ModsSource = "",
    [int]$MemoryMB = 2048,
    [string]$PlanFile = "",
    [int]$TriggerCount = 64,
    [string]$Auth = "",
    [switch]$JustJoin,
    [string]$Gui = "",
    [switch]$IgnoreModCheck,
    [switch]$RefreshConfig,
    [switch]$PrintOnly,
    [switch]$NoSetup
)

# ============================================================
#  自动采矿助手 —— 一键多开启动器
#  两种模式：
#   1) 模板模式（推荐，配合 PCL）：读 PCL 导出的启动脚本，
#      复用它的 java / classpath / 全部参数，只换用户名、UUID、游戏目录
#   2) 自带拼装模式：不提供模板时，脚本按 versions 目录自己拼 classpath
#  用法：双击同目录的“启动多开.bat”，或在 PowerShell 里跑本脚本
# ============================================================

# 控制台显示中文：把窗口代码页和输出编码都切成 UTF-8，避免中文变乱码
try {
    $chcp = & "$env:SystemRoot\System32\chcp.com" 65001 2>$null
    [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
    $OutputEncoding = New-Object System.Text.UTF8Encoding($false)
} catch { }

$ErrorActionPreference = "Stop"

function Fail([string]$msg) {
    Write-Host ""
    Write-Host "[错误] $msg" -ForegroundColor Red
    exit 1
}

function Find-Java {
    if ($JavaExe -and (Test-Path $JavaExe)) { return $JavaExe }
    $cmd = Get-Command java -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\java.exe")) { return "$env:JAVA_HOME\bin\java.exe" }
    $where = cmd /c "where java.exe 2>nul"
    if ($where) { return ($where | Select-Object -First 1).Trim() }
    $bases = @(
        "$env:ProgramFiles\Java",
        "${env:ProgramFiles(x86)}\Java",
        "$env:ProgramFiles\Eclipse Adoptium",
        "$env:LOCALAPPDATA\Programs\Eclipse Adoptium",
        "$env:ProgramFiles\Microsoft\jdk"
    )
    $found = @()
    foreach ($b in $bases) {
        if (Test-Path $b) {
            $found += Get-ChildItem $b -Directory -ErrorAction SilentlyContinue |
                ForEach-Object { Join-Path $_.FullName "bin\java.exe" } |
                Where-Object { Test-Path $_ }
        }
    }
    if ($found.Count -gt 0) { return ($found | Sort-Object -Descending | Select-Object -First 1) }
    return $null
}

function Get-JavaMajor([string]$exe) {
    if (-not $exe) { return 0 }
    if (-not (Test-Path $exe)) { return 0 }
    # 用 cmd 抓输出：PowerShell 里重定向原生 stderr 在 Stop 模式下会抛异常，读不到版本号
    $out = ""
    try {
        $out = ((cmd /c "`"$exe`" -version 2>&1") -join " ")
    } catch { $out = "" }
    if (-not $out) {
        $prev = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        try { $out = (& $exe -version 2>&1 | Out-String) } catch { $out = "" }
        $ErrorActionPreference = $prev
    }
    if ($out -match 'version\s+"(\d+)') {
        $m = [int]$Matches[1]
        if ($m -eq 1 -and $out -match 'version\s+"1\.(\d+)') { $m = [int]$Matches[1] }
        return $m
    }
    if ($out -match 'openjdk\s+(\d+)') { return [int]$Matches[1] }
    return 0
}

function Find-JavaForVersion([int]$minMajor) {
    $roots = @(
        "$env:APPDATA\.minecraft\java",
        "$env:APPDATA\.minecraft\runtime",
        "$env:LOCALAPPDATA\Packages\Microsoft.4297127D64EC6_8wekyb3d8bbwe\LocalCache\Local\runtime",
        "${env:ProgramFiles(x86)}\Minecraft Launcher\runtime",
        "$env:APPDATA\PCL",
        "$env:LOCALAPPDATA\Programs",
        "$env:USERPROFILE\.jdks",
        "$env:ProgramFiles\Java",
        "${env:ProgramFiles(x86)}\Java",
        "$env:ProgramFiles\Eclipse Adoptium",
        "$env:ProgramFiles\Zulu",
        "$env:ProgramFiles\Microsoft\jdk",
        "$env:ProgramFiles\BellSoft"
    )
    $found = @()
    foreach ($r in $roots) {
        if (Test-Path $r) {
            try {
                $found += @(Get-ChildItem $r -Filter "java.exe" -Recurse -Depth 5 -ErrorAction SilentlyContinue |
                    ForEach-Object { $_.FullName })
            } catch { }
        }
    }
    $found = @($found | Select-Object -Unique)
    Write-Host ("在 " + $found.Count + " 个 java.exe 里找 " + $minMajor + " 以上的 ...") -ForegroundColor Gray
    foreach ($f in $found) {
        $m = Get-JavaMajor $f
        if ($m -ge $minMajor) {
            Write-Host ("  用这个：" + $f + "（Java " + $m + "）") -ForegroundColor Green
            return $f
        }
    }
    return $null
}

function Test-Allowed($lib) {
    if (-not $lib.rules) { return $true }
    $allowed = $false
    foreach ($rule in $lib.rules) {
        $osName = $null
        if ($rule.os) { $osName = $rule.os.name }
        if ($osName -and $osName -ne "windows") { continue }
        if ($rule.action -eq "allow") { $allowed = $true }
        elseif ($rule.action -eq "disallow") { $allowed = $false }
    }
    return $allowed
}

# ---------- 从 PCL 日志自动抓启动命令 ----------
function Get-CommandFromLog([string]$path) {
    $lines = @(Get-Content $path -Encoding UTF8 -ErrorAction SilentlyContinue)
    if ($lines.Count -eq 0) { return $null }
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -notmatch "Minecraft 启动参数|net\.minecraft\.client\.main\.Main|JavaWrapper|BootstrapLauncher") { continue }
        $buf = ""
        $end = [Math]::Min($i + 40, $lines.Count)
        for ($j = $i; $j -lt $end; $j++) {
            $seg = $lines[$j]
            $seg = [regex]::Replace($seg, '^\s*\[\d{2}:\d{2}:\d{2}(\.\d+)?\]\s*', '')
            $seg = [regex]::Replace($seg, '^\[[^\]]*\]\s*', '')
            $seg = [regex]::Replace($seg, '^Minecraft 启动参数[：:\s]*', '')
            $seg = $seg.Trim()
            if ($seg -eq "") { continue }
            if ($seg.StartsWith("-") -or $seg -match "\.exe" -or $seg -match " --") {
                $buf = ($buf + " " + $seg).Trim()
            } elseif ($buf.Length -gt 0) {
                break
            }
            if ($buf -match "--username" -and ($buf -match "-cp " -or $buf -match "-jar ")) { return $buf }
        }
        if ($buf -match "--username") { return $buf }
    }
    return $null
}

function Find-PclLaunchCommand {
    $dirs = @("$env:APPDATA\PCL", "$env:LOCALAPPDATA\PCL", "$env:APPDATA\Roaming\PCL")
    $logs = @()
    foreach ($d in $dirs) {
        if (Test-Path $d) {
            $logs += @(Get-ChildItem $d -Filter "Log*.txt" -ErrorAction SilentlyContinue |
                Sort-Object LastWriteTime -Descending)
        }
    }
    foreach ($lg in $logs) {
        $c = Get-CommandFromLog $lg.FullName
        if ($c) { return @{ cmd = $c; src = $lg.FullName } }
    }
    return $null
}

# ---------- 模板解析 ----------
function Get-TemplateCommand([string]$path) {
    $raw = Get-Content $path -Raw -Encoding UTF8
    if (-not $raw) { $raw = Get-Content $path -Raw }
    foreach ($l in ($raw -split "`r?`n")) {
        $t = $l.Trim()
        if ($t -eq "") { continue }
        if ($t.StartsWith("@")) { continue }
        if ($t -match "(?i)^(rem|::)") { continue }
        if ($t -match "net\.minecraft\.client\.main\.Main" -or
            $t -match "BootstrapLauncher" -or
            $t -match "net\.fabricmc\.loader" -or
            $t -match "-cp\s") {
            return $t
        }
    }
    return $null
}

function Split-CmdLine([string]$line) {
    if ($line -match '^"([^"]+)"\s*(.*)$') { return @($Matches[1], $Matches[2].Trim()) }
    $i = $line.IndexOf(" ")
    if ($i -lt 0) { return @($line, "") }
    return @($line.Substring(0, $i), $line.Substring($i + 1).Trim())
}

function Set-CmdArg([string]$cmd, [string]$argName, [string]$newValue) {
    $p = '--' + $argName + '\s+("[^"]*"|[^\s]+)'
    if ([regex]::IsMatch($cmd, $p)) {
        return [regex]::Replace($cmd, $p, ('--' + $argName + ' ' + $newValue))
    }
    return ($cmd + ' --' + $argName + ' ' + $newValue)
}

function Get-CmdArg([string]$cmd, [string]$argName) {
    $p = '--' + $argName + '\s+"([^"]+)"'
    if ($cmd -match $p) { return $Matches[1] }
    $p2 = '--' + $argName + '\s+([^\s]+)'
    if ($cmd -match $p2) { return $Matches[1] }
    return $null
}

# ---------- 矿种 → 方块/掉落物 ----------
$OreMap = @{
    "coal"       = @{ blocks = @("minecraft:coal_ore", "minecraft:deepslate_coal_ore"); drops = @("minecraft:coal") }
    "iron"       = @{ blocks = @("minecraft:iron_ore", "minecraft:deepslate_iron_ore"); drops = @("minecraft:raw_iron") }
    "copper"     = @{ blocks = @("minecraft:copper_ore", "minecraft:deepslate_copper_ore"); drops = @("minecraft:raw_copper") }
    "gold"       = @{ blocks = @("minecraft:gold_ore", "minecraft:deepslate_gold_ore"); drops = @("minecraft:raw_gold") }
    "nether_gold"= @{ blocks = @("minecraft:nether_gold_ore"); drops = @("minecraft:gold_nugget") }
    "redstone"   = @{ blocks = @("minecraft:redstone_ore", "minecraft:deepslate_redstone_ore"); drops = @("minecraft:redstone") }
    "lapis"      = @{ blocks = @("minecraft:lapis_ore", "minecraft:deepslate_lapis_ore"); drops = @("minecraft:lapis_lazuli") }
    "diamond"    = @{ blocks = @("minecraft:diamond_ore", "minecraft:deepslate_diamond_ore"); drops = @("minecraft:diamond") }
    "emerald"    = @{ blocks = @("minecraft:emerald_ore", "minecraft:deepslate_emerald_ore"); drops = @("minecraft:emerald") }
    "quartz"     = @{ blocks = @("minecraft:nether_quartz_ore"); drops = @("minecraft:quartz") }
    "debris"     = @{ blocks = @("minecraft:ancient_debris"); drops = @("minecraft:ancient_debris", "minecraft:netherite_scrap") }
    "amethyst"   = @{ blocks = @("minecraft:amethyst_cluster"); drops = @("minecraft:amethyst_shard") }
}
$OreAlias = @{
    "煤" = "coal"; "煤矿" = "coal"; "铁" = "iron"; "铁矿" = "iron"; "铜" = "copper"; "铜矿" = "copper"
    "金" = "gold"; "金矿" = "gold"; "下界金" = "nether_gold"; "红石" = "redstone"; "青金石" = "lapis"
    "钻石" = "diamond"; "钻石矿" = "diamond"; "绿宝石" = "emerald"; "石英" = "quartz"; "下界石英" = "quartz"
    "残骸" = "debris"; "远古残骸" = "debris"; "紫水晶" = "amethyst"
    "煤炭" = "coal"; "煤炭矿" = "coal"; "铁矿石" = "iron"; "铜矿石" = "copper"
    "金矿石" = "gold"; "红石矿" = "redstone"; "青金石矿" = "lapis"; "钻石矿石" = "diamond"
    "绿宝石矿" = "emerald"; "石英矿" = "quartz"; "紫水晶簇" = "amethyst"
}

$OreCn = @{
    "coal" = "煤"; "iron" = "铁"; "copper" = "铜"; "gold" = "金"
    "nether_gold" = "下界金"; "redstone" = "红石"; "lapis" = "青金石"
    "diamond" = "钻石"; "emerald" = "绿宝石"; "quartz" = "石英"
    "debris" = "远古残骸"; "amethyst" = "紫水晶"
}

# 通用读文本：按字节判断 UTF-8 / 系统编码，避免记事本存成 ANSI 就读不出中文
function Read-TextFile([string]$path) {
    if (-not (Test-Path $path)) { return "" }
    $bytes = [System.IO.File]::ReadAllBytes($path)
    if ($bytes.Length -eq 0) { return "" }
    $utf8 = [System.Text.Encoding]::UTF8.GetString($bytes)
    if ($utf8.IndexOf([char]0xFFFD, 0) -ge 0) {
        $utf8 = [System.Text.Encoding]::Default.GetString($bytes)
    }
    return ($utf8 -replace "\uFEFF", "")
}

function Resolve-OreKey([string]$raw) {
    $t = $raw.Trim()
    if ($OreAlias.ContainsKey($t)) { return $OreAlias[$t] }
    $k = $t.ToLower()
    if ($OreMap.ContainsKey($k)) { return $k }
    return $null
}

function Convert-Pos3([string]$txt) {
    if ([string]::IsNullOrWhiteSpace($txt)) { return $null }
    $nums = @($txt -split "[,\s]+" | Where-Object { $_ -ne "" })
    if ($nums.Count -ne 3) { return $null }
    $out = @()
    foreach ($n in $nums) {
        $v = 0
        if (-not [int]::TryParse($n, [ref]$v)) { return $null }
        $out += $v
    }
    return ,$out
}

function Get-PosText($pos) {
    if (-not $pos -or @($pos).Count -ne 3) { return "" }
    return ((@($pos) -join ", "))
}

function Get-JsonArray([string[]]$items) {
    return "[" + (($items | ForEach-Object { '"' + $_ + '"' }) -join ", ") + "]"
}

function New-BotConfig([string]$baseText, [string[]]$blocks, [string[]]$drops, [string]$triggerItem, [int]$triggerCount, [string]$authText, [bool]$autoStartOnJoin = $true, [string]$joinServer = "", [string]$guiSpec = "", [int]$retrySeconds = 10) {
    $old = $null
    if (-not [string]::IsNullOrWhiteSpace($baseText)) {
        try { $old = $baseText | ConvertFrom-Json } catch { $old = $null }
    }
    $o = [ordered]@{}
    $o["enabled"] = $true
    if ($old -and $old.chests) { $o["chests"] = @($old.chests) } else { $o["chests"] = @() }

    if ([string]::IsNullOrWhiteSpace($authText)) {
        if ($old -and $old.autoAuthCommands) { $o["autoAuthCommands"] = [string]$old.autoAuthCommands } else { $o["autoAuthCommands"] = "" }
    } else {
        $o["autoAuthCommands"] = $authText
    }
    $o["autoAuthDelaySeconds"] = 1

    if ([string]::IsNullOrWhiteSpace($joinServer)) {
        if ($old -and $old.autoJoinServer) { $o["autoJoinServer"] = [string]$old.autoJoinServer } else { $o["autoJoinServer"] = "" }
    } else {
        $o["autoJoinServer"] = $joinServer
    }
    $o["autoJoinDelaySeconds"] = 2
    $o["autoJoinRetrySeconds"] = $retrySeconds
    $o["autoJoinMaxAttempts"] = 30

    if (-not [string]::IsNullOrWhiteSpace($guiSpec)) {
        $gv = $guiSpec -split "\|", 3
        $kw = if ($gv.Count -ge 1 -and $gv[0].Trim() -ne "") { $gv[0].Trim() } else { "登录" }
        $sl = if ($gv.Count -ge 2) { [int]$gv[1].Trim() } else { -1 }
        $pw = if ($gv.Count -ge 3) { $gv[2] } else { "" }
        $o["guiLogin"] = [ordered]@{ enabled = $true; titleKeyword = $kw; clickSlot = $sl; slotKeyword = "登录,登入,登陆,进入,确认,密码,Login"; maxTries = 6; chatText = $pw; delaySeconds = 1 }
    } elseif ($old -and $old.guiLogin -and ($old.guiLogin.PSObject.Properties.Name -contains "slotKeyword") -and ($old.guiLogin.PSObject.Properties.Name -contains "maxTries")) {
        $o["guiLogin"] = $old.guiLogin
    } else {
        $o["guiLogin"] = [ordered]@{ enabled = $true; titleKeyword = "登录"; clickSlot = -1; slotKeyword = "登录,登入,登陆,进入,确认,密码,Login"; maxTries = 6; chatText = ""; delaySeconds = 1 }
    }

    $o["autoStartOnJoin"] = $autoStartOnJoin
    if ($old -and $old.intervalSeconds) { $o["intervalSeconds"] = [int]$old.intervalSeconds } else { $o["intervalSeconds"] = 900 }
    if ($old -and $old.returnRange) { $o["returnRange"] = [int]$old.returnRange } else { $o["returnRange"] = 2 }
    if ($old -and $old.depositAll) { $o["depositAll"] = [bool]$old.depositAll } else { $o["depositAll"] = $false }
    if ($old -and $old.keepItems) { $o["keepItems"] = @($old.keepItems) } else { $o["keepItems"] = @("minecraft:torch", "minecraft:water_bucket", "minecraft:bread") }

    $o["mineBlocks"] = @($blocks)
    $o["depositItems"] = @($drops)
    $o["depositTriggerItem"] = $triggerItem
    $o["depositTriggerCount"] = $triggerCount
    return ($o | ConvertTo-Json -Depth 8)
}

Write-Host ""
Write-Host "=== 自动采矿助手 · 一键多开 ===" -ForegroundColor Cyan

# ---------- 确定启动命令来源 ----------
$autoCmd = $null
$autoSrc = $null

if ([string]::IsNullOrWhiteSpace($Template)) {
    $guess = Join-Path $PSScriptRoot "启动模板.bat"
    if (Test-Path $guess) {
        $Template = $guess
    } else {
        $pcl = Find-PclLaunchCommand
        if ($pcl) {
            $autoCmd = $pcl.cmd
            $autoSrc = $pcl.src
            Write-Host "已从 PCL 日志自动读到启动命令：$autoSrc" -ForegroundColor Green
            Write-Host "（不用做任何事，直接用 PCL 那套参数启动机器人）" -ForegroundColor Gray
        } elseif (-not $NoSetup) {
            Write-Host ""
            Write-Host "没找到 启动模板.bat，也没在 PCL 日志里找到启动命令。" -ForegroundColor Yellow
            Write-Host "把 PCL 导出的启动脚本拖到这个窗口再回车；直接回车 = 脚本自己拼 java 参数（要 Java 25 且版本文件齐全）。"
            $inp = Read-Host "模板路径"
            if ($inp) { $Template = $inp.Trim('"').Trim("'").Trim() }
        }
    }
}

$useTemplate = ((-not [string]::IsNullOrWhiteSpace($Template)) -or ($autoCmd -ne $null))
$exePath = $null
$exeArgs = $null
$tplGameDir = $null

if ($useTemplate) {
    $baseCmd = $autoCmd
    if (-not $baseCmd) {
        if (-not (Test-Path $Template)) { Fail "找不到启动模板：$Template" }
        Write-Host "使用启动模板：$Template" -ForegroundColor Cyan
        $baseCmd = Get-TemplateCommand $Template
    }
    if (-not $baseCmd) {
        Fail "没找到启动命令行。文件里应该有 PCL 导出的那条 java 命令（含 net.minecraft.client.main.Main 或 -cp）。"
    }
    $sp = Split-CmdLine $baseCmd
    $exePath = $sp[0]
    $exeArgs = $sp[1]
    $tplGameDir = Get-CmdArg $exeArgs "gameDir"
    if (-not $exePath -or -not (Test-Path $exePath)) {
        $exePath = Find-Java
        if (-not $exePath) { Fail "启动命令里的 java 路径无效，也找不到其它 java。用 -JavaExe 指定。" }
    }
    if ($exeArgs -notmatch "net\.minecraft\.client\.main\.Main" -and
        $exeArgs -notmatch "BootstrapLauncher" -and
        $exeArgs -notmatch "net\.fabricmc\.loader") {
        Fail "这条命令看起来不是 Minecraft 启动命令，检查一下复制的是不是那一整行。"
    }
    if (-not $JavaExe) {
        $jm = Get-JavaMajor $exePath
        Write-Host ("java：" + $exePath + "（Java " + $jm + "）")
        if ($jm -eq 0) {
            Write-Host "[提醒] 这个 java 的版本号读不出来，先按模板用；真崩了再用 -JavaExe 指定一个 25。" -ForegroundColor Yellow
        } elseif ($jm -lt 25) {
            Write-Host ("[提醒] 这个 java 是 " + $jm + "，26.2 要 25。找一下机器上有没有 25 ...") -ForegroundColor Yellow
            $better = Find-JavaForVersion 25
            if ($better) { $exePath = $better } else {
                Write-Host "[提醒] 没找到 25，先按模板用；还崩就 -JavaExe 指定 25。" -ForegroundColor Yellow
            }
        }
    } else {
        Write-Host "java：$exePath"
    }
    if ($tplGameDir) { Write-Host "模板游戏目录：$tplGameDir" }
}

# ---------- 游戏目录 ----------
if ([string]::IsNullOrWhiteSpace($GameRoot)) {
    if ($tplGameDir -and (Test-Path $tplGameDir)) {
        $GameRoot = $tplGameDir
    } else {
        $GameRoot = "$env:APPDATA\.minecraft"
    }
}
if (-not (Test-Path $GameRoot)) { Fail "找不到游戏目录：$GameRoot（用 -GameRoot 指定）" }
Write-Host "游戏目录：$GameRoot"
# 记下游戏目录，控制台.bat 会用这个，不用你每次传 -GameRoot
try {
    [System.IO.File]::WriteAllText((Join-Path $PSScriptRoot "游戏目录.txt"), $GameRoot, (New-Object System.Text.UTF8Encoding($false)))
} catch { }

$versionsDir = Join-Path $GameRoot "versions"

# ---------- 非模板模式：自己拼 classpath ----------
if (-not $useTemplate) {
    if (-not (Test-Path $versionsDir)) { Fail "找不到 versions 目录：$versionsDir" }

    if ([string]::IsNullOrWhiteSpace($Version)) {
        $dirs = Get-ChildItem $versionsDir -Directory | Where-Object {
            Test-Path (Join-Path $_.FullName ($_.Name + ".json"))
        }
        if (-not $dirs) { Fail "versions 目录里没有可用的版本" }
        $fabricCandidates = @()
        foreach ($d in $dirs) {
            $jsonFile = Join-Path $d.FullName ($d.Name + ".json")
            $raw = Get-Content $jsonFile -Raw
            if ($raw -match "(?i)fabric|knot") { $fabricCandidates += $d }
        }
        if ($fabricCandidates) {
            $Version = ($fabricCandidates | Sort-Object LastWriteTime -Descending | Select-Object -First 1).Name
        } else {
            $Version = ($dirs | Sort-Object LastWriteTime -Descending | Select-Object -First 1).Name
        }
    }
    Write-Host "使用版本：$Version"

    $jsonPath = Join-Path $versionsDir "$Version\$Version.json"
    $jarPath = Join-Path $versionsDir "$Version\$Version.jar"
    if (-not (Test-Path $jsonPath)) { Fail "找不到版本配置：$jsonPath" }
    if (-not (Test-Path $jarPath)) { Fail "找不到版本 jar：$jarPath。建议改用 PCL 模板模式（放一个 启动模板.bat 就行）。" }

    $meta = Get-Content $jsonPath -Raw | ConvertFrom-Json
    $mainClass = $meta.mainClass
    if (-not $mainClass) { $mainClass = "net.minecraft.client.main.Main" }
    $assetIndex = "legacy"
    if ($meta.assetIndex) { $assetIndex = $meta.assetIndex.id }
    $assetsDir = Join-Path $GameRoot "assets"

    if (-not $JavaExe) { $JavaExe = Find-Java }
    if (-not $JavaExe) { Fail "找不到 java.exe。用 -JavaExe 指定（26.2 要 Java 25）；或者用 PCL 模板模式。" }
    $jm = Get-JavaMajor $JavaExe
    if ($jm -eq 0) {
        Write-Host "[提醒] java 版本号读不出来，先试；崩了再用 -JavaExe 指定 25。" -ForegroundColor Yellow
    }
    if ($jm -gt 0 -and $jm -lt 25) {
        Write-Host ("[提醒] " + $JavaExe + " 是 Java " + $jm + "，26.2 要 25，自动找一下 ...") -ForegroundColor Yellow
        $better = Find-JavaForVersion 25
        if ($better) { $JavaExe = $better } else { Fail "只找到 Java $jm，装 JDK 25 或用 -JavaExe 指定。" }
    }

    $cp = @($jarPath)
    $libRoot = Join-Path $GameRoot "libraries"
    foreach ($lib in $meta.libraries) {
        if (-not (Test-Allowed $lib)) { continue }
        $rel = $null
        if ($lib.downloads -and $lib.downloads.artifact) { $rel = $lib.downloads.artifact.path }
        if (-not $rel) {
            $lp = $lib.name.Split(":")
            if ($lp.Count -ge 3) {
                $groupPath = $lp[0].Replace(".", "\")
                $artifact = $lp[1]
                $ver = $lp[2]
                $classifier = ""
                if ($lp.Count -ge 4) { $classifier = "-" + $lp[3] }
                $rel = "$groupPath\$artifact\$ver\$artifact-$ver$classifier.jar"
            }
        }
        if (-not $rel) { continue }
        $full = Join-Path $libRoot $rel
        if (Test-Path $full) { $cp += $full }
    }
    $cpString = ($cp -join ";")
    Write-Host "classpath 条目数：$($cp.Count)"
    $natives = Join-Path $versionsDir "$Version\natives"
}

# ---------- 服务器地址（可选，自己填；记在上次填过的文件里） ----------
$srvFile = Join-Path $PSScriptRoot "服务器地址.txt"
$saved = ""
if (Test-Path $srvFile) {
    $rawSrv = Get-Content $srvFile -Raw
    if ($rawSrv) { $saved = $rawSrv.Trim() }
}

if ([string]::IsNullOrWhiteSpace($Server)) {
    if ($saved) {
        $Server = $saved
        Write-Host ("服务器：" + $saved + "（读自 服务器地址.txt；要换服就改这个文件）") -ForegroundColor Gray
    } elseif ($NoSetup) {
        $Server = "skip"
    } else {
        Write-Host ""
        Write-Host "填服务器地址（host 或 host:port）；填 skip = 不自动进服，只开游戏。" -ForegroundColor Yellow
        $inp = Read-Host "服务器地址"
        $inp = $inp.Trim().Trim([char]34)
        $Server = $inp
        if ($Server -and $Server -notmatch "(?i)skip|none|跳过") {
            [System.IO.File]::WriteAllText((Join-Path $PSScriptRoot "服务器地址.txt"), $Server, (New-Object System.Text.UTF8Encoding($false)))
        }
    }
}

if ($Server -match "^(skip|none|-|跳过|不连)$") { $Server = "" }

if ($Server) {
    if ($Server -notmatch "^[^:]+:\d+$") {
        if ($Server -match "^[^:]+$") {
            $Server = "$Server:25565"
        } else {
            Fail "服务器地址格式不对：$Server（要 host:port，比如 127.0.0.1:25565）"
        }
    }
    [System.IO.File]::WriteAllText($srvFile, $Server, (New-Object System.Text.UTF8Encoding($false)))
    Write-Host "连服务器：$Server" -ForegroundColor Cyan
} else {
    Write-Host "不自动进服：机器人只会打开游戏停在主菜单。" -ForegroundColor Yellow
}

# ---------- 模组目录 ----------
if ([string]::IsNullOrWhiteSpace($ModsSource)) {
    $candidates = @((Join-Path $GameRoot "mods"))
    if (-not $useTemplate) { $candidates += (Join-Path $versionsDir "$Version\mods") }
    foreach ($c in $candidates) {
        if (Test-Path $c) { $ModsSource = $c; break }
    }
}
if (-not $ModsSource -or -not (Test-Path $ModsSource)) { Fail "找不到 mods 目录，用 -ModsSource 指定" }
$modsFiles = @(Get-ChildItem $ModsSource -Filter "*.jar" -ErrorAction SilentlyContinue)
Write-Host "模组目录：$ModsSource"

function Disable-Jar([string]$path, [string]$reason) {
    $dir = Join-Path $GameRoot "mods_disabled"
    try {
        if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir | Out-Null }
        $leaf = Split-Path $path -Leaf
        Move-Item -LiteralPath $path (Join-Path $dir ($leaf + ".disabled")) -Force
        Write-Host ("  已移走：" + $leaf + "（" + $reason + "）") -ForegroundColor Yellow
    } catch {
        Write-Host ("  移走失败（游戏可能还开着？）：" + (Split-Path $path -Leaf)) -ForegroundColor Red
    }
}

function Find-NearbyJar([string]$pattern) {
    $dirs = @($PSScriptRoot, (Join-Path $PSScriptRoot ".."), (Join-Path $PSScriptRoot "..\.."))
    foreach ($d in $dirs) {
        if (Test-Path $d) {
            $hit = Get-ChildItem $d -Filter $pattern -File -ErrorAction SilentlyContinue | Select-Object -First 1
            if ($hit) { return $hit.FullName }
        }
    }
    return $null
}

Write-Host "自动整理 mods ..." -ForegroundColor Gray

# Fabric API：非 26.2 的全部挪走，缺 26.2 的从本包找
foreach ($j in @($modsFiles | Where-Object { $_.Name -like "fabric-api-*.jar" })) {
    if ($j.Name -notmatch "\+26\.2") { Disable-Jar $j.FullName "不是 26.2 的 Fabric API，放进去必崩" }
}
$apiOk = @(Get-ChildItem $ModsSource -Filter "fabric-api-*+26.2*.jar" -ErrorAction SilentlyContinue)
if ($apiOk.Count -eq 0) {
    $src = Find-NearbyJar "fabric-api-*+26.2*.jar"
    if ($src) {
        Copy-Item $src (Join-Path $ModsSource (Split-Path $src -Leaf)) -Force
        Write-Host ("  已放入：" + (Split-Path $src -Leaf)) -ForegroundColor Green
    }
}

# 男中音：standalone 精简版挪走，缺 api 版就从本包找
foreach ($j in @($modsFiles | Where-Object { $_.Name -like "baritone-*" -and $_.Name -notlike "*api*" })) {
    Disable-Jar $j.FullName "standalone 精简版没有 baritone.api"
}
$barOk = @(Get-ChildItem $ModsSource -Filter "baritone-api-fabric*.jar" -ErrorAction SilentlyContinue)
if ($barOk.Count -eq 0) {
    $src = Find-NearbyJar "baritone-api-fabric*.jar"
    if ($src) {
        Copy-Item $src (Join-Path $ModsSource (Split-Path $src -Leaf)) -Force
        Write-Host ("  已放入：" + (Split-Path $src -Leaf)) -ForegroundColor Green
    }
}

# 本模组：只留版本号最大的那个 autominer-*.jar，其余挪走；一个都没有就从本包里找版本最大的
function Get-MinerVer([string]$n) {
    if ($n -match 'autominer-(\d+)\.(\d+)\.(\d+)') {
        return ([int]$Matches[1] * 10000 + [int]$Matches[2] * 100 + [int]$Matches[3])
    }
    return -1
}
# 候选：mods 里已有的 + 本包附带的（本包优先按版本号比大小）
$pkgModDirs = @((Join-Path $PSScriptRoot "mods"), $PSScriptRoot, (Split-Path $PSScriptRoot -Parent))
$cand = @(Get-ChildItem $ModsSource -Filter "autominer-*.jar" -ErrorAction SilentlyContinue)
foreach ($d in $pkgModDirs) {
    if (Test-Path $d) {
        $cand += @(Get-ChildItem $d -Filter "autominer-*.jar" -File -ErrorAction SilentlyContinue)
    }
}
$bestJar = $null
$bestVer = -1
foreach ($c in $cand) {
    $v = Get-MinerVer $c.Name
    if ($v -gt $bestVer) { $bestVer = $v; $bestJar = $c }
}
$lockProblem = $false
if ($bestJar) {
    $target = Join-Path $ModsSource $bestJar.Name
    if ($bestJar.FullName -ne $target -and -not (Test-Path $target)) {
        try {
            Copy-Item $bestJar.FullName $target -Force
            Write-Host ("  已更新模组：" + $bestJar.Name) -ForegroundColor Green
        } catch {
            Write-Host ("  放不进新模组（文件被占用）：" + $bestJar.Name) -ForegroundColor Red
            $lockProblem = $true
        }
    }
    foreach ($j in @(Get-ChildItem $ModsSource -Filter "autominer-*.jar" -ErrorAction SilentlyContinue)) {
        if ($j.Name -ne $bestJar.Name) {
            try {
                $dir = Join-Path $GameRoot "mods_disabled"
                if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir | Out-Null }
                Move-Item -LiteralPath $j.FullName (Join-Path $dir ($j.Name + ".disabled")) -Force
                Write-Host ("  已移走旧版：" + $j.Name + "（已有 " + $bestJar.Name + "）") -ForegroundColor Yellow
            } catch {
                Write-Host ("  旧版挪不动：" + $j.Name + "（游戏还开着，关掉它再启动一次）") -ForegroundColor Red
                $lockProblem = $true
            }
        }
    }
}
if ($lockProblem -and -not $PrintOnly) {
    Fail "旧版模组被占用，两个 autominer 会同时加载导致进不去。先把所有客户端关掉（含正在玩的那个），再双击启动。"
}

$modsFiles = @(Get-ChildItem $ModsSource -Filter "*.jar" -ErrorAction SilentlyContinue)
$hasMiner = @($modsFiles | Where-Object { $_.Name -like "autominer*.jar" })
$hasBaritone = @($modsFiles | Where-Object { $_.Name -like "baritone-*.jar" })
$hasApi = @($modsFiles | Where-Object { $_.Name -like "fabric-api-*.jar" })

$fatal = @()
if ($hasMiner.Count -eq 0) {
    $fatal += "没有 autominer*.jar：机器人不会挖矿、也不会自动进服"
} else {
    $minerName = $hasMiner[0].Name
    $mv = Get-MinerVer $minerName
    if ($mv -lt 10800) {
        $fatal += ("autominer 版本太旧：" + $minerName + "（至少要 1.8.0，推荐 1.9.2）")
    } elseif ($mv -lt 10900) {
        Write-Host ("  [提醒] " + $minerName + " 没有图形登录自动化（没有 gui 指令），要 1.9.x 才有") -ForegroundColor Yellow
    }
}
if ($hasBaritone.Count -eq 0) {
    $fatal += "没有 baritone*.jar：模组没有寻路能力"
} else {
    $barName = $hasBaritone[0].Name
    if ($barName -like "*standalone*") {
        $fatal += ("baritone 放的是精简版：" + $barName + "（要 baritone-api-fabric-*.jar）")
    }
}
if ($hasApi.Count -eq 0) {
    $fatal += "没有 fabric-api-*.jar"
} else {
    foreach ($a in $hasApi) {
        if ($a.Name -notmatch "\+26\.2") {
            $fatal += ("Fabric API 版本不对：" + $a.Name + "（26.2 要 fabric-api-0.161.0+26.2.jar，把它删掉）")
        }
    }
}
if ($fatal.Count -gt 0) {
    Write-Host ""
    Write-Host "[这些模组问题会让游戏直接崩，先修好再启动]：" -ForegroundColor Red
    foreach ($f in $fatal) { Write-Host ("  - " + $f) -ForegroundColor Red }
    Write-Host ("要删/要换的文件都在：" + $ModsSource) -ForegroundColor Yellow
    Write-Host "改完再跑一次本脚本；确实想硬启动就加 -IgnoreModCheck。" -ForegroundColor Gray
    if (-not $IgnoreModCheck) { exit 1 }
    Write-Host "（-IgnoreModCheck 生效，继续启动）" -ForegroundColor Yellow
}

$configSrc = Join-Path $GameRoot "config\autominer.json"
$botsRoot = Join-Path $GameRoot "bots"
if (-not (Test-Path $botsRoot)) { New-Item -ItemType Directory -Path $botsRoot | Out-Null }

# ---------- 分工计划 ----------
if ([string]::IsNullOrWhiteSpace($PlanFile)) {
    $PlanFile = Join-Path $PSScriptRoot "矿工配置.txt"
}
$botPlan = @{}
$planAuth = @{}
$planPassword = ""
$planRetry = 10
$planBase = @{}
$planChest = @{}
$planJoin = @()
if (Test-Path $PlanFile) {
    Write-Host "读取分工计划：$PlanFile" -ForegroundColor Cyan
    foreach ($line in ((Read-TextFile $PlanFile) -split "`r?`n")) {
        $t = $line.Trim()
        if ($t -eq "") { continue }
        if ($t.StartsWith("#")) { continue }
        $kv = $t -split "=", 2
        if ($kv.Count -lt 2) {
            Write-Host "[提醒] 这行看不懂，跳过：$t" -ForegroundColor Yellow
            continue
        }
        $botName = $kv[0].Trim()
        $oreText = $kv[1].Trim()
        if ($botName -match "^(进服指令|进服命令|onJoin|onJoinCommands)$") {
            $planJoin = @($oreText -split "\|" | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne "" })
            Write-Host ("进服指令：" + ($planJoin -join " 然后 ")) -ForegroundColor Gray
            continue
        }
        $authPart = ""
        $baseTxt = ""
        $chestTxt = ""
        if ($oreText.Contains("|")) {
            $parts = @($oreText -split "\|" | ForEach-Object { $_.Trim() })
            $oreText = $parts[0]
            $cmds = @()
            foreach ($token in @($parts | Select-Object -Skip 1)) {
                if ($token -match "^(基点|基地|起点|base)=(.+)$") { $baseTxt = $Matches[2] }
                elseif ($token -match "^(箱|箱子|存矿|chest)=(.+)$") { $chestTxt = $Matches[2] }
                elseif ($token -ne "") { $cmds += $token }
            }
            if ($cmds.Count -gt 0) { $authPart = ($cmds -join "|") }
        }
        if ($botName -match "^(重连|重连秒数|重连间隔|retry|retrySeconds)$") {
            $rv = 0
            if ([int]::TryParse($oreText, [ref]$rv)) {
                if ($rv -lt 3) { $rv = 3 }
                if ($rv -gt 600) { $rv = 600 }
                $planRetry = $rv
                Write-Host ("重连间隔：" + $planRetry + " 秒") -ForegroundColor Gray
            } else {
                Write-Host ("[提醒] 重连秒数不是数字，按默认 10 秒：" + $oreText) -ForegroundColor Yellow
            }
            continue
        }
        if ($botName -match "^(密码|登录密码|password|pass)$") {
            $planPassword = $oreText
            $botPlan["__password__"] = @($oreText)
            continue
        }
        $keys = @()
        foreach ($piece in ($oreText -split "[,，;；]")) {
            $piece = $piece.Trim()
            if ($piece -eq "") { continue }
            if ($piece -eq "all" -or $piece -eq "全部") {
                $keys += $OreMap.Keys
                continue
            }
            $k = Resolve-OreKey $piece
            if ($k) { $keys += $k } else { Write-Host "[提醒] 不认识的矿种：$piece（$botName）" -ForegroundColor Yellow }
        }
        $keys = @($keys | Select-Object -Unique)
        if ($keys.Count -eq 0 -and $oreText -ne "") {
            Write-Host "[提醒] $botName 没解析出矿种，这次不覆盖它的配置" -ForegroundColor Yellow
        }
        $bp = Convert-Pos3 $baseTxt
        if ($bp) {
            $planBase[$botName] = $bp
            Write-Host ("      基地/矿区：" + (Get-PosText $bp)) -ForegroundColor Gray
        }
        $cp = Convert-Pos3 $chestTxt
        if ($cp) {
            $planChest[$botName] = $cp
            Write-Host ("      存矿箱：" + (Get-PosText $cp)) -ForegroundColor Gray
        }
        $botPlan[$botName] = $keys
        if ($authPart) { $planAuth[$botName] = $authPart }
        if ($keys.Count -gt 0) {
            Write-Host ("  {0} → {1}" -f $botName, ($keys -join ", ")) -ForegroundColor Gray
        } else {
            Write-Host ("  {0} → 沿用游戏里现有配置" -f $botName) -ForegroundColor Gray
        }
        if ($authPart) { Write-Host ("      登录指令：" + $authPart) -ForegroundColor Gray }
    }
}

# ---------- 逐个启动 ----------
$names = @()
if ($botPlan.ContainsKey("__password__")) { $botPlan.Remove("__password__") | Out-Null }
if ($botPlan.Count -gt 0) {
    $names = @($botPlan.Keys | Sort-Object)
} else {
    $names = @($Bots.Split(",") | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne "" })
}
if ($names.Count -eq 0) { Fail "没有机器人名字（-Bots "Miner1,Miner2"，或在 矿工配置.txt 里写）" }

if ([string]::IsNullOrWhiteSpace($Auth) -and -not [string]::IsNullOrWhiteSpace($planPassword)) {
    $Auth = "/login " + $planPassword
    Write-Host ("按 矿工配置.txt 里的密码自动登录：/login " + $planPassword) -ForegroundColor Cyan
}

# 服务器只认 3-16 位 ASCII 名字（字母/数字/下划线），中文名会被踢或进不去
$planByBot = @{}
$origByNew = @{}
$finalNames = @()
$idx = 0
foreach ($n in $names) {
    $idx++
    $raw = $n.Trim()
    if ($raw -match "^[A-Za-z0-9_]{3,16}$") {
        $finalNames += $raw
        $origByNew[$raw] = $raw
        if ($botPlan.ContainsKey($n)) { $planByBot[$raw] = $botPlan[$n] }
        elseif ($botPlan.ContainsKey($raw)) { $planByBot[$raw] = $botPlan[$raw] }
        continue
    }
    $cand = $idx
    $safe = "Miner" + $cand
    while (($finalNames -contains $safe) -or (Test-Path (Join-Path $botsRoot $safe))) {
        $cand++
        $safe = "Miner" + $cand
    }
    $finalNames += $safe
    $origByNew[$safe] = $raw
    if ($botPlan.ContainsKey($n)) { $planByBot[$safe] = $botPlan[$n] }
    Write-Host ("[改名] " + $raw + " → " + $safe + "（服务器只认 3-16 位英文字母/数字/下划线，中文名进不去）") -ForegroundColor Yellow
}
$names = $finalNames
$renamedPlan = $false
foreach ($k in $origByNew.Keys) { if ($k -ne $origByNew[$k]) { $renamedPlan = $true; break } }

# ---------- 启动时就地改矿工分工（不用去手改 矿工配置.txt） ----------
function Get-PlanText($keys) {
    if (-not $keys -or $keys.Count -eq 0) { return "" }
    return (@($keys) -join ", ")
}

function TrimD([string]$t, [int]$w) {
    if ($null -eq $t) { return "" }
    $n = 0
    $out = ""
    foreach ($ch in $t.ToCharArray()) {
        $c = [int]$ch
        $cw = if (($c -ge 0x1100 -and $c -le 0x115F) -or ($c -ge 0x2E80 -and $c -le 0xA4CF) -or
                 ($c -ge 0xAC00 -and $c -le 0xD7A3) -or ($c -ge 0xF900 -and $c -le 0xFAFF) -or
                 ($c -ge 0xFE10 -and $c -le 0xFE19) -or ($c -ge 0xFF00 -and $c -le 0xFF60) -or
                 ($c -ge 0xFFE0 -and $c -le 0xFFE6)) { 2 } else { 1 }
        if ($n + $cw -gt $w - 1) { return $out + "…" }
        $out += $ch
        $n += $cw
    }
    return $out
}

function PadD([string]$t, [int]$w) {
    if ($null -eq $t) { $t = "" }
    $n = 0
    foreach ($ch in $t.ToCharArray()) {
        $c = [int]$ch
        if (($c -ge 0x1100 -and $c -le 0x115F) -or ($c -ge 0x2E80 -and $c -le 0xA4CF) -or
            ($c -ge 0xAC00 -and $c -le 0xD7A3) -or ($c -ge 0xF900 -and $c -le 0xFAFF) -or
            ($c -ge 0xFE10 -and $c -le 0xFE19) -or ($c -ge 0xFF00 -and $c -le 0xFF60) -or
            ($c -ge 0xFFE0 -and $c -le 0xFFE6)) { $n += 2 } else { $n += 1 }
    }
    if ($n -ge $w) { return $t }
    return $t + (" " * ($w - $n))
}

function Clear-Screen {
    try { [Console]::Clear() } catch { }
    try { $host.UI.RawUI.CursorPosition = New-Object System.Management.Automation.Host.Coordinates 0, 0 } catch { }
}

function Get-RunningBots {
    $set = @{}
    try {
        $procs = Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue
        foreach ($pr in $procs) {
            $cl = $pr.CommandLine
            if (-not $cl) { continue }
            $mm = [regex]::Match($cl, 'bots\\([A-Za-z0-9_]{3,16})')
            if ($mm.Success) { $set[$mm.Groups[1].Value] = $true }
        }
    } catch { }
    return ,$set
}

function Get-PlanDisp($keys) {
    if (-not $keys -or @($keys).Count -eq 0) { return "（沿用游戏里现有配置）" }
    return ((@($keys) | ForEach-Object { if ($OreCn.ContainsKey($_)) { $OreCn[$_] + "(" + $_ + ")" } else { $_ } }) -join "、")
}

function Show-BotPlan([hashtable]$running) {
    Write-Host ""
    Write-Host ("  " + (PadD "序号" 5) + (PadD "名字" 13) + (PadD "挖什么" 26) + (PadD "状态" 9) + "基地 / 存矿箱") -ForegroundColor DarkGray
    Write-Host ("  " + ("─" * 77)) -ForegroundColor DarkCyan
    $i = 0
    foreach ($n in $names) {
        $i++
        $isRun = ($running -and $running.ContainsKey($n))
        $oreDisp = Get-PlanDisp $planByBot[$n]
        if ($oreDisp -like "（*") { $oreDisp = "沿用现有配置" }
        $oreDisp = TrimD $oreDisp 24
        Write-Host ("  " + (PadD $i 5)) -NoNewline -ForegroundColor DarkGray
        Write-Host (PadD $n 13) -NoNewline -ForegroundColor White
        Write-Host (PadD $oreDisp 26) -NoNewline -ForegroundColor Gray
        if ($isRun) {
            Write-Host (PadD "已启动" 9) -NoNewline -ForegroundColor Green
        } else {
            Write-Host (PadD "没启动" 9) -NoNewline -ForegroundColor DarkGray
        }
        $ext = "-"
        if ($planBase.ContainsKey($n) -or $planChest.ContainsKey($n)) {
            $b = if ($planBase.ContainsKey($n)) { Get-PosText $planBase[$n] } else { "-" }
            $c = if ($planChest.ContainsKey($n)) { Get-PosText $planChest[$n] } else { "-" }
            $ext = $b + "  /  " + $c
        }
        Write-Host $ext -ForegroundColor DarkGray
    }
    Write-Host ("  " + ("─" * 77)) -ForegroundColor DarkCyan
}

function Show-Header([hashtable]$running) {
    Write-Host ""
    Write-Host ("  " + ("═" * 77)) -ForegroundColor DarkCyan
    $srvText = if ($Server) { $Server } else { "只开游戏，不进服务器" }
    Write-Host ("   自动采矿助手    " + $srvText + "    共 " + $names.Count + " 个号    掉线 " + $planRetry + " 秒重连") -ForegroundColor Cyan
    Write-Host ("  " + ("═" * 77)) -ForegroundColor DarkCyan
    if ($script:lastNote) {
        Write-Host ("   ✔ " + $script:lastNote) -ForegroundColor Green
    }
    Show-BotPlan $running
    Write-Host "   输序号 = 改这个号（名字、挖什么）     a = 全部重设     回车 = 现在开始" -ForegroundColor Gray
    Write-Host "   n=加号   d=删号   b=设基地和存矿箱   i=进服指令   r=改重连秒数   ?=详细说明" -ForegroundColor Gray
}

function Ask-BotName([string]$oldName) {
    Write-Host ("  现在的名字：" + $oldName + "（服务器只收 3-16 位英文/数字/下划线）") -ForegroundColor DarkGray
    try { $inp = Read-Host "  改成什么名字（回车=不改）" } catch { return $oldName }
    if ($null -eq $inp) { return $oldName }
    $inp = $inp.Trim()
    if ($inp -eq "") { return $oldName }
    if ($inp -eq $oldName) { return $oldName }
    if ($inp -notmatch "^[A-Za-z0-9_]{3,16}$") {
        Write-Host ("    这个名字不行（要 3-16 位英文字母/数字/下划线，中文进不去）：" + $inp) -ForegroundColor Yellow
        return $oldName
    }
    if ($names -contains $inp) {
        Write-Host ("    已经有 " + $inp + " 这个号了，换个名字") -ForegroundColor Yellow
        return $oldName
    }
    return $inp
}

function Rename-BotDir([string]$oldName, [string]$newName) {
    if ($oldName -eq $newName) { return $true }
    $oldDir = Join-Path $botsRoot $oldName
    $newDir = Join-Path $botsRoot $newName
    if (-not (Test-Path $oldDir)) { return $true }
    if (Test-Path $newDir) {
        Write-Host ("    已经有 " + $newName + " 这个目录了，名字没改（避免覆盖）") -ForegroundColor Yellow
        return $false
    }
    try {
        Move-Item -LiteralPath $oldDir -Destination $newDir -Force
        Write-Host ("    目录也一起改名：" + $oldName + " → " + $newName) -ForegroundColor Green
        return $true
    } catch {
        Write-Host ("    目录改名失败：" + $_.Exception.Message) -ForegroundColor Red
        return $false
    }
}

function Save-PlanFile([hashtable]$plan, [string]$password, [string]$planPath, [int]$retry = 10) {
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.AppendLine("# 自动采矿助手 · 矿工分工")
    [void]$sb.AppendLine("# 格式：名字 = 矿种1, 矿种2   （可写中文：钻石、绿宝石、煤、铁、红石、青金石…）")
    [void]$sb.AppendLine("# 只写名字不写矿种 = 这个号沿用游戏里已有的配置")
    [void]$sb.AppendLine("# 这个文件每次在启动界面改完都会自动重写，平时不用手改")
    [void]$sb.AppendLine("")
    if ($password) { [void]$sb.AppendLine("密码 = " + $password) }
    [void]$sb.AppendLine("重连秒数 = " + $retry)
    if ($planJoin.Count -gt 0) { [void]$sb.AppendLine("进服指令 = " + ($planJoin -join " | ")) }
    [void]$sb.AppendLine("")
    foreach ($n in $names) {
        $keys = $plan[$n]
        if (-not $keys -or @($keys).Count -eq 0) {
            $line = "$n ="
        } else {
            $line = "$n = " + (Get-PlanText $keys)
        }
        if ($planChest.ContainsKey($n)) { $line += " | 箱=" + (Get-PosText $planChest[$n]) }
        if ($planBase.ContainsKey($n)) { $line += " | 基点=" + (Get-PosText $planBase[$n]) }
        if ($planAuth.ContainsKey($n)) { $line += " | " + $planAuth[$n] }
        [void]$sb.AppendLine($line)
    }
    [System.IO.File]::WriteAllText($planPath, $sb.ToString(), (New-Object System.Text.UTF8Encoding($false)))
}

function Ask-BotOres([string]$botName) {
    $cur = Get-PlanText $planByBot[$botName]
    $hint = if ($cur) { "现在的：" + $cur } else { "例：钻石, 绿宝石 / 煤 铁 / all" }
    Write-Host ("  " + $hint) -ForegroundColor DarkGray
    try { $inp = Read-Host ("  " + $botName + " 挖什么（回车=不改）") } catch { return $false }
    if ($null -eq $inp -or $inp.Trim() -eq "") { return $false }
    $keys = @()
    foreach ($piece in ($inp -split "[,，;；\s]+")) {
        $piece = $piece.Trim()
        if ($piece -eq "") { continue }
        if ($piece -eq "all" -or $piece -eq "全部") { $keys += $OreMap.Keys; continue }
        $k = Resolve-OreKey $piece
        if ($k) { $keys += $k } else { Write-Host ("    不认识的矿种：" + $piece) -ForegroundColor Yellow }
    }
    $keys = @($keys | Select-Object -Unique)
    if ($keys.Count -eq 0) { Write-Host "    没识别出矿种，保持原样" -ForegroundColor Yellow; return $false }
    $planByBot[$botName] = $keys
    Write-Host ("    已改成：" + (Get-PlanText $keys)) -ForegroundColor Green
    return $true
}

$planChanged = $false
$menuDrawn = $false
$script:lastNote = ""
if (-not $NoSetup -and -not [Environment]::GetEnvironmentVariable("AM_NO_SETUP")) {
    while ($true) {
        $running = Get-RunningBots
        if ($menuDrawn) { Clear-Screen }
        Show-Header $running
        try { $sel = Read-Host "  请输入" } catch { break }
        if ($null -eq $sel) { break }
        $sel = $sel.Trim()
        if ($sel -eq "") { break }
        if ($sel -ieq "s" -or $sel -ieq "q") { break }
        if ($sel -eq "?") {
            Write-Host ""
            Write-Host "  说明" -ForegroundColor Cyan
            Write-Host "   数字     改这个号的名字和挖什么（改名字会连游戏目录一起改）" -ForegroundColor Gray
            Write-Host "   n        新增一个号（会建 bots\新名字 目录）" -ForegroundColor Gray
            Write-Host "   d        删除一个号（目录挪进 bots\_旧目录，不真删）" -ForegroundColor Gray
            Write-Host "   b        设基地/矿点坐标 + 存矿箱坐标（进服先去这里，再开挖）" -ForegroundColor Gray
            Write-Host "   i        设进服后自动发的指令，比如 /home base、/kit（多条用 |）" -ForegroundColor Gray
            Write-Host "   a        所有号挨个重设挖什么" -ForegroundColor Gray
            Write-Host "   r        改掉线重连间隔（3-600 秒，默认 10）" -ForegroundColor Gray
            Write-Host "   名字规则  3-16 位英文/数字/下划线（服务器不收中文名）" -ForegroundColor DarkGray
            Write-Host "   填坐标    格式 x y z，空格或逗号都行，例如 100 64 -200" -ForegroundColor DarkGray
            Write-Host ""
            try { Read-Host "  按回车回到菜单" | Out-Null } catch { }
            $menuDrawn = $true
            continue
        }
        if ($sel -ieq "r") {
            Write-Host ("  现在掉线后每 " + $planRetry + " 秒重连一次（3-600 秒）") -ForegroundColor DarkGray
            try { $inp = Read-Host "  改成多少秒（回车=不改）" } catch { $inp = "" }
            if ($null -ne $inp -and $inp.Trim() -ne "") {
                $rv = 0
                if ([int]::TryParse($inp.Trim(), [ref]$rv)) {
                    if ($rv -lt 3) { $rv = 3 }
                    if ($rv -gt 600) { $rv = 600 }
                    $planRetry = $rv
                    $planChanged = $true
                    Write-Host ("    已改成每 " + $planRetry + " 秒重连") -ForegroundColor Green
                    $script:lastNote = "重连间隔改成 " + $planRetry + " 秒"
                } else {
                    Write-Host "    只认数字，没改" -ForegroundColor Yellow
                }
            }
            $menuDrawn = $true
            continue
        }
        if ($sel -ieq "a") {
            $anyOre = $false
            foreach ($n in $names) {
                if (Ask-BotOres $n) { $planChanged = $true; $anyOre = $true }
            }
            if ($anyOre) { $script:lastNote = "已更新 " + $names.Count + " 个号的挖矿清单" }
            $menuDrawn = $true
            continue
        }
        if ($sel -ieq "i") {
            $cur = if ($planJoin.Count -gt 0) { $planJoin -join " | " } else { "（空）" }
            Write-Host ("  现在进服后会自动发：" + $cur) -ForegroundColor DarkGray
            Write-Host "  例：/home base   或   /tpa 主号名   （多条用 | 隔开）" -ForegroundColor DarkGray
            try { $inp = Read-Host "  改成什么（回车=不改，输入 0 = 清空）" } catch { $inp = "" }
            if ($null -ne $inp -and $inp.Trim() -ne "") {
                if ($inp.Trim() -eq "0") {
                    $planJoin = @()
                    Write-Host "    已清空进服指令" -ForegroundColor Green
                } else {
                    $planJoin = @($inp -split "\|" | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne "" })
                    Write-Host ("    已设成：" + ($planJoin -join "  然后 ")) -ForegroundColor Green
                    $script:lastNote = "进服指令：" + ($planJoin -join " → ")
                }
                $planChanged = $true
            }
            $menuDrawn = $true
            continue
        }
        if ($sel -ieq "b") {
            $target = $null
            while ($true) {
                try { $inp = Read-Host "  给哪个号设基地点（编号或名字，回车=取消）" } catch { break }
                if ($null -eq $inp -or $inp.Trim() -eq "") { break }
                $t = $inp.Trim()
                if ($t -match "^\d+$") {
                    $k = [int]$t - 1
                    if ($k -ge 0 -and $k -lt $names.Count) { $target = $names[$k] }
                    else { Write-Host "    没有这个编号" -ForegroundColor Yellow; continue }
                } elseif ($names -contains $t) { $target = $t }
                else { Write-Host ("    没有叫 " + $t + " 的号") -ForegroundColor Yellow; continue }
                break
            }
            if ($target) {
                try { $p1 = Read-Host "  基地/矿区坐标，格式 x y z（回车=取消）" } catch { $p1 = "" }
                $bp = Convert-Pos3 $p1
                if ($bp) {
                    $planBase[$target] = $bp
                    $planChanged = $true
                    Write-Host ("    基地/矿区：" + (Get-PosText $bp)) -ForegroundColor Green
                    try { $p2 = Read-Host "  存矿箱坐标 x y z（回车=跟基地同一个）" } catch { $p2 = "" }
                    $cp = Convert-Pos3 $p2
                    if (-not $cp) { $cp = $bp }
                    $planChest[$target] = $cp
                    Write-Host ("    存矿箱：" + (Get-PosText $cp)) -ForegroundColor Green
                    Write-Host "    以后这个号进服 → 走到基地 → 开挖 → 挖满回这个箱子" -ForegroundColor DarkGray
                    $script:lastNote = $target + " 基地 " + (Get-PosText $bp) + "，存矿箱 " + (Get-PosText $cp)
                } else {
                    Write-Host "    坐标看不懂（要 3 个数字，空格或逗号隔开）" -ForegroundColor Yellow
                }
            } else {
                Write-Host "    取消" -ForegroundColor DarkGray
            }
            $menuDrawn = $true
            continue
        }
        if ($sel -ieq "n") {
            $newBot = $null
            while ($true) {
                try { $inp = Read-Host "  新号名字（3-16 位英文/数字/下划线，回车=取消）" } catch { break }
                if ($null -eq $inp -or $inp.Trim() -eq "") { break }
                $t = $inp.Trim()
                if ($t -notmatch "^[A-Za-z0-9_]{3,16}$") {
                    Write-Host "    名字要 3-16 位英文/数字/下划线（服务器不收中文）" -ForegroundColor Yellow
                    continue
                }
                if ($names -contains $t) { Write-Host ("    已经有 " + $t + " 了，换个名字") -ForegroundColor Yellow; continue }
                $newBot = $t
                break
            }
            if ($newBot) {
                $dir = Join-Path $botsRoot $newBot
                if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
                $names = @($names + $newBot)
                $planByBot[$newBot] = @()
                $origByNew[$newBot] = $newBot
                $planChanged = $true
                Write-Host ("    已新增 " + $newBot) -ForegroundColor Green
                $script:lastNote = "已新增 " + $newBot
                if (-not (Ask-BotOres $newBot)) {
                    $donorName = $null
                    $donorKeys = $null
                    foreach ($dn in $names) {
                        if ($dn -eq $newBot) { continue }
                        if ($planByBot.ContainsKey($dn) -and @($planByBot[$dn]).Count -gt 0) {
                            $donorName = $dn
                            $donorKeys = @($planByBot[$dn])
                            break
                        }
                    }
                    if ($donorKeys) {
                        $planByBot[$newBot] = $donorKeys
                        Write-Host ("    没写矿种 → 先跟 " + $donorName + " 一样挖：" + (Get-PlanText $donorKeys) + "（按数字可改）") -ForegroundColor DarkGray
                    } else {
                        Write-Host "    没写矿种 → 先让它挖全部常见矿，进界面按数字可改" -ForegroundColor DarkGray
                    }
                }
            } else {
                Write-Host "    取消新增" -ForegroundColor DarkGray
            }
            $menuDrawn = $true
            continue
        }
        if ($sel -ieq "d") {
            $target = $null
            while ($true) {
                try { $inp = Read-Host "  删哪个（输编号或名字，回车=取消）" } catch { break }
                if ($null -eq $inp -or $inp.Trim() -eq "") { break }
                $t = $inp.Trim()
                if ($t -match "^\d+$") {
                    $k = [int]$t - 1
                    if ($k -ge 0 -and $k -lt $names.Count) { $target = $names[$k] }
                    else { Write-Host "    没有这个编号" -ForegroundColor Yellow; continue }
                } elseif ($names -contains $t) {
                    $target = $t
                } else {
                    Write-Host ("    没有叫 " + $t + " 的号") -ForegroundColor Yellow
                    continue
                }
                break
            }
            if ($target) {
                if ($names.Count -le 1) {
                    Write-Host "    只剩一个号了，删了就没得挖，留着吧" -ForegroundColor Yellow
                } elseif ($running.ContainsKey($target)) {
                    Write-Host ("    " + $target + " 正在跑，先在它的窗口里关掉游戏，再来删（不然文件锁着删不掉）") -ForegroundColor Yellow
                } else {
                    try { $ok = Read-Host ("  确认删除 " + $target + " ？（存档会挪到 bots\_旧目录，输 y 确认）") } catch { $ok = "" }
                    if ($ok -match "^(y|Y|是)$") {
                        $dir = Join-Path $botsRoot $target
                        if (Test-Path $dir) {
                            $trashRoot = Join-Path $botsRoot "_旧目录"
                            New-Item -ItemType Directory -Path $trashRoot -Force | Out-Null
                            $dest = Join-Path $trashRoot ($target + "_删除_" + (Get-Date -Format "MMdd-HHmm"))
                            try {
                                Move-Item -LiteralPath $dir -Destination $dest -Force
                                Write-Host ("    已删除 " + $target + "，存档留在 " + $dest) -ForegroundColor Green
                            } catch {
                                Write-Host ("    删不掉：" + $_.Exception.Message) -ForegroundColor Red
                                $target = $null
                            }
                        }
                        if ($target) {
                            $names = @($names | Where-Object { $_ -ne $target })
                            $planByBot.Remove($target) | Out-Null
                            $planAuth.Remove($target) | Out-Null
                            $origByNew.Remove($target) | Out-Null
                            $planBase.Remove($target) | Out-Null
                            $planChest.Remove($target) | Out-Null
                            $script:lastNote = "已删除 " + $target + "（存档在 bots\_旧目录）"
                            $planChanged = $true
                        }
                    } else {
                        Write-Host "    已取消" -ForegroundColor DarkGray
                    }
                }
            } else {
                Write-Host "    取消删除" -ForegroundColor DarkGray
            }
            $menuDrawn = $true
            continue
        }
        if ($sel -match "^\d+$") {
            $idxSel = [int]$sel - 1
            if ($idxSel -ge 0 -and $idxSel -lt $names.Count) {
                $old = $names[$idxSel]
                $new = Ask-BotName $old
                if ($new -ne $old) {
                    if (Rename-BotDir $old $new) {
                        $names[$idxSel] = $new
                        if ($planByBot.ContainsKey($old)) { $planByBot[$new] = $planByBot[$old]; $planByBot.Remove($old) }
                        if ($planAuth.ContainsKey($old)) { $planAuth[$new] = $planAuth[$old]; $planAuth.Remove($old) }
                        if ($origByNew.ContainsKey($old)) { $origByNew[$new] = $origByNew[$old]; $origByNew.Remove($old) }
                        if ($planBase.ContainsKey($old)) { $planBase[$new] = $planBase[$old]; $planBase.Remove($old) }
                        if ($planChest.ContainsKey($old)) { $planChest[$new] = $planChest[$old]; $planChest.Remove($old) }
                        $script:lastNote = "已改成 " + $new + "（目录、基地、箱子都跟着改了）"
                        $planChanged = $true
                    }
                }
                if (Ask-BotOres $new) {
                    $planChanged = $true
                    $script:lastNote = "已更新 " + $new + " 的挖矿清单：" + (Get-PlanText $planByBot[$new])
                }
            } else {
                Write-Host "    没有这个编号" -ForegroundColor Yellow
            }
            $menuDrawn = $true
            continue
        }
        Write-Host "    看不懂：数字=改号  n=新增  d=删除  b=设点  i=进服指令  a=全重设  r=重连  ?=说明  回车=启动" -ForegroundColor Yellow
    }
    if ($planChanged) {
        Save-PlanFile $planByBot $planPassword $PlanFile $planRetry
        Write-Host ("已把分工写回 " + $PlanFile) -ForegroundColor Cyan
    }
}

if ($renamedPlan) {
    try {
        Save-PlanFile $planByBot $planPassword $PlanFile $planRetry
        Write-Host ("[名字修正] 计划文件里的中文名已换成服务器能用的名字，写回 " + $PlanFile) -ForegroundColor Yellow
    } catch { }
}

# 顺手清理目录：中文名目录如果能对上号就改名，对不上就挪进 _旧目录，别让控制台里冒出 6 个机器人
$oldRoot = Join-Path $botsRoot "_旧目录"
foreach ($bd in @(Get-ChildItem $botsRoot -Directory -ErrorAction SilentlyContinue)) {
    if ($bd.Name.StartsWith("_")) { continue }
    if ($bd.Name -match "^[A-Za-z0-9_]{3,16}$") { continue }
    $matchNew = $null
    foreach ($k in $origByNew.Keys) {
        if ($origByNew[$k] -eq $bd.Name) { $matchNew = $k; break }
    }
    if ($matchNew -and -not (Test-Path (Join-Path $botsRoot $matchNew))) {
        try {
            Move-Item -LiteralPath $bd.FullName -Destination (Join-Path $botsRoot $matchNew) -Force
            Write-Host ("  旧目录改名：" + $bd.Name + " → " + $matchNew) -ForegroundColor Gray
        } catch {
            Write-Host ("  旧目录改名失败：" + $bd.Name) -ForegroundColor Yellow
        }
    } else {
        try {
            if (-not (Test-Path $oldRoot)) { New-Item -ItemType Directory -Path $oldRoot | Out-Null }
            Move-Item -LiteralPath $bd.FullName -Destination (Join-Path $oldRoot $bd.Name) -Force
            Write-Host ("  旧目录已挪走（名字不能用）：" + $bd.Name + " → bots\_旧目录\" + $bd.Name) -ForegroundColor Yellow
        } catch { }
    }
}

foreach ($name in $names) {
    $botDir = Join-Path $botsRoot $name
    if (-not (Test-Path $botDir)) { New-Item -ItemType Directory -Path $botDir | Out-Null }
    Write-Host ("  [" + $name + "] 进服用户名 = " + $name + "，目录 " + $botDir) -ForegroundColor Gray

    # mods 目录：目录联接，不需要管理员、不会有 UAC
    $modsLink = Join-Path $botDir "mods"
    if (-not (Test-Path $modsLink)) {
        cmd /c mklink /J "$modsLink" "$ModsSource" | Out-Null
        if (-not (Test-Path $modsLink)) {
            Write-Host "  目录联接失败，改为复制 mods（慢一点）" -ForegroundColor Yellow
            Copy-Item $ModsSource $modsLink -Recurse -Force
        }
    }

    # 配置
    $cfgDir = Join-Path $botDir "config"
    if (-not (Test-Path $cfgDir)) { New-Item -ItemType Directory -Path $cfgDir | Out-Null }
    $botCfgPath = Join-Path $cfgDir "autominer.json"
    $baseText = ""
    if (Test-Path $botCfgPath) {
        $rawCfg = Read-TextFile $botCfgPath
        if ($rawCfg) { $baseText = $rawCfg }
    } elseif (Test-Path $configSrc) {
        $rawCfg = Read-TextFile $configSrc
        if ($rawCfg) { $baseText = $rawCfg }
    }

    $hasOwnCfg = Test-Path $botCfgPath
    $hasKeys = ($planByBot.ContainsKey($name) -and @($planByBot[$name]).Count -gt 0)
    if (-not $hasOwnCfg -and -not (Test-Path $configSrc) -and -not $hasKeys) {
        $planByBot[$name] = @($OreMap.Keys | Sort-Object)
        $hasKeys = $true
        Write-Host ("  这个号没写矿种，也没找到可沿用的配置：先让它挖全部常见矿（界面里按数字可改）") -ForegroundColor Yellow
    }
    if ($hasKeys) {
        $keys = $planByBot[$name]
        $blocks = @()
        $drops = @()
        foreach ($k in $keys) {
            $blocks += $OreMap[$k].blocks
            $drops += $OreMap[$k].drops
        }
        $blocks = @($blocks | Select-Object -Unique)
        $drops = @($drops | Select-Object -Unique)
        $trigger = $drops[0]
        $wantAuto = $true
        if ($JustJoin) { $wantAuto = $false }
        $authForBot = $Auth
        if ($planAuth.ContainsKey($name)) { $authForBot = $planAuth[$name] }
        $json = New-BotConfig $baseText $blocks $drops $trigger $TriggerCount $authForBot $wantAuto $Server $Gui $planRetry
        # 开荒：基地/矿区坐标、存矿箱、进服指令
        try {
            $obj = $json | ConvertFrom-Json
            $changedExtra = $false
            if ($planBase.ContainsKey($name)) {
                $bp = @($planBase[$name])
                $obj | Add-Member -Force -NotePropertyName startPoint -NotePropertyValue $bp
                $changedExtra = $true
            }
            if ($planChest.ContainsKey($name)) {
                $cp = @($planChest[$name])
                $entry = [ordered]@{ name = "基地"; x = [int]$cp[0]; y = [int]$cp[1]; z = [int]$cp[2] }
                $keep = @()
                if ($obj.PSObject.Properties.Name -contains "chests") {
                    foreach ($c in @($obj.chests)) {
                        if ($c.name -ne "基地") { $keep += $c }
                    }
                }
                $obj | Add-Member -Force -NotePropertyName chests -NotePropertyValue (@($entry) + $keep)
                $changedExtra = $true
            }
            if ($planJoin.Count -gt 0) {
                $obj | Add-Member -Force -NotePropertyName onJoinCommands -NotePropertyValue @($planJoin)
                $changedExtra = $true
            }
            if ($changedExtra) { $json = ($obj | ConvertTo-Json -Depth 8) }
        } catch {
            Write-Host ("  开荒参数写入失败：" + $_.Exception.Message) -ForegroundColor Yellow
        }
        $existing = ""
        if (Test-Path $botCfgPath) { $existing = [System.IO.File]::ReadAllText($botCfgPath) }
        if ($existing -ne $json) {
            [System.IO.File]::WriteAllText($botCfgPath, $json, (New-Object System.Text.UTF8Encoding($false)))
            if ($existing) {
                Write-Host ("  已按 矿工配置.txt 更新 {0}：挖 {1}" -f $name, ($keys -join ", ")) -ForegroundColor Green
            } else {
                Write-Host ("  已生成 {0} 的配置：挖 {1}" -f $name, ($keys -join ", ")) -ForegroundColor Gray
            }
        } else {
            Write-Host ("  配置已是最新：{0}（挖 {1}）" -f $name, ($keys -join ", ")) -ForegroundColor Gray
        }
    } elseif (($planByBot.ContainsKey($name)) -and (Test-Path $botCfgPath)) {
        Write-Host ("  保留已有配置：{0}（这个号没写矿种）" -f $name) -ForegroundColor Gray
    } elseif ((-not (Test-Path $botCfgPath)) -and (Test-Path $configSrc)) {
        Copy-Item $configSrc $botCfgPath -Force
        try {
            $oj = [System.IO.File]::ReadAllText($botCfgPath) | ConvertFrom-Json
            if ($oj.PSObject.Properties.Name -contains "enabled") { $oj.enabled = $true } else { $oj | Add-Member -NotePropertyName enabled -NotePropertyValue $true }
            if (-not $JustJoin -and @($oj.mineBlocks).Count -gt 0) {
                if ($oj.PSObject.Properties.Name -contains "autoStartOnJoin") { $oj.autoStartOnJoin = $true } else { $oj | Add-Member -NotePropertyName autoStartOnJoin -NotePropertyValue $true }
            }
            if (@($oj.mineBlocks).Count -eq 0) {
                $allB = @(); foreach ($k in ($OreMap.Keys | Sort-Object)) { $allB += $OreMap[$k].blocks }
                $oj | Add-Member -Force -NotePropertyName mineBlocks -NotePropertyValue @($allB | Select-Object -Unique)
                $allD = @(); foreach ($k in ($OreMap.Keys | Sort-Object)) { $allD += $OreMap[$k].drops }
                $oj | Add-Member -Force -NotePropertyName depositItems -NotePropertyValue @($allD | Select-Object -Unique)
            }
            if (-not $JustJoin) {
                if ($oj.PSObject.Properties.Name -contains "autoStartOnJoin") { $oj.autoStartOnJoin = $true } else { $oj | Add-Member -NotePropertyName autoStartOnJoin -NotePropertyValue $true }
            }
            [System.IO.File]::WriteAllText($botCfgPath, ($oj | ConvertTo-Json -Depth 8), (New-Object System.Text.UTF8Encoding($false)))
        } catch { }
        Write-Host "  没有分工条目，沿用主号配置：$name（已确保开启挖矿）" -ForegroundColor Gray
    } elseif (Test-Path $botCfgPath) {
        Write-Host "  保留已有配置：$name（要重写就加 -RefreshConfig）" -ForegroundColor Gray
    }

    # 可见性：把“不会自动开挖”的原因直接打在屏幕上，避免“进服了却不动”的错觉
    if (Test-Path $botCfgPath) {
        try {
            $chk = [System.IO.File]::ReadAllText($botCfgPath) | ConvertFrom-Json
            $isEnabled = $true
            if ($chk.PSObject.Properties.Name -contains "enabled") { $isEnabled = [bool]$chk.enabled }
            $isAuto = $true
            if ($chk.PSObject.Properties.Name -contains "autoStartOnJoin") { $isAuto = [bool]$chk.autoStartOnJoin }
            if (-not $isEnabled) {
                Write-Host ("  [注意] {0} 的配置 enabled=false —— 进服后不会开挖（界面里重设矿种即可打开）" -f $name) -ForegroundColor Yellow
            } elseif (-not $isAuto -and -not $JustJoin) {
                Write-Host ("  [注意] {0} 的 autoStartOnJoin=false —— 进服后只站着不开挖（界面里重设矿种即可打开）" -f $name) -ForegroundColor Yellow
            }
        } catch { }
    }

    # -JustJoin：已有配置也改成“进服不自动开挖”
    if ($JustJoin -and (Test-Path $botCfgPath)) {
        try {
            $oj = [System.IO.File]::ReadAllText($botCfgPath) | ConvertFrom-Json
            if ($oj.PSObject.Properties.Name -contains "autoStartOnJoin") { $oj.autoStartOnJoin = $false }
            else { $oj | Add-Member -NotePropertyName autoStartOnJoin -NotePropertyValue $false }
            [System.IO.File]::WriteAllText($botCfgPath, ($oj | ConvertTo-Json -Depth 8), (New-Object System.Text.UTF8Encoding($false)))
            Write-Host "  已设为只进服不挖矿：$name（想开挖就在控制台输 $name start）" -ForegroundColor Yellow
        } catch { }
    }

    # 自动进服地址（和 --quickPlayMultiplayer 双保险：哪个先到算哪个）
    if (Test-Path $botCfgPath) {
        try {
            $oj = [System.IO.File]::ReadAllText($botCfgPath) | ConvertFrom-Json
            $cur = ""
            if ($oj.PSObject.Properties.Name -contains "autoJoinServer") { $cur = [string]$oj.autoJoinServer }
            if ([string]::IsNullOrWhiteSpace($Server)) {
                # 这次没填地址（skip）：保留机器人原来的设置，别把上次填好的抹成空
                if (-not [string]::IsNullOrWhiteSpace($cur)) {
                    Write-Host ("  " + $name + " 沿用上次的进服地址：" + $cur) -ForegroundColor Gray
                } else {
                    Write-Host ("  " + $name + " 没有进服地址：只能开到主菜单（想自动进服用 -Server host:port）") -ForegroundColor Yellow
                }
            } elseif ($cur -ne $Server) {
                if ($oj.PSObject.Properties.Name -contains "autoJoinServer") { $oj.autoJoinServer = $Server }
                else { $oj | Add-Member -NotePropertyName autoJoinServer -NotePropertyValue $Server }
                [System.IO.File]::WriteAllText($botCfgPath, ($oj | ConvertTo-Json -Depth 8), (New-Object System.Text.UTF8Encoding($false)))
            }
        } catch { }
    }

    # 轻量窗口设置，省显存
    $options = Join-Path $botDir "options.txt"
    if (-not (Test-Path $options)) {
        @(
            "lang:zh_cn",
            "guiScale:2",
            "renderDistance:6",
            "simulationDistance:6",
            "maxFps:30",
            "enableVsync:false",
            "pauseOnLostFocus:false",
            "fullscreen:false"
        ) | Set-Content -Path $options -Encoding ASCII
    } else {
        # 语言固定成简体中文（游戏里界面、断开提示都是中文）
        $ot = [System.IO.File]::ReadAllText($options)
        if ($ot -match '(?m)^lang:') {
            $ot = $ot -replace '(?m)^lang:.*', 'lang:zh_cn'
        } else {
            $ot = "lang:zh_cn`r`n" + $ot
        }
        [System.IO.File]::WriteAllText($options, $ot, (New-Object System.Text.UTF8Encoding($false)))
    }

    # 离线 UUID
    $md5 = [System.Security.Cryptography.MD5]::Create()
    $hash = $md5.ComputeHash([Text.Encoding]::UTF8.GetBytes("OfflinePlayer:$name"))
    $hex = [BitConverter]::ToString($hash).Replace("-", "").ToLower()
    $uuid = "{0}-{1}-{2}-{3}-{4}" -f $hex.Substring(0, 8), $hex.Substring(8, 4), $hex.Substring(12, 4), $hex.Substring(16, 4), $hex.Substring(20, 12)

    if ($useTemplate) {
        # 复用 PCL 的参数：只换用户名 / UUID / 游戏目录 / 服务器
        $cmd = $exeArgs
        $cmd = [regex]::Replace($cmd, '--quickPlaySingleplayer\s+("[^"]*"|[^\s]+)', '')
        $cmd = Set-CmdArg $cmd "username" $name
        $cmd = Set-CmdArg $cmd "uuid" $uuid
        $cmd = Set-CmdArg $cmd "gameDir" ('"' + $botDir + '"')
        if ($Server) {
            $cmd = Set-CmdArg $cmd "quickPlayMultiplayer" $Server
        } else {
            $cmd = [regex]::Replace($cmd, '\s*--quickPlayMultiplayer\s+("[^"]*"|[^\s]+)', '')
        }
        $argLine = $cmd

        if ($PrintOnly) {
            Write-Host ""
            Write-Host "[$name] 启动命令："
            Write-Host "`"$exePath`" $argLine"
        } else {
            Write-Host "启动 $name ..." -ForegroundColor Green
            Start-Process -FilePath $exePath -ArgumentList $argLine -WorkingDirectory $botDir | Out-Null
            Start-Sleep -Seconds 3
        }
    } else {
        $parts = @("-Xmx${MemoryMB}M")
        if (Test-Path $natives) { $parts += "-Djava.library.path=$natives" }
        $parts += @(
            "-Dfile.encoding=UTF-8",
            "-cp", "`"$cpString`"",
            $mainClass,
            "--gameDir", "`"$botDir`"",
            "--assetsDir", "`"$assetsDir`"",
            "--assetIndex", $assetIndex,
            "--version", $Version,
            "--username", $name,
            "--uuid", $uuid,
            "--accessToken", "0",
            "--userType", "legacy"
        )
        if ($Server) { $parts += @("--quickPlayMultiplayer", $Server) }
        $argLine = $parts -join " "

        if ($PrintOnly) {
            Write-Host ""
            Write-Host "[$name] 启动命令："
            Write-Host "`"$JavaExe`" $argLine"
        } else {
            Write-Host "启动 $name ..." -ForegroundColor Green
            Start-Process -FilePath $JavaExe -ArgumentList $argLine -WorkingDirectory $botDir | Out-Null
            Start-Sleep -Seconds 2
        }
    }
}

Write-Host ""
if ($Server) {
    Write-Host "完成。每个机器人会自己连上 $Server。" -ForegroundColor Cyan
} else {
    Write-Host "完成。机器人已打开，停在主菜单，手动选服务器进就行。" -ForegroundColor Cyan
}
if ($Server) { Write-Host "注意：服务器要一直开着；服务器一关，机器人就掉线。" -ForegroundColor Yellow }
Write-Host "机器人各自的游戏目录在 $botsRoot\<名字>，想单独改配置就改那里的 config\autominer.json。" -ForegroundColor Gray
