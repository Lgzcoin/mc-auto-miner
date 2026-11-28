param(
    [string]$GameRoot = "",
    [string]$Bots = "",
    [int]$SelfTest = 0
)

# ============================================================
#  机器人控制台 —— 一个窗口：实时看输出 + 直接下指令
#  指令会写进各机器人的 autominer_cmd.txt，游戏里每秒读一次执行
# ============================================================

# 控制台显示中文：把窗口代码页和输出编码都切成 UTF-8，避免中文变乱码
try {
    $chcp = & "$env:SystemRoot\System32\chcp.com" 65001 2>$null
    [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
    $OutputEncoding = New-Object System.Text.UTF8Encoding($false)
} catch { }

$ErrorActionPreference = "Stop"

# 任何时候出错都别让窗口一闪就没，留个话
trap {
    Write-Host ""
    Write-Host ("[错误] " + $_.Exception.Message) -ForegroundColor Red
    Write-Host "按回车关闭。" -ForegroundColor Gray
    Read-Host | Out-Null
    exit 1
}

# 从 矿工配置.txt 读“当前要显示的号”。控制台以这个文件为准，不再把 bots 里
# 已经删掉、但目录还没清的号重新列出来。
function Get-PlanNames {
    $out = @()
    $planFile = Join-Path $PSScriptRoot "矿工配置.txt"
    if (-not (Test-Path $planFile)) { return $out }
    foreach ($line in (Get-Content $planFile -Encoding UTF8)) {
        $t = "$line".Trim()
        if ($t -eq "" -or $t.StartsWith("#")) { continue }
        $kv = $t -split "=", 2
        if ($kv.Count -lt 2) { continue }
        $k = $kv[0].Trim()
        if ($k -match "^(密码|登录密码|password|pass|重连|重连秒数|重连间隔|retry|retrySeconds|进服指令|进服命令|onJoin|onJoinCommands)$") { continue }
        if ($k -match "^[A-Za-z0-9_]{3,16}$") { $out += $k }
    }
    return $out
}

$planNames = @(Get-PlanNames)

# 找游戏目录：命令行 > 一键多开记下的 游戏目录.txt > 默认 .minecraft > 在 versions 里搜 bots
$savedFile = Join-Path $PSScriptRoot "游戏目录.txt"
$candidates = @()
if (-not [string]::IsNullOrWhiteSpace($GameRoot)) { $candidates += ($GameRoot -replace '"', '').Trim() }
if (Test-Path $savedFile) {
    $t = Get-Content $savedFile -Raw
    if ($t) { $candidates += ($t -replace '"', '').Trim() }
}
$candidates += "$env:APPDATA\.minecraft"

$botsRoot = $null
$fallbackRoot = $null
foreach ($c in $candidates) {
    if ([string]::IsNullOrWhiteSpace($c)) { continue }
    $br = Join-Path $c "bots"
    if (-not (Test-Path $br)) { continue }
    if (-not $fallbackRoot) { $fallbackRoot = $br }
    $matched = 0
    if ($planNames.Count -gt 0) {
        foreach ($n in $planNames) {
            if (Test-Path (Join-Path $br $n)) { $matched++ }
        }
    }
    # 优先选“配置里的号真的存在”的游戏目录，避免旧 游戏目录.txt 指向测试目录。
    if ($planNames.Count -eq 0 -or $matched -gt 0) {
        $botsRoot = $br
        $GameRoot = $c
        break
    }
}
if (-not $botsRoot) {
    $vdir = Join-Path "$env:APPDATA\.minecraft" "versions"
    if (Test-Path $vdir) {
        $hit = Get-ChildItem $vdir -Directory | Where-Object { Test-Path (Join-Path $_.FullName "bots") } | Select-Object -First 1
        if ($hit) {
            $botsRoot = Join-Path $hit.FullName "bots"
            $GameRoot = $hit.FullName
        }
    }
}
if (-not $botsRoot -and $fallbackRoot) {
    $botsRoot = $fallbackRoot
    $GameRoot = Split-Path -Parent $fallbackRoot
}
if (-not $botsRoot) {
    Write-Host ""
    Write-Host "[错误] 找不到 bots 目录，说明还没成功跑过 启动多开.bat。" -ForegroundColor Red
    Write-Host ("我已经找过这些位置：") -ForegroundColor Gray
    foreach ($c in $candidates) { Write-Host ("  " + $c) -ForegroundColor Gray }
    Write-Host ""
    Write-Host "先双击 启动多开.bat 起机器人，再回来开控制台。" -ForegroundColor Yellow
    Write-Host "按回车关闭。" -ForegroundColor Gray
    Read-Host | Out-Null
    exit 1
}
# 把真正选中的目录记回去，下次不用再猜。
try { [System.IO.File]::WriteAllText($savedFile, $GameRoot, (New-Object System.Text.UTF8Encoding($false))) } catch { }

function Get-BotDirs {
    $nowPlan = @(Get-PlanNames)
    if (-not [string]::IsNullOrWhiteSpace($Bots)) {
        $wanted = @($Bots.Split(",") | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne "" })
    } elseif ($nowPlan.Count -gt 0) {
        $wanted = @($nowPlan)
    } else {
        # 没有配置文件的旧安装：保持原来的“扫 bots 目录”行为
        return @(Get-ChildItem $botsRoot -Directory | Where-Object { $_.Name -match "^[A-Za-z0-9_]{3,16}$" } | Sort-Object Name)
    }
    $list = @()
    foreach ($n in $wanted) {
        $d = Join-Path $botsRoot $n
        if (Test-Path $d) { $list += Get-Item $d }
    }
    return $list
}

