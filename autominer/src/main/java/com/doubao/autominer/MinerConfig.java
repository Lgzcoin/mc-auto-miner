package com.doubao.autominer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 自动采矿助手的配置文件（config/autominer.json）。
 */
public class MinerConfig {
    /** 默认要挖的矿石方块（给 Baritone 的 mine 进程用）。 */
    public static final List<String> DEFAULT_ORES = List.of(
            "minecraft:coal_ore", "minecraft:deepslate_coal_ore",
            "minecraft:iron_ore", "minecraft:deepslate_iron_ore",
            "minecraft:copper_ore", "minecraft:deepslate_copper_ore",
            "minecraft:gold_ore", "minecraft:deepslate_gold_ore", "minecraft:nether_gold_ore",
            "minecraft:redstone_ore", "minecraft:deepslate_redstone_ore",
            "minecraft:lapis_ore", "minecraft:deepslate_lapis_ore",
            "minecraft:diamond_ore", "minecraft:deepslate_diamond_ore",
            "minecraft:emerald_ore", "minecraft:deepslate_emerald_ore",
            "minecraft:nether_quartz_ore", "minecraft:ancient_debris"
    );

    /** 默认需要送进箱子的矿物掉落物。 */
    public static final List<String> DEFAULT_DROPS = List.of(
            "minecraft:coal", "minecraft:raw_iron", "minecraft:raw_copper", "minecraft:raw_gold",
            "minecraft:redstone", "minecraft:lapis_lazuli", "minecraft:diamond", "minecraft:emerald",
            "minecraft:quartz", "minecraft:amethyst_shard", "minecraft:gold_nugget",
            "minecraft:ancient_debris", "minecraft:netherite_scrap"
    );

    /** depositAll 模式下始终留在身上的物品。 */
    public static final List<String> DEFAULT_KEEP = List.of(
            "minecraft:torch", "minecraft:water_bucket", "minecraft:bread", "minecraft:cooked_beef"
    );

    public boolean enabled = false;

    /** 存矿目标箱子列表。 */
    public List<ChestEntry> chests = new ArrayList<>();

    /** 进服后自动发送的认证指令（多条用 | 分隔，例如 register 密码 密码|login 密码）；空 = 不发送。 */
    public String autoAuthCommands = "";

    /** 进服后等几秒再发认证指令（给服务器一点加载时间）。 */
    public int autoAuthDelaySeconds = 5;

    /** 自动进服：服务器地址 host:port，空 = 不自动连（多开挂机时用）。 */
    public String autoJoinServer = "";

    /** 标题界面出现后等几秒再自动连（给服务器和模组加载留时间）。 */
    public int autoJoinDelaySeconds = 3;

    /** 连不上（服务器满/被踢）时隔几秒重试一次。 */
    public int autoJoinRetrySeconds = 10;

    /** 最多重试几次（含第一次）。 */
    public int autoJoinMaxAttempts = 30;

    /** 图形登录（第三方菜单）自动化配置。 */
    public GuiLogin guiLogin = new GuiLogin();

    /** 进入世界后自动开始挖矿（多开挂机时用）。 */
    public boolean autoStartOnJoin = true;

    /** 进服并登录后自动发送的指令（例如 home、tpa 队友、kit），按顺序发；空 = 不发。 */
    public List<String> onJoinCommands = new ArrayList<>();

    /** 开荒：开挖前先走到这个点（基地/矿区），格式 [x, y, z]；空 = 原地开挖。 */
    public List<Integer> startPoint = new ArrayList<>();

    /** 认为“已经走到开荒点”的判定半径。 */
    public int startPointRange = 3;

    /** 定时回箱间隔（秒），0 = 关闭定时。 */
    public int intervalSeconds = 900;

    /** 生命周期事件挂的指令/脚本：开始、回箱前、爆仓、镐快坏、定时、回箱后、挖完、停止。 */
    public List<Hook> hooks = new ArrayList<>();

