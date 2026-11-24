package com.doubao.autominer;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * 给“命令行窗口”用的指令入口：和 /am 的语义一致，但不依赖聊天命令。
 * 所有方法都返回一段给人看的结果文本。
 */
public final class AutoMinerApi {

    private AutoMinerApi() {
    }

    public static String execute(String raw) {
        String line = raw == null ? "" : raw.trim();
        if (line.isEmpty()) {
            return "";
        }
        if (line.startsWith("/")) {
            line = line.substring(1).trim();
        }
        String[] parts = line.split("\\s+");
        String cmd = parts[0].toLowerCase();
        MinerConfig config = AutoMiner.config();
        MinerController controller = AutoMiner.controller();

        switch (cmd) {
            case "help":
                return """
                        可用指令（前面加名字=只对该机器人，all=全部）：
                        start / stop              开始 / 停止采矿
                        status                    当前状态
                        get <物品> [数量]          挖+合成指定物品
                        interval <秒>              定时回箱，0=关
                        trigger <物品> <数量>      攒够回箱（trigger off 关）
                        depositall                切换“全部入库”
                        ores list|add <方块>|remove|default
                        items list|add <物品>|remove|default
                        chest list|remove <名字>|clear
                        range <1-6>               到箱子的判定半径
                        auth <内容>               立刻发一条认证（/login 之类）
                        autoauth <内容>           设自动认证，多条用 | 分隔
                        autoauth off / delay <秒> 关闭 / 改延迟
                        join <地址>               立刻连服务器（host:port）
                        autologin <地址|off>      进服自动连接
                        gui                       查看/设置图形登录菜单自动化
                        gui dump                  导出当前打开的菜单（看里面有什么）
                        gui kw <文字>             自动挑格子时认的字（默认 登录,进入,确认,密码）
                        gui set <标题关键词> <格子> <密码>   格子写 -1 = 自动挑
                        gui on / gui off
                        reload / diag             重载配置 / 男中音诊断""";
            case "start":
                if (controller.getJob() != null) {
                    return "当前有 get 任务，先 stop 再 start。";
                }
                controller.start();
                return "已开始自动采矿。";
            case "stop":
                controller.stop(false);
                return "已停止。";
            case "status":
                return status();
            case "diag":
                return BaritoneHook.diagnose();
            case "auth": {
                if (parts.length < 2) {
                    return "用法：auth <要发送的内容>（以 / 开头当命令，否则当聊天）";
                }
                String text = line.substring(line.toLowerCase().indexOf("auth") + 4).trim();
                boolean ok = AutoAuth.send(net.minecraft.client.Minecraft.getInstance().player, text);
                return ok ? "已发送认证指令。" : "现在没法发送（不在服务器里）。";
            }
            case "autoauth": {
                if (parts.length < 2 || parts[1].equalsIgnoreCase("off")) {
                    config.autoAuthCommands = "";
                    config.save();
                    return "已关闭进服自动认证。";
                }
                if (parts[1].equalsIgnoreCase("delay")) {
                    if (parts.length < 3) {
                        return "用法：autoauth delay <秒>";
                    }
                    try {
                        config.autoAuthDelaySeconds = Math.max(0, Math.min(120, Integer.parseInt(parts[2])));
                    } catch (NumberFormatException e) {
                        return "秒数要是整数。";
                    }
                    config.save();
                    return "进服后 " + config.autoAuthDelaySeconds + " 秒发送认证指令。";
                }
                config.autoAuthCommands = line.substring(line.toLowerCase().indexOf("autoauth") + 8).trim();
                config.save();
                return "已设置自动认证，共 " + config.autoAuthCommands.split("\\|").length + " 条（下次进服生效）。";
            }
            case "gui": {
                MinerConfig.GuiLogin g = config.guiLogin;
                if (g == null) {
                    g = new MinerConfig.GuiLogin();
                    config.guiLogin = g;
                }
                if (parts.length < 2) {
                    return "图形登录自动化：" + (g.enabled ? "开" : "关")
                            + "，标题关键词=" + g.titleKeyword
                            + "，点第 " + g.clickSlot + " 格，聊天密码=" + (g.chatText.isBlank() ? "（空）" : "已设置")
                            + "，延迟 " + g.delaySeconds + " 秒。\n用法：gui on | gui off | gui dump | gui set <关键词> <格子> <密码> | gui title <关键词> | gui slot <格子> | gui pass <密码>";
                }
                if (parts[1].equalsIgnoreCase("dump")) {
                    return GuiLogin.dump(net.minecraft.client.Minecraft.getInstance());
                }
                if (parts[1].equalsIgnoreCase("on")) {
                    g.enabled = true;
                    config.save();
                    GuiLogin.reset();
                    return "已开启图形登录自动化。";
                }
                if (parts[1].equalsIgnoreCase("off")) {
                    g.enabled = false;
                    config.save();
                    return "已关闭图形登录自动化。";
                }
                if (parts[1].equalsIgnoreCase("title")) {
                    String kw = line.substring(line.toLowerCase().indexOf("title") + 5).trim();
                    g.titleKeyword = kw;
                    config.save();
                    GuiLogin.reset();
                    return "标题关键词改为：" + kw;
                }
                if (parts[1].equalsIgnoreCase("kw")) {
                    String kw = line.substring(line.toLowerCase().indexOf("kw") + 2).trim();
                    if (kw.isBlank()) {
                        return "用法：gui kw 登录,进入,确认";
                    }
                    g.slotKeyword = kw;
                    config.save();
                    GuiLogin.reset();
                    return "自动挑格子的关键词改为：" + kw;
                }
                if (parts[1].equalsIgnoreCase("slot")) {
                    try {
                        g.clickSlot = Integer.parseInt(parts[2]);
                    } catch (Exception e) {
                        return "用法：gui slot <格子号>";
                    }
                    config.save();
                    GuiLogin.reset();
                    return "改成点第 " + g.clickSlot + " 格。";
                }
                if (parts[1].equalsIgnoreCase("pass")) {
                    String pw = line.substring(line.toLowerCase().indexOf("pass") + 4).trim();
                    g.chatText = pw;
                    config.save();
                    GuiLogin.reset();
                    return "聊天密码已设置（不显示内容）。";
                }
                if (parts[1].equalsIgnoreCase("set")) {
                    if (parts.length < 4) {
                        return "用法：gui set <标题关键词> <格子号> <密码>";
                    }
                    int slotIdx = -1;
                    try {
                        slotIdx = Integer.parseInt(parts[2]);
                    } catch (Exception e) {
                        return "格子号要是整数，先用 gui dump 看。";
                    }
                    g.titleKeyword = parts[1];
                    g.clickSlot = slotIdx;
                    String rest = line.substring(line.indexOf(parts[3])).trim();
                    g.chatText = rest;
                    g.enabled = true;
                    config.save();
                    GuiLogin.reset();
                    return "已设置：菜单含“" + parts[1] + "”时点第 " + slotIdx + " 格，然后发密码（内容不显示）。下次进服生效。";
                }
                return "看不懂的参数，用 gui 看用法。";
            }
            case "join": {
                if (parts.length < 2) {
                    return "用法：join host:port（比如 join 127.0.0.1:25565）";
                }
                String addr = line.substring(line.toLowerCase().indexOf("join") + 4).trim();
                boolean ok = AutoJoin.connect(net.minecraft.client.Minecraft.getInstance(), addr);
                return ok ? "正在连接 " + addr + " ..." : "连接失败，地址格式或状态不对：" + addr;
            }
            case "autologin": {
                if (parts.length < 2) {
                    String cur = (config.autoJoinServer == null || config.autoJoinServer.isBlank())
                            ? "（未设置）" : config.autoJoinServer;
                    return "自动进服：" + cur + "，延迟 " + config.autoJoinDelaySeconds
                            + " 秒。用法：autologin host:port / autologin off / autologin delay <秒>";
                }
                if (parts[1].equalsIgnoreCase("delay")) {
                    try {
                        config.autoJoinDelaySeconds = Math.max(0, Math.min(60, Integer.parseInt(parts[2])));
                    } catch (Exception e) {
                        return "用法：autologin delay <秒>";
                    }
                    config.save();
                    AutoJoin.reset();
                    return "自动进服延迟改为 " + config.autoJoinDelaySeconds + " 秒。";
                }
                if (parts[1].equalsIgnoreCase("off")) {
                    config.autoJoinServer = "";
                    config.save();
                    return "已关闭进游戏自动连接。";
                }
                String addr = line.substring(line.toLowerCase().indexOf("autologin") + 9).trim();
                if (addr.indexOf(':') < 0) {
                    addr = addr + ":25565";
                }
                config.autoJoinServer = addr;
                config.save();
                AutoJoin.reset();
                return "已设置自动连接：" + addr + "（下次开游戏生效，也可以现在 join " + addr + "）";
            }
            case "reload":
                AutoMiner.reloadConfig();
                AutoJoin.reset();
                return "配置已从 autominer.json 重载。";
            case "get":
                if (parts.length < 2) {
                    return "用法：get <物品> [数量]";
                }
                int count = 1;
                if (parts.length >= 3) {
                    try {
                        count = Integer.parseInt(parts[2]);
                    } catch (NumberFormatException e) {
                        return "数量要是整数。";
                    }
                }
                controller.startGet(normalize(parts[1]), Math.max(1, Math.min(3456, count)));
                return "已下发：get " + normalize(parts[1]) + " ×" + count;
            case "interval": {
                if (parts.length < 2) {
                    return "用法：interval <秒>";
                }
                try {
                    config.intervalSeconds = Math.max(0, Math.min(86400, Integer.parseInt(parts[1])));
                } catch (NumberFormatException e) {
                    return "秒数要是整数。";
                }
                config.save();
                return config.intervalSeconds == 0 ? "定时回箱已关闭。" : "每 " + config.intervalSeconds + " 秒回箱一次。";
            }
            case "trigger": {
                if (parts.length < 2 || parts[1].equalsIgnoreCase("off")) {
                    config.depositTriggerItem = "";
                    config.save();
                    return "攒够回箱已关闭。";
                }
                if (parts.length < 3) {
                    return "用法：trigger <物品> <数量> 或 trigger off";
                }
                try {
                    config.depositTriggerCount = Math.max(1, Math.min(3456, Integer.parseInt(parts[2])));
                } catch (NumberFormatException e) {
                    return "数量要是整数。";
                }
                config.depositTriggerItem = normalize(parts[1]);
                config.save();
                return "攒够 " + config.depositTriggerCount + " 个 " + config.depositTriggerItem + " 就回箱。";
            }
            case "depositall":
                config.depositAll = !config.depositAll;
                config.save();
                return config.depositAll ? "全部入库：开。" : "全部入库：关。";
            case "ores":
                return listCmd(config.mineBlocks, parts, true);
            case "items":
                return listCmd(config.depositItems, parts, false);
            case "chest":
                return chestCmd(config, parts);
            case "range": {
                if (parts.length < 2) {
                    return "用法：range <1-6>";
                }
                try {
                    config.returnRange = Math.max(1, Math.min(6, Integer.parseInt(parts[1])));
                } catch (NumberFormatException e) {
                    return "范围要是整数。";
                }
                config.save();
                return "到箱子判定半径：" + config.returnRange + " 格。";
            }
            default:
                return "不认识的指令：" + cmd + "（输 help 看列表）";
        }
    }

