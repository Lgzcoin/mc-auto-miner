# 自动采矿多开助手（Minecraft Java 26.2 / Fabric）

一个给自己服务器用的挂机挖矿工具包：**客户端模组 + 一键多开启动器 + 实时控制台**。适合“多个小号同时挂机挖矿、按矿种分工、自动进服登录、挖满回箱存放”的场景。

> 只在**自己开的服务器**或**明确允许挂机**的服务器上使用；多开前先看清服务器规则。

## 它能做什么

**模组（autominer）**
- 自动进服（`--quickPlayMultiplayer` 直接连）、断线自动重连（间隔可调）
- 自动认证：按配置自动发 `/login`，误配 `/reg` 会自动转 `/login`
- 图形登录自动化：进服弹出容器/铁砧样式的登录菜单时，自动找「登录/进入/确认/密码」格点掉；铁砧界面会自动填密码提交
- 行为准则弹窗自动同意
- 挖矿：接 Baritone 寻路，按矿种清单开挖；背包快满或到时间自动走到存矿箱存放，循环
- 开荒：进服 → 发你设的指令（如 `/home base`）→ 寻路到基地/矿点 → 到了才开挖
- 状态上报：每秒写一份 `autominer_status.txt`（画面、维度、坐标、血饱、在做什么、登录状态、心跳）

**启动器（multi）**
- 一个面板改名字、改矿种（支持中文矿名）、加号、删号、设基地与存矿箱、设进服指令、改重连间隔
- 每号独立游戏目录 `bots/<名字>`，改名目录一起改，删号软删除到 `_旧目录`
- 自动整理 mods：升级本模组、挪走冲突版本、缺 Fabric API / Baritone 时警告
- 名字强制 3–16 位英文/数字/下划线（服务器普遍不收中文名），中文名会自动换成 `MinerN`

**控制台**
- 状态表：名字 / 画面 / 维度 / 位置 / 血食 / 在干什么 / 登录 / 攒满 / 心跳
- 顶部一句话概览：`共 3 个号：2 个在挖矿，1 个没登录`
- 约 15 秒自动播一行「看板」，画面或状态变了才播
- `detail 名字` 看这个号的画面标题、最近聊天、最近动作（自动点登录成没成在这里能看到）
- `watch off` 关播报，`status` 重画表，`quit` 退出控制台（机器人继续跑）

## 环境要求

| 项 | 版本 |
| --- | --- |
| Minecraft | 26.2（Fabric） |
| Fabric Loader | ≥ 0.19.3 |
| Fabric API | 0.161.0+26.2 |
| Java | 25 |
| Baritone | `baritone-api-fabric`（必须 api 版，standalone 精简版没有 `baritone.api`） |

## 安装

1. 把 `dist/autominer-1.9.9.jar` 放进 `.minecraft/mods/`
2. 放入对应 26.2 的 Fabric API 和 `baritone-api-fabric` jar
3. 把 `multi/` 整个文件夹放到桌面，双击 `启动多开.bat`

启动器会读 `multi/服务器地址.txt` 里的地址（`host:port`，填 `skip` = 只开游戏不进服），把每个号的数据放在 `.minecraft/bots/<名字>/`，并自动把模组复制/升级到 `.minecraft/mods/`。

详细操作见 `multi/启动方式说明.txt`。

## 从源码构建

```bash
# 需要 JDK 25 和 Gradle（本项目用 9.x 测试）
cd autominer
# 编译期需要 Baritone API：把 baritone-api-fabric 的 jar 放到 autominer/libs/baritone-api-1.19.0.jar
./gradlew build      # 或 gradle build
# 产物：build/libs/autominer-<版本>.jar
```

## 已知限制

- 图形登录只能点**容器/铁砧**类界面；要求你在告示牌或书里手打字的界面需要人工点一次
- 同 IP 同时在线数量受服务器限制（常见是 2），第 3 个号要么换出口 IP，要么别同时开
- 自动挖矿依赖 Baritone 的寻路；远距离移动很慢，建议先进服传到基地附近
- 状态表的“心跳”是状态文件多少秒没更新：正常 0–1 秒，超过 15 秒变红说明这个客户端卡住或断线

## 目录结构

```
autominer/          模组源码（Java / Fabric）
multi/              多开启动器与控制台（PowerShell 5.1）
dist/               已构建的模组 jar
```

## 许可

MIT，见 `LICENSE`。第三方依赖（Fabric API、Baritone）各自遵循其许可，本仓库不包含它们的二进制。