    /** 兼容 1.13 旧配置：回箱前是否执行自定义指令/脚本。 */
    public boolean preDepositEnabled = true;
    /** 回箱前按顺序执行的游戏指令（发给服务器，不需要写开头的 /）。 */
    public List<String> preDepositCommands = new ArrayList<>();
    /** 回箱前脚本文件名，放 config 目录；一行一条指令，也可用 | 分隔；空 = 不读。 */
    public String preDepositScript = "pre_deposit.txt";
    /** 每条回箱前指令之间等几 tick（20 tick = 1 秒）。 */
    public int preDepositCommandDelayTicks = 10;
    /** 最后一条指令执行完，再等几 tick 才走向箱子。 */
    public int preDepositSettleTicks = 20;

    /** 回箱触发用的“指定矿物”：物品 id，空字符串 = 关闭。 */
    public String depositTriggerItem = "minecraft:diamond";

    /** 指定矿物在背包里攒到这么多个就回箱一次。 */
    public int depositTriggerCount = 64;

    /** 认为“已经到箱子旁边”的判定半径。 */
    public int returnRange = 2;

    /** true：除工具装备与 keepItems 外，所有东西都存进箱子。 */
    public boolean depositAll = false;

    /** 自动回收：镐子快坏了 / 背包快满时回箱，顺手从箱子里拿备用镐。 */
    public Recycle recycle = new Recycle();

    /** 一个生命周期事件对应的指令/脚本。 */
    public static class Hook {
        /** HookEvent.key */
        public String event = "";
        public boolean enabled = true;
        public List<String> commands = new ArrayList<>();
        /** 放 config 目录的脚本文件名；一行一条，空 = 不读。 */
        public String script = "";
        public int commandDelayTicks = 10;
        public int settleTicks = 20;

        public Hook() {
        }

        public Hook(String event) {
            this.event = event;
        }
    }

    public Hook hook(HookEvent event) {
        if (event == null || hooks == null) {
            return null;
        }
        for (Hook hook : hooks) {
            if (hook != null && event.key.equals(hook.event)) {
                return hook;
            }
        }
        return null;
    }

    public Hook hookOrCreate(HookEvent event) {
        if (hooks == null) {
            hooks = new ArrayList<>();
        }
        Hook hook = hook(event);
        if (hook == null) {
            hook = new Hook(event.key);
            hooks.add(hook);
        }
        return hook;
    }

    public static class Recycle {
        /** 总开关。 */
        public boolean enabled = true;
        /** 镐子剩余耐久低于这个百分比就回箱换镐（0 = 关）。 */
        public int pickDurabilityPercent = 15;
        /** 背包空位少于等于这个数就回箱（0 = 关）。 */
        public int minFreeSlots = 1;
        /** 从箱子里拿的工具物品 id，空 = 自动找名称里带 pickaxe 的。 */
        public String pickItemId = "";
        /** 一次从箱子拿几把镐（一般是 1，拿多了占背包）。 */
        public int takeCount = 1;
        /** 箱子里没有备用镐时，是否还在身上继续挖（true = 继续挖并提醒）。 */
        public boolean continueWithoutSpare = true;
    }

    public List<String> mineBlocks = new ArrayList<>(DEFAULT_ORES);
    public List<String> depositItems = new ArrayList<>(DEFAULT_DROPS);
    public List<String> keepItems = new ArrayList<>(DEFAULT_KEEP);

    public static class GuiLogin {
        /** 打开这个开关才会自动点菜单。 */
        public boolean enabled = false;
        /** 菜单标题里要包含的文字，空 = 任何菜单都算。 */
        public String titleKeyword = "登录";
        /** 点第几格（用 gui dump 看编号），-1 = 自动按 slotKeyword 挑。 */
        public int clickSlot = -1;
        /** clickSlot = -1 时，按格子里物品的名字自动挑（逗号分隔，中文英文都行）。 */
        public String slotKeyword = "登录,登入,登陆,进入,确认,密码,Login";
        /** 最多点几次（点出下一层菜单会继续点）。 */
        public int maxTries = 6;
        /** 点完之后要发的聊天内容（很多菜单点了会要求聊天输密码）。 */
        public String chatText = "";
        /** 菜单弹出后等几秒再点。 */
        public int delaySeconds = 2;
    }