    private static String status() {
        MinerConfig config = AutoMiner.config();
        MinerController controller = AutoMiner.controller();
        StringBuilder sb = new StringBuilder();
        sb.append("状态：").append(controller.state().label());
        if (controller.getJob() != null) {
            sb.append("（get 任务进行中）");
        }
        sb.append("｜开关：").append(config.enabled ? "开" : "关");
        sb.append("｜矿石：").append(config.mineBlocks.size()).append(" 种");
        sb.append("｜箱子：").append(config.chests.size()).append(" 个");
        sb.append("｜回箱：").append(controller.depositTriggerLabel());
        return sb.toString();
    }

    private static String listCmd(List<String> list, String[] parts, boolean block) {
        MinerConfig config = AutoMiner.config();
        if (parts.length < 2 || parts[1].equalsIgnoreCase("list")) {
            return (block ? "挖矿清单：" : "存矿清单：") + (list.isEmpty() ? "（空）" : String.join(", ", list));
        }
        switch (parts[1].toLowerCase()) {
            case "default": {
                list.clear();
                list.addAll(block ? MinerConfig.DEFAULT_ORES : MinerConfig.DEFAULT_DROPS);
                config.save();
                return "已恢复默认，共 " + list.size() + " 项。";
            }
            case "add":
            case "remove": {
                if (parts.length < 3) {
                    return "用法：" + (block ? "ores" : "items") + " " + parts[1] + " <id>";
                }
                String id = normalize(parts[2]);
                var registry = block ? BuiltInRegistries.BLOCK : BuiltInRegistries.ITEM;
                Identifier identifier = Identifier.tryParse(id);
                if (identifier == null || !registry.containsKey(identifier)) {
                    return "游戏里没有这个" + (block ? "方块" : "物品") + "：" + id;
                }
                boolean add = parts[1].equalsIgnoreCase("add");
                if (add) {
                    if (list.contains(id)) {
                        return id + " 已经在清单里。";
                    }
                    list.add(id);
                } else {
                    if (!list.contains(id)) {
                        return id + " 不在清单里。";
                    }
                    list.remove(id);
                }
                config.save();
                AutoMiner.controller().refreshMiningTargets();
                return (add ? "已添加 " : "已移除 ") + id + "。";
            }
            default:
                return "用法：" + (block ? "ores" : "items") + " list|add|remove|default";
        }
    }