$dirs = Get-BotDirs
if ($dirs.Count -eq 0) {
    Write-Host ""
    Write-Host "[提示] bots 里还没有能识别的机器人（没建号、名字太短，或都被删了）。" -ForegroundColor Yellow
    Write-Host ("  位置：" + $botsRoot + "    名字要 3-16 位英文/数字/下划线") -ForegroundColor Gray
    Write-Host "  双击 启动多开.bat 建号并启动，再回来看控制台。" -ForegroundColor Yellow
    if ($SelfTest -le 0) { Write-Host "按回车关闭。" -ForegroundColor Gray; Read-Host | Out-Null }
    exit 0
}

$offsets = @{}
foreach ($d in $dirs) {
    $offsets["$($d.Name)_out"] = 0
    $offsets["$($d.Name)_log"] = 0
}
$width = ($dirs | ForEach-Object { $_.Name.Length } | Measure-Object -Maximum).Maximum
if ($width -lt 4) { $width = 4 }

function Read-New([string]$path, [string]$key) {
    if (-not (Test-Path $path)) { return @() }
    if (-not $offsets.ContainsKey($key)) { $offsets[$key] = 0 }
    $len = (Get-Item $path).Length
    $off = $offsets[$key]
    if ($off -gt $len) { $off = 0 }
    if ($len -eq $off) { return @() }
    $fs = [System.IO.File]::Open($path, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
    try {
        [void]$fs.Seek($off, [System.IO.SeekOrigin]::Begin)
        $buf = New-Object byte[] ($len - $off)
        $read = 0
        while ($read -lt $buf.Length) {
            $n = $fs.Read($buf, $read, $buf.Length - $read)
            if ($n -le 0) { break }
            $read += $n
        }
    } finally { $fs.Close() }
    if ($read -le 0) { return @() }
    if ($read -lt $buf.Length) { $buf = $buf[0..($read - 1)] }

    # 用 UTF-8 解码；末尾若是半个汉字/半个换行，留到下一次再读，避免出现乱码方块
    $enc = New-Object System.Text.UTF8Encoding($false, $false)
    $text = $enc.GetString($buf)
    $cut = 0
    for ($k = 0; $k -le 3; $k++) {
        $nB = $buf.Length - $k
        if ($nB -le 0) { break }
        $cand = if ($k -eq 0) { $text } else { $enc.GetString($buf, 0, $nB) }
        if (-not $cand.EndsWith([string][char]0xFFFD)) { $text = $cand; $cut = $k; break }
    }
    # 最后一行没写完就不输出，等它写完
    $hold = 0
    if (-not $text.EndsWith("`n")) {
        $nl = $text.LastIndexOf("`n")
        if ($nl -ge 0) {
            $tailTxt = $text.Substring($nl + 1)
            $hold = $enc.GetByteCount($tailTxt)
            $text = $text.Substring(0, $nl + 1)
        } else {
            $hold = $buf.Length - $cut
            $text = ""
        }
        $age = 0
        try { $age = ((Get-Date) - (Get-Item $path).LastWriteTime).TotalSeconds } catch { $age = 0 }
        if ($hold -gt 0 -and $age -gt 3) {
            $text = $enc.GetString($buf, 0, $buf.Length - $cut)
            $hold = 0
        }
    }
    $offsets[$key] = $off + ($buf.Length - $cut) - $hold
    return @($text -split "`r?`n" | Where-Object { $_ -ne "" })
}

$script:promptWidth = 0
function Clear-Prompt {
    if ([Console]::IsOutputRedirected) { $script:promptWidth = 0; return }
    if ($script:promptWidth -gt 0) {
        try { [Console]::Write("`r" + (" " * $script:promptWidth) + "`r") } catch { }
        $script:promptWidth = 0
    }
}
function Show-Prompt {
    if ([Console]::IsOutputRedirected) { return }
    Clear-Prompt
    try { [Console]::Write("命令> ") } catch { }
    $script:promptWidth = 6
}
function Out-Line([string]$text, [string]$color = "Gray") {
    Clear-Prompt
    Write-Host $text -ForegroundColor $color
    Show-Prompt
    $script:pollPrinted = $true
}

$script:showChat = $true
$script:lastScreen = @{}
$script:pollTicks = 0
$script:watchOn = $true
$script:watchTicks = 0
$script:lastWatch = @{}

function Broadcast-Status {
    if (-not $script:watchOn) { return }
    if (-not $dirs) { return }
    $script:watchTicks++
    if ($script:watchTicks -lt 75) { return }   # 15 秒一次
    $script:watchTicks = 0
    foreach ($d in $dirs) {
        $st = Read-BotStatus $d
        if (-not $st) { continue }
        $sig = $st["screen"] + "|" + $st["title"] + "|" + $st["mining"] + "|" + $st["inworld"] + "|" + $st["auth"] + "|" + $st["trigger"]
        if ($script:lastWatch.ContainsKey($d.Name) -and $script:lastWatch[$d.Name] -eq $sig) { continue }
        $script:lastWatch[$d.Name] = $sig
        $scr = Screen-Name $st["screen"] $st["title"]
        if ($st["inworld"] -eq "1") {
            $txt = "看板 " + $d.Name + "  画面=" + $scr + "  位置=" + $st["pos"] + "  " + $st["hp"] + "/" + $st["food"] + "  " + $st["mining"] + "  " + $st["auth"]
        } else {
            $extra = ""
            if ($st["join"] -and $st["join"] -ne "0") { $extra = " 试" + $st["join"] + "次" }
            $txt = "看板 " + $d.Name + "  画面=" + $scr + "  未进服" + $extra
        }
        Out-Line $txt "DarkCyan"
    }
}

function Poll {
    $script:pollTicks++
    foreach ($d in $dirs) {
        $tag = ("[{0}]" -f $d.Name).PadRight($width + 2)

        foreach ($line in (Read-New (Join-Path $d.FullName "autominer_out.txt") "$($d.Name)_out")) {
            if ($line.StartsWith("> ")) {
                Out-Line ("$tag " + $line) "White"
            } elseif ($line -match "出错|失败|没有|不支持|不认识|用法") {
                Out-Line ("$tag " + $line) "Red"
            } else {
                Out-Line ("$tag " + $line) "Green"
            }
        }

        # 界面/动作变了就提示一行，方便一眼看出它们在看什么画面
        if ($script:pollTicks % 5 -eq 0) {
            $st = Read-BotStatus $d
            if ($st) {
                $sig = $st["screen"] + "|" + $st["title"] + "|" + $st["mining"] + "|" + $st["inworld"]
                if (-not $script:lastScreen.ContainsKey($d.Name)) {
                    $script:lastScreen[$d.Name] = $sig
                    Out-Line ("[状态] " + (Status-Line $d).TrimStart()) "DarkCyan"
                } elseif ($script:lastScreen[$d.Name] -ne $sig) {
                    $script:lastScreen[$d.Name] = $sig
                    if ($st["inworld"] -eq "1") {
                        Out-Line ("[状态] " + (Status-Line $d).TrimStart()) (Status-Color $st)
                    } else {
                        Out-Line ("[状态] " + $d.Name + " 界面=" + (Screen-Name $st["screen"] $st["title"])) "DarkCyan"
                    }
                }
            }
        }

        foreach ($line in (Read-New (Join-Path $d.FullName "logs\latest.log") "$($d.Name)_log")) {
            if ($line -match "^\s*$") { continue }
            # 巴里通寻路的刷屏噪音，一律不要
            if ($line -match "(?i)\[STDOUT\]|In movement traverse|movements considered|Open set size|PathNode|nodes per second|Path goes for|cost coefficient|yolo|Repath|Calc path|Setting goal") { continue }

            # 去掉 [18:38:11] [Render thread/INFO]: 这类前缀，只留时间
            $body = $line -replace "^\s*\[(\d\d:\d\d:\d\d)\][^:]*:\s*", '[$1] '
            if ($body -match "^\s*$") { $body = $line }

            # 聊天栏内容：这就是它们屏幕上看到的字
            if ($body -match "\[CHAT\]") {
                if (-not $script:showChat) { continue }
                $txt = ($body -replace ".*\[CHAT\]\s*", "") -replace "§[0-9a-fk-orA-FK-OR]", ""
                $txt = $txt.Trim()
                if ($txt -eq "") { continue }
                if ($txt -match "未登录|请先登录|/login|/reg|注册|验证码|验证|login|register") {
                    Out-Line ("$tag 聊天> " + $txt) "Yellow"
                } elseif ($txt -match "桃花源|服务器|警告|踢|封|欢迎|提示|系统") {
                    Out-Line ("$tag 聊天> " + $txt) "Cyan"
                } else {
                    Out-Line ("$tag 聊天> " + $txt) "DarkGray"
                }
                continue
            }

            if ($body -match "(?i)(autominer|自动|男中音|baritone|进服|连接|服务器|认证|登录|login|auth|connect|join|disconnect|kick|kicked|banned|refused|timeout|exception|error|warn|fabric)") {
                if ($body -match "(?i)(error|exception|refused|timeout|disconnect|kick|banned)") {
                    Out-Line ("$tag " + $body) "Red"
                } elseif ($body -match "(?i)(进服|连接|服务器|connect|join)") {
                    Out-Line ("$tag " + $body) "Cyan"
                } else {
                    Out-Line ("$tag " + $body) "DarkGray"
                }
            }
        }
    }
    Broadcast-Status
}

function Get-DispWidth([string]$s) {
    $w = 0
    foreach ($ch in $s.ToCharArray()) {
        $c = [int]$ch
        if (($c -ge 0x1100 -and $c -le 0x115F) -or ($c -ge 0x2E80 -and $c -le 0xA4CF) -or
            ($c -ge 0xAC00 -and $c -le 0xD7A3) -or ($c -ge 0xF900 -and $c -le 0xFAFF) -or
            ($c -ge 0xFE10 -and $c -le 0xFE19) -or ($c -ge 0xFF00 -and $c -le 0xFF60) -or
            ($c -ge 0xFFE0 -and $c -le 0xFFE6)) { $w += 2 } else { $w += 1 }
    }
    return $w
}

function PadD([string]$s, [int]$w) {
    if ($null -eq $s) { $s = "" }
    $n = Get-DispWidth $s
    if ($n -ge $w) { return $s }
    return $s + (" " * ($w - $n))
}

function TrimD([string]$s, [int]$w) {
    if ($null -eq $s) { return "" }
    if ((Get-DispWidth $s) -le $w) { return $s }
    $out = ""
    foreach ($ch in $s.ToCharArray()) {
        if ((Get-DispWidth ($out + $ch)) -gt ($w - 1)) { break }
        $out += $ch
    }
    return $out + "…"
}

function Read-BotStatus($dir) {
    $f = Join-Path $dir.FullName "autominer_status.txt"
    if (-not (Test-Path $f)) { return $null }
    $kv = @{}
    foreach ($ln in (Get-Content $f -Encoding UTF8 -ErrorAction SilentlyContinue)) {
        $i = $ln.IndexOf("=")
        if ($i -gt 0) { $kv[$ln.Substring(0, $i)] = $ln.Substring($i + 1) }
    }
    return $kv
}

function Screen-Name([string]$cls, [string]$title) {
    if (-not $cls -or $cls -eq "none") { return "游戏世界" }
    if ($cls -match "CodeOfConduct") {
        if ($title) { return "行为准则「" + $title + "」" }
        return "行为准则弹窗"
    }
    if ($cls -match "^(SimpleDialog|DialogScreen|NoticeScreen)$") {
        if ($title) { return "对话框「" + (TrimD $title 13) + "」" }
        return "对话框(要点确定)"
    }
    if ($cls -match "Loading|Progress|ReceivingLevel") { return "加载中" }
    if ($cls -match "Death") { return "死亡界面(已死)" }
    if ($cls -match "Title") { return "标题界面" }
    if ($cls -match "Disconnected") {
        if ($title -and $title -notmatch "Connection Lost") { return "断开：" + (TrimD $title 9) }
        return "断开：满服/被踢"
    }
    if ($title) {
        if ($cls -match "ContainerScreen") { return "「" + (TrimD $title 9) + "」菜单" }
        return "「" + $title + "」"
    }
    switch -Regex ($cls) {
        "^TitleScreen$"        { return "标题界面" }
        "^ConnectScreen$"      { return "连接中" }
        "^GameMenuScreen$"     { return "游戏菜单(ESC)" }
        "^InventoryScreen$"    { return "背包界面" }
        "^.*ContainerScreen$"  { return "容器/菜单界面" }
        "^.*Screen$"           { return ($cls -replace "Screen$", "") + " 界面" }
        default                { return $cls }
    }
}

function Status-Line($d, [bool]$withHead = $false) {
    if ($withHead) {
        return ("  " + (PadD "名字" 10) + (PadD "画面" 19) + (PadD "维度" 7) + (PadD "位置" 16) + (PadD "血食" 6) + (PadD "在干什么" 13) + (PadD "登录" 7) + (PadD "攒满" 8) + "心跳")
    }
    $st = Read-BotStatus $d
    if (-not $st) {
        return ("  " + (PadD (TrimD $d.Name 9) 10) + "还没状态（游戏没起来 / 已退出）")
    }
    $scr = Screen-Name $st["screen"] $st["title"]
    $beat = "?"
    $bf = Join-Path $d.FullName "autominer_status.txt"
    if (Test-Path $bf) {
        $sec = [int](((Get-Date) - (Get-Item $bf).LastWriteTime).TotalSeconds)
        if ($sec -lt 0) { $sec = 0 }
        $beat = "$sec s"
    }
    $auth = $st["auth"]; if (-not $auth) { $auth = "-" }
    if ($st["inworld"] -ne "1") {
        $mode = "未进服"
        if ($st["enabled"] -eq "1") {
            $mode = "在服外"
            $j = $st["join"]
            if ($j -and $j -ne "0") { $mode = "试" + $j + "次进服" }
        }
        return ("  " + (PadD (TrimD $d.Name 9) 10) + (PadD (TrimD $scr 18) 19) + (PadD "未进服" 7) + (PadD "-" 16) + (PadD "-" 6) + (PadD (TrimD $mode 12) 13) + (PadD $auth 7) + (PadD "-" 8) + $beat)
    }
    $dim = $st["dim"]
    if ($dim -like "*nether*") { $dim = "下界" } elseif ($dim -like "*end*") { $dim = "末地" } else { $dim = "主世界" }
    $pos = $st["pos"]; if (-not $pos) { $pos = "-" }
    $hp = $st["hp"]; $food = $st["food"]
    $mining = $st["mining"]; if (-not $mining) { $mining = "?" }
    $en = $st["enabled"]
    if ($en -eq "0" -or $en -eq "false") { $mining = "挖矿已关闭" }
    $mining = TrimD $mining 12
    $trig = $st["trigger"]; if (-not $trig) { $trig = "-" }
    return ("  " + (PadD (TrimD $d.Name 9) 10) + (PadD (TrimD $scr 18) 19) + (PadD $dim 7) + (PadD (TrimD $pos 15) 16) + (PadD ($hp + "/" + $food) 6) + (PadD $mining 13) + (PadD $auth 7) + (PadD $trig 8) + $beat)
}

function Status-Color($st) {
    if (-not $st) { return "DarkGray" }
    if ($st["inworld"] -ne "1") { return "DarkYellow" }
    if ($st["auth"] -eq "未登录" -or $st["auth"] -eq "未发送") { return "Yellow" }
    $en0 = $st["enabled"]
    if ($en0 -eq "0" -or $en0 -eq "false") { return "DarkYellow" }
    $m = $st["mining"]
    if ($m) {
        if ($m -like "*挖矿*") { return "Green" }
        if ($m -like "*前往基地*") { return "Cyan" }
        if ($m -like "*前往箱子*") { return "Cyan" }
        if ($m -like "*开箱*" -or $m -like "*存放*") { return "Magenta" }
        if ($m -like "*收尾*") { return "DarkYellow" }
    }
    return "Gray"
}

function Get-Overview {
    $n = 0; $mining = 0; $busy = 0; $off = 0; $stuck = 0; $noauth = 0
    foreach ($d in $dirs) {
        $n++
        $st = Read-BotStatus $d
        if (-not $st) { $off++; continue }
        $bf = Join-Path $d.FullName "autominer_status.txt"
        $age = 999
        if (Test-Path $bf) { $age = ((Get-Date) - (Get-Item $bf).LastWriteTime).TotalSeconds }
        if ($st["inworld"] -ne "1") { $off++; continue }
        if ($age -gt 15) { $stuck++; continue }
        if ($st["auth"] -ne "已登录") { $noauth++; continue }
        $en0 = $st["enabled"]
        if ($en0 -eq "0" -or $en0 -eq "false" -or $st["mining"] -like "*已关闭*") { $busy++ }
        elseif ($st["mining"] -like "*挖矿*") { $mining++ }
        else { $busy++ }
    }
    $txt = "共 " + $n + " 个号：" + $mining + " 个在挖矿"
    if ($busy -gt 0) { $txt += "，" + $busy + " 个在忙别的（赶路/存矿）" }
    if ($noauth -gt 0) { $txt += "，" + $noauth + " 个没登录" }
    if ($off -gt 0) { $txt += "，" + $off + " 个没进服" }
    if ($stuck -gt 0) { $txt += "，" + $stuck + " 个卡住了" }
    return $txt
}

function Show-StatusTable {
    Clear-Prompt
    Write-Host ""
    $headTxt = "  ╭─ 机器人状态 ─ " + (Get-Date -Format "HH:mm:ss") + " "
    $fill0 = 96 - (Get-DispWidth $headTxt)
    if ($fill0 -lt 4) { $fill0 = 4 }
    Write-Host ($headTxt + ("─" * $fill0)) -ForegroundColor DarkCyan
    Write-Host ("  " + (Get-Overview)) -ForegroundColor Yellow
    Write-Host (Status-Line $null $true) -ForegroundColor DarkGray
    Write-Host ("  " + ("─" * 93)) -ForegroundColor DarkCyan
    foreach ($d in $dirs) {
        $st = Read-BotStatus $d
        $c = Status-Color $st
        $bf = Join-Path $d.FullName "autominer_status.txt"
        if ($st -and (Test-Path $bf)) {
            $age = ((Get-Date) - (Get-Item $bf).LastWriteTime).TotalSeconds
            if ($age -gt 15 -and $st["inworld"] -eq "1") { $c = "DarkRed" }
        }
        Write-Host (Status-Line $d) -ForegroundColor $c
    }
    Write-Host ("  " + ("─" * 93)) -ForegroundColor DarkCyan
    Write-Host "  画面=它屏幕上正显示什么    在干什么=挖矿现在做的事" -ForegroundColor DarkGray
    Write-Host "  登录=账号状态    攒满=攒多少个去回箱" -ForegroundColor DarkGray
    Write-Host "  心跳=状态文件几秒前更新，正常 0-1 秒；超过 15 秒 = 游戏卡了/断线了（整行变红）" -ForegroundColor DarkGray
    Write-Host "  绿=挖矿中  青=赶路  紫=存矿  黄=未登录/断线  红=卡住(15秒没动静)" -ForegroundColor DarkGray
    Write-Host ("  ╰" + ("─" * 92) + "╯") -ForegroundColor DarkCyan
    Write-Host ""
    Show-Prompt
}

function Send-Cmd([string]$botName, [string]$cmd) {
    $d = $dirs | Where-Object { $_.Name -eq $botName } | Select-Object -First 1
    if (-not $d) { return $false }
    $file = Join-Path $d.FullName "autominer_cmd.txt"
    $enc = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::AppendAllText($file, $cmd + "`n", $enc)
    return $true
}

Write-Host ""
Write-Host ("  " + ("═" * 74)) -ForegroundColor DarkCyan
Write-Host "   机器人控制台    实时看画面 / 实时下指令" -ForegroundColor Cyan
Write-Host ("  " + ("═" * 74)) -ForegroundColor DarkCyan
Write-Host ("   游戏目录  " + $GameRoot) -ForegroundColor Gray
Write-Host ("   目标号    " + (($dirs | ForEach-Object { $_.Name }) -join "、")) -ForegroundColor White
Write-Host "   help 看全部指令    quit 退出（机器人继续跑）" -ForegroundColor Gray
Write-Host ("  " + ("═" * 74)) -ForegroundColor DarkCyan

# 先读一遍已有内容，避免刷屏历史（只显示从现在开始的新输出）
foreach ($d in $dirs) {
    $o = Join-Path $d.FullName "autominer_out.txt"
    if (Test-Path $o) { $offsets["$($d.Name)_out"] = (Get-Item $o).Length }
    $l = Join-Path $d.FullName "logs\latest.log"
    if (Test-Path $l) { $offsets["$($d.Name)_log"] = (Get-Item $l).Length }
    if (-not (Test-Path $l)) {
        Write-Host ("[提醒] " + $d.Name + " 还没日志文件（logs\latest.log），可能游戏没真正启动") -ForegroundColor Yellow
    }
}
Write-Host ("已跳过历史输出，从现在起的新内容才会显示。想看历史输：logs 名字") -ForegroundColor DarkGray

Show-StatusTable
Write-Host "  输出颜色：绿=指令回显  青=进服/连接  红=出错  灰=其他    [状态]=画面刚变化" -ForegroundColor DarkGray

if ($SelfTest -gt 0) {
    Write-Host ("  [自检] 连续刷新 " + $SelfTest + " 秒，看有没有报错/乱码…") -ForegroundColor DarkGray
    $swT = [Diagnostics.Stopwatch]::StartNew()
    while ($swT.Elapsed.TotalSeconds -lt $SelfTest) {
        Poll
        Start-Sleep -Milliseconds 200
    }
    Write-Host "  [自检] 结束，没有异常。" -ForegroundColor Green
    exit 0
}

$reader = [Console]::In
Show-Prompt
$task = $reader.ReadLineAsync()

while ($true) {
    while (-not $task.IsCompleted) {
        Poll
        Start-Sleep -Milliseconds 200
    }

    $line = $task.Result
    if ($null -eq $line) { break }
    $line = $line.Trim()
    if ($line -eq "") {
        Show-Prompt
        $task = $reader.ReadLineAsync()
        continue
    }

    if ($line -eq "quit" -or $line -eq "exit" -or $line -eq "q") { break }

    if ($line -eq "help" -or $line -eq "?") {
        Write-Host "" 
        Write-Host "用法：" -ForegroundColor Cyan
        Write-Host "  <机器人名字> <指令>     只对它下发，例如：矿工1 get diamond 64" -ForegroundColor Gray
        Write-Host "  all <指令>              所有机器人一起执行，例如：all stop" -ForegroundColor Gray
        Write-Host "  status（或 状态/面板）   列出每个机器人现在屏幕上是什么、在哪、在干什么" -ForegroundColor Gray
        Write-Host "  detail [名字]（或 详情） 看这个号屏幕上是什么、最近聊天和动作（不开游戏窗口也能看）" -ForegroundColor Gray
        Write-Host "  watch off / watch on    自动播报（默认开，约 15 秒一次，界面/状态变了才说一句）" -ForegroundColor Gray
        Write-Host "  chat off / chat on      关掉或打开聊天栏内容（默认开）" -ForegroundColor Gray
        Write-Host "  bots                    重新扫描机器人目录" -ForegroundColor Gray
        Write-Host "  quit                    退出控制台" -ForegroundColor Gray
        Write-Host ""
        Write-Host "可用指令（游戏里每秒读一次）：" -ForegroundColor Cyan
        Write-Host "  start / stop / status / diag / reload" -ForegroundColor Gray
        Write-Host "  get <物品> [数量]        例如 get diamond 64、get stick 16" -ForegroundColor Gray
        Write-Host "  interval <秒>            定时回箱，0=关" -ForegroundColor Gray
        Write-Host "  trigger <物品> <数量>    攒够回箱；trigger off 关掉" -ForegroundColor Gray
        Write-Host "  depositall               切换全部入库" -ForegroundColor Gray
        Write-Host "  ores list|add|remove|default" -ForegroundColor Gray
        Write-Host "  items list|add|remove|default" -ForegroundColor Gray
        Write-Host "  chest list|remove <名字>|clear" -ForegroundColor Gray
        Write-Host "  range <1-6>" -ForegroundColor Gray
        Write-Host ""
        Show-Prompt
        $task = $reader.ReadLineAsync()
        continue
    }

    if ($line -match "^(chat|聊天)\s*(on|off|开|关)?$") {
        $m = $matches[1]; $v = $matches[2]
        if ($v -eq "off" -or $v -eq "关") { $script:showChat = $false } elseif ($v) { $script:showChat = $true }
        Write-Host ("聊天栏显示：" + $(if ($script:showChat) { "开（显示它们看到的聊天）" } else { "关" })) -ForegroundColor Gray
        Show-Prompt
        $task = $reader.ReadLineAsync()
        continue
    }

    if ($line -eq "status" -or $line -eq "状态" -or $line -eq "面板" -or $line -eq "s") {
        Show-StatusTable
        Show-Prompt
        $task = $reader.ReadLineAsync()
        continue
    }

    if ($line -match "^(watch|播报|看板)\s*(on|off|开|关)?$") {
        $v = $matches[2]
        if ($v -eq "off" -or $v -eq "关") { $script:watchOn = $false } elseif ($v) { $script:watchOn = $true }
        Write-Host ("自动播报：" + $(if ($script:watchOn) { "开（约 15 秒一次，画面/状态变了才说一句）" } else { "关" })) -ForegroundColor Gray
        Show-Prompt
        $task = $reader.ReadLineAsync()
        continue
    }

    if ($line -match "^(detail|详情)\s*([A-Za-z0-9_]{0,16})$") {
        $who = $matches[2]
        $targets = if ($who) { @($dirs | Where-Object { $_.Name -eq $who }) } else { $dirs }
        if (-not $targets -or $targets.Count -eq 0) {
            Write-Host ("没有叫 " + $who + " 的号，用 bots 看现有名单") -ForegroundColor Yellow
            Show-Prompt
            $task = $reader.ReadLineAsync()
            continue
        }
        foreach ($d in $targets) {
            $st = Read-BotStatus $d
            Write-Host ""
            Write-Host ("=== " + $d.Name + " ===") -ForegroundColor Cyan
            if (-not $st) {
                Write-Host "  还没状态文件：游戏没起来或已退出" -ForegroundColor Yellow
                continue
            }
            $bf = Join-Path $d.FullName "autominer_status.txt"
            $beat = "-"
            if (Test-Path $bf) {
                $sec2 = [int](((Get-Date) - (Get-Item $bf).LastWriteTime).TotalSeconds)
                if ($sec2 -lt 0) { $sec2 = 0 }
                $beat = "$sec2 秒前"
            }
            $scr = Screen-Name $st["screen"] $st["title"]
            Write-Host ("  画面上：" + $scr + $(if ($st["title"] -and $scr -ne ("「" + $st["title"] + "」")) { "（标题：" + $st["title"] + "）" } else { "" })) -ForegroundColor White
            Write-Host ("  位置：" + $(if ($st["pos"]) { $st["pos"] } else { "-" }) + "  维度：" + $(if ($st["dim"]) { $st["dim"] } else { "-" }) + "  血/饱：" + $st["hp"] + "/" + $st["food"]) -ForegroundColor Gray
            Write-Host ("  在做：" + $(if ($st["mining"]) { $st["mining"] } else { "-" }) + "  登录：" + $(if ($st["auth"]) { $st["auth"] } else { "-" }) + "  回箱：" + $(if ($st["trigger"]) { $st["trigger"] } else { "-" }) + "  挖矿开关：" + $st["enabled"]) -ForegroundColor Gray
            $pickTxt = $st["pick"]; if (-not $pickTxt) { $pickTxt = "-" }
            $freeTxt = $st["free"]; if (-not $freeTxt) { $freeTxt = "-" }
            $pickLow = $false
            if ($pickTxt -match '^(\d+)') { if ([int]$Matches[1] -le 15) { $pickLow = $true } }
            Write-Host ("  镐子耐久：" + $pickTxt + "  背包空位：" + $freeTxt) -ForegroundColor $(if ($pickLow) { "Yellow" } else { "Gray" })
            Write-Host ("  状态文件：" + $beat + "更新") -ForegroundColor DarkGray
            $l = Join-Path $d.FullName "logs\latest.log"
            if (Test-Path $l) {
                $tail = @(Get-Content $l -Encoding UTF8 -Tail 80 -ErrorAction SilentlyContinue)
                $chats = @($tail | Where-Object { $_ -match "\[CHAT\]" } | Select-Object -Last 4)
                if ($chats.Count -gt 0) {
                    Write-Host "  最近聊天：" -ForegroundColor Cyan
                    foreach ($c in $chats) {
                        $t = ($c -replace ".*\[CHAT\]\s*", "") -replace "§[0-9a-fk-orA-FK-OR]", ""
                        Write-Host ("    " + $t.Trim()) -ForegroundColor DarkGray
                    }
                }
                $keys = @($tail | Where-Object { $_ -match "(?i)(autominer|登录|认证|进服|连接|点击|失败|错误|exception|error)" -and $_ -notmatch "\[CHAT\]" } | Where-Object { $_ -notmatch "(?i)\[STDOUT\]|In movement" } | Select-Object -Last 6)
                if ($keys.Count -gt 0) {
                    Write-Host "  最近动作/提示：" -ForegroundColor Cyan
                    foreach ($k in $keys) {
                        $b = ($k -replace "^\s*\[(\d\d:\d\d:\d\d)\][^:]*:\s*", '[$1] ')
                        Write-Host ("    " + $b) -ForegroundColor DarkGray
                    }
                }
            } else {
                Write-Host "  没有 logs\latest.log（游戏可能没真正启动）" -ForegroundColor Yellow
            }
        }
        Show-Prompt
        $task = $reader.ReadLineAsync()
        continue
    }

    if ($line -eq "logs" -or $line -eq "log") {
        foreach ($d in $dirs) {
            Write-Host ""
            Write-Host ("=== " + $d.Name + " 最近日志 ===") -ForegroundColor Cyan
            $l = Join-Path $d.FullName "logs\latest.log"
            if (Test-Path $l) {
                $tail = Get-Content $l -Encoding UTF8 -Tail 30 -ErrorAction SilentlyContinue
                foreach ($tl in $tail) { Write-Host ("  " + $tl) -ForegroundColor DarkGray }
            } else {
                Write-Host "  （没有日志文件）" -ForegroundColor Yellow
            }
            $o = Join-Path $d.FullName "autominer_out.txt"
            if (Test-Path $o) {
                Write-Host "  --- 指令回显 ---" -ForegroundColor Gray
                foreach ($tl in (Get-Content $o -Encoding UTF8 -Tail 10 -ErrorAction SilentlyContinue)) { Write-Host ("  " + $tl) -ForegroundColor Green }
            }
        }
        Show-Prompt
        $task = $reader.ReadLineAsync()
        continue
    }

    if ($line -eq "config" -or $line -eq "info") {
        foreach ($d in $dirs) {
            $p = Join-Path $d.FullName "config\autominer.json"
            Write-Host ""
            Write-Host ("=== " + $d.Name + " ===") -ForegroundColor Cyan
            if (-not (Test-Path $p)) {
                Write-Host "  （还没有配置文件，先跑一次 启动多开.bat）" -ForegroundColor Yellow
                continue
            }
            try {
                $cfg = Get-Content $p -Raw | ConvertFrom-Json
            } catch {
                Write-Host "  配置文件不是合法 JSON，建议删掉重跑" -ForegroundColor Red
                continue
            }
            $ores = @($cfg.mineBlocks)
            $drops = @($cfg.depositItems)
            $chests = @($cfg.chests)
            Write-Host ("  挖：" + ($ores -join ", ")) -ForegroundColor Gray
            Write-Host ("  回箱矿物：" + ($drops -join ", ")) -ForegroundColor Gray
            Write-Host ("  定时回箱：" + $cfg.intervalSeconds + " 秒" + $(if ($cfg.depositTriggerItem) { "  攒够：" + $cfg.depositTriggerItem + " × " + $cfg.depositTriggerCount } else { "  攒够：关" })) -ForegroundColor Gray
            Write-Host ("  箱子：" + $chests.Count + " 个" + $(if ($chests.Count -gt 0) { "（" + (($chests | ForEach-Object { $_.name }) -join "、") + "）" } else { "" })) -ForegroundColor Gray
            Write-Host ("  自动进服：" + $(if ($cfg.autoJoinServer) { $cfg.autoJoinServer } else { "关" }) + "   进服自动开挖：" + $cfg.autoStartOnJoin + "   全部入库：" + $cfg.depositAll) -ForegroundColor Gray
            Write-Host ("  自动认证：" + $(if ($cfg.autoAuthCommands) { "已设置（" + $cfg.autoAuthDelaySeconds + " 秒后发）" } else { "关" })) -ForegroundColor Gray
        }
        Show-Prompt
        $task = $reader.ReadLineAsync()
        continue
    }

    if ($line -eq "bots" -or $line -eq "scan") {
        $dirs = Get-BotDirs
        Write-Host ("目标：" + (($dirs | ForEach-Object { $_.Name }) -join "、")) -ForegroundColor Cyan
        Show-Prompt
        $task = $reader.ReadLineAsync()
        continue
    }

    $parts = $line -split "\s+", 2
    $target = $parts[0]
    $cmd = if ($parts.Count -ge 2) { $parts[1] } else { "" }

    if ($cmd -eq "") {
        Write-Host "要写成：<机器人名字或 all> <指令>" -ForegroundColor Yellow
        Show-Prompt
        $task = $reader.ReadLineAsync()
        continue
    }

    if ($target -eq "all" -or $target -eq "全部") {
        foreach ($d in $dirs) {
            Send-Cmd $d.Name $cmd | Out-Null
        }
        Write-Host ("已下发到 " + $dirs.Count + " 个机器人：" + $cmd) -ForegroundColor Cyan
    } else {
        if (Send-Cmd $target $cmd) {
            Write-Host ("已下发给 " + $target + "：" + $cmd) -ForegroundColor Cyan
        } else {
            Write-Host ("没有这个机器人：" + $target) -ForegroundColor Red
        }
    }

    Show-Prompt
    $task = $reader.ReadLineAsync()
}

Write-Host ""
Write-Host "控制台已退出，机器人还在跑。要重新看输出就再双击一次。" -ForegroundColor Gray