    /** 最后一次读/写这个文件时它的修改时间，用来做热重载判断。 */
    private static long lastModified = -1L;

    public static long lastModified() {
        return lastModified;
    }

    public static void touchLastModified() {
        try {
            if (Files.exists(configPath())) {
                lastModified = Files.getLastModifiedTime(configPath()).toMillis();
            }
        } catch (Exception ignored) {
        }
    }

    public static class ChestEntry {
        public String name = "箱子";
        public int x;
        public int y;
        public int z;

        public ChestEntry() {
        }

        public ChestEntry(String name, BlockPos pos) {
            this.name = name;
            this.x = pos.getX();
            this.y = pos.getY();
            this.z = pos.getZ();
        }

        public BlockPos pos() {
            return new BlockPos(x, y, z);
        }

        public String describe() {
            return name + " (" + x + ", " + y + ", " + z + ")";
        }
    }

    public static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("autominer.json");
    }

    public static MinerConfig load() {
        Path path = configPath();
        MinerConfig config = new MinerConfig();
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path)) {
                Gson gson = new Gson();
                MinerConfig loaded = gson.fromJson(reader, MinerConfig.class);
                if (loaded != null) {
                    config = loaded;
                }
            } catch (Exception e) {
                AutoMiner.LOGGER.warn("读取 autominer.json 失败，使用默认配置", e);
                config = new MinerConfig();
            }
        }
        config.normalize();
        config.save();
        touchLastModified();
        return config;
    }

    /** 修补手改配置里可能缺失的字段。 */
    public void normalize() {
        if (chests == null) chests = new ArrayList<>();
        if (mineBlocks == null) mineBlocks = new ArrayList<>(DEFAULT_ORES);
        if (depositItems == null) depositItems = new ArrayList<>(DEFAULT_DROPS);
        if (keepItems == null) keepItems = new ArrayList<>(DEFAULT_KEEP);
        if (autoAuthCommands == null) autoAuthCommands = "";
        if (onJoinCommands == null) onJoinCommands = new ArrayList<>();
        if (startPoint == null) startPoint = new ArrayList<>();
        if (startPoint.size() != 3) startPoint = new ArrayList<>();
        if (startPointRange < 1) startPointRange = 1;
        if (startPointRange > 10) startPointRange = 10;
        if (autoAuthDelaySeconds < 0) autoAuthDelaySeconds = 0;
        if (autoAuthDelaySeconds > 120) autoAuthDelaySeconds = 120;
        if (guiLogin == null) guiLogin = new GuiLogin();
        if (guiLogin.titleKeyword == null) guiLogin.titleKeyword = "登录";
        if (guiLogin.chatText == null) guiLogin.chatText = "";
        if (guiLogin.clickSlot < -1) guiLogin.clickSlot = -1;
        if (guiLogin.slotKeyword == null) guiLogin.slotKeyword = "登录,登入,登陆,进入,确认,密码,Login";
        if (guiLogin.maxTries < 1) guiLogin.maxTries = 1;
        if (guiLogin.maxTries > 20) guiLogin.maxTries = 20;
        if (guiLogin.delaySeconds < 0) guiLogin.delaySeconds = 0;
        if (guiLogin.delaySeconds > 30) guiLogin.delaySeconds = 30;
        if (depositTriggerItem == null) depositTriggerItem = "";
        if (autoJoinServer == null) autoJoinServer = "";
        if (autoJoinDelaySeconds < 0) autoJoinDelaySeconds = 0;
        if (autoJoinDelaySeconds > 60) autoJoinDelaySeconds = 60;
        if (autoJoinRetrySeconds < 3) autoJoinRetrySeconds = 3;
        if (autoJoinRetrySeconds > 600) autoJoinRetrySeconds = 600;
        if (autoJoinMaxAttempts < 1) autoJoinMaxAttempts = 1;
        if (autoJoinMaxAttempts > 200) autoJoinMaxAttempts = 200;
        if (depositTriggerCount < 1) depositTriggerCount = 1;
        if (depositTriggerCount > 3456) depositTriggerCount = 3456;
        if (intervalSeconds < 0) intervalSeconds = 0;
        if (intervalSeconds > 86400) intervalSeconds = 86400;
        if (preDepositCommands == null) preDepositCommands = new ArrayList<>();
        if (preDepositScript == null) preDepositScript = "";
        if (preDepositCommandDelayTicks < 0) preDepositCommandDelayTicks = 0;
        if (preDepositCommandDelayTicks > 200) preDepositCommandDelayTicks = 200;
        if (preDepositSettleTicks < 0) preDepositSettleTicks = 0;
        if (preDepositSettleTicks > 600) preDepositSettleTicks = 600;

        boolean hadHooks = hooks != null && !hooks.isEmpty();
        if (hooks == null) hooks = new ArrayList<>();
        for (HookEvent event : HookEvent.values()) {
            Hook hook = null;
            for (Hook candidate : hooks) {
                if (event.key.equals(candidate.event)) {
                    hook = candidate;
                    break;
                }
            }
            if (hook == null) {
                hook = new Hook(event.key);
                hooks.add(hook);
            }
            if (hook.commands == null) hook.commands = new ArrayList<>();
            if (hook.script == null) hook.script = "";
            if (hook.commandDelayTicks < 0) hook.commandDelayTicks = 0;
            if (hook.commandDelayTicks > 200) hook.commandDelayTicks = 200;
            if (hook.settleTicks < 0) hook.settleTicks = 0;
            if (hook.settleTicks > 600) hook.settleTicks = 600;
        }
        // 1.13 的旧字段迁移到 PRE_DEPOSIT；已有 hooks 的配置不再覆盖。
        if (!hadHooks) {
            Hook pre = hook(HookEvent.PRE_DEPOSIT);
            if (pre != null) {
                pre.enabled = preDepositEnabled;
                pre.commands.clear();
                pre.commands.addAll(preDepositCommands);
                pre.script = preDepositScript;
                pre.commandDelayTicks = preDepositCommandDelayTicks;
                pre.settleTicks = preDepositSettleTicks;
            }
        }
        if (returnRange < 1) returnRange = 1;
        if (returnRange > 6) returnRange = 6;
        if (recycle == null) recycle = new Recycle();
        if (recycle.pickDurabilityPercent < 0) recycle.pickDurabilityPercent = 0;
        if (recycle.pickDurabilityPercent > 90) recycle.pickDurabilityPercent = 90;
        if (recycle.minFreeSlots < 0) recycle.minFreeSlots = 0;
        if (recycle.minFreeSlots > 20) recycle.minFreeSlots = 20;
        if (recycle.takeCount < 1) recycle.takeCount = 1;
        if (recycle.takeCount > 9) recycle.takeCount = 9;
        if (recycle.pickItemId == null) recycle.pickItemId = "";
    }

    public void save() {
        Path path = configPath();
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                new GsonBuilder().setPrettyPrinting().create().toJson(this, writer);
            }
            lastModified = Files.getLastModifiedTime(path).toMillis();
        } catch (Exception e) {
            AutoMiner.LOGGER.warn("保存 autominer.json 失败", e);
        }
    }

    public ChestEntry chestByName(String name) {
        for (ChestEntry entry : chests) {
            if (entry.name.equalsIgnoreCase(name)) {
                return entry;
            }
        }
        return null;
    }
}