    private static String chestCmd(MinerConfig config, String[] parts) {
        if (parts.length < 2 || parts[1].equalsIgnoreCase("list")) {
            if (config.chests.isEmpty()) {
                return "还没有箱子。";
            }
            StringBuilder sb = new StringBuilder("共 " + config.chests.size() + " 个箱子：");
            for (MinerConfig.ChestEntry entry : config.chests) {
                sb.append("\n- ").append(entry.describe());
            }
            return sb.toString();
        }
        switch (parts[1].toLowerCase()) {
            case "clear":
                config.chests.clear();
                config.save();
                return "已清空箱子列表。";
            case "remove": {
                if (parts.length < 3) {
                    return "用法：chest remove <名字>";
                }
                String name = parts[2];
                MinerConfig.ChestEntry entry = config.chestByName(name);
                if (entry == null) {
                    return "没有叫“" + name + "”的箱子。";
                }
                config.chests.remove(entry);
                config.save();
                return "已移除箱子“" + name + "”。";
            }
            case "add":
                return "远程不能记箱子（要用准星），进游戏按 C 或对准箱子执行 /am chest add。";
            default:
                return "用法：chest list|remove <名字>|clear";
        }
    }

    private static String normalize(String raw) {
        String id = raw.trim().toLowerCase();
        if (!id.contains(":")) {
            id = "minecraft:" + id;
        }
        return id;
    }
}
