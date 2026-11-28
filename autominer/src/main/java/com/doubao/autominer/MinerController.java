package com.doubao.autominer;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 主循环：挖矿 → 到点/背包满 → 走到箱子 → 开箱存矿 → 回去继续挖。
 */
public final class MinerController {
    public enum State {
        IDLE("空闲"),
        GOTO("前往基地"),
        MINING("挖矿中"),
        SCRIPT("执行事件脚本"),
        WALKING("前往箱子"),
        OPENING("开箱子"),
        DEPOSITING("存放矿物"),
        COOLDOWN("收尾");

        private final String label;

        State(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private static final String PREFIX = "§b[自动采矿]§r ";

    private final MinerConfig config;

    private State state = State.IDLE;
    private int stateTicks = 0;
    private int ticksSinceDeposit = 0;
    private int chestIndex = 0;
    private int walkRetries = 0;
    private int emptyPasses = 0;
    private int mineIdleTicks = 0;
    private int mineRestarts = 0;
    private BlockPos targetChest = null;
    private int startPointTries = 0;
    private GetJob getJob = null;
    private String pendingDepositReason = "";
    private final List<String> scriptQueue = new ArrayList<>();
    private int scriptIndex = 0;
    private int scriptCooldown = 0;
    private int scriptDoneTicks = 0;
    private int scriptBudget = 0;
    private int scriptSettleTicks = 20;
    private int scriptCommandDelayTicks = 10;
    private Runnable scriptAfter = null;
    private String scriptLabel = "";
    private String scriptMacro = "";
    private boolean scriptMacroPending = false;
    private boolean scriptMacroRunning = false;
    private boolean startHookDone = false;
    private boolean postDepositDone = true;
    private boolean stopHookRunning = false;

    /** 这次回箱是不是为了换镐；箱子里的备用镐试拿过一次没有。 */
    private boolean wantSparePick = false;
    private boolean sparePickTried = false;
    private int noPickWarnTicks = -1;

    /** 给状态面板用：现在在干什么。 */
    public State stateNow() {
        return state;
    }
    private boolean autoStarted = false;

    public MinerController(MinerConfig config) {
        this.config = config;
    }

    public MinerConfig config() {
        return config;
    }

    public State state() {
        return state;
    }

    /** 汉化过的回箱触发描述，比如“每 900 秒 / 钻石 12/64”。 */
    public String depositTriggerLabel() {
        StringBuilder sb = new StringBuilder();
        if (config.intervalSeconds > 0) {
            sb.append("每 ").append(config.intervalSeconds).append(" 秒");
        }
        if (config.depositTriggerItem != null && !config.depositTriggerItem.isEmpty()) {
            LocalPlayer player = Minecraft.getInstance().player;
            int have = player == null ? 0 : countItem(player, config.depositTriggerItem);
            if (!sb.isEmpty()) {
                sb.append(" 或 ");
            }
            sb.append(shortName(config.depositTriggerItem)).append(" ").append(have)
                    .append("/").append(config.depositTriggerCount);
        }
        return sb.isEmpty() ? "关闭" : sb.toString();
    }

    /** 指定矿物当前数量。 */
    public int triggerItemCount(LocalPlayer player) {
        if (config.depositTriggerItem == null || config.depositTriggerItem.isEmpty()) {
            return -1;
        }
        return countItem(player, config.depositTriggerItem);
    }

    public static int countItem(LocalPlayer player, String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return 0;
        }
        net.minecraft.resources.Identifier id = net.minecraft.resources.Identifier.tryParse(itemId);
        if (id == null) {
            return 0;
        }
        int total = 0;
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(id)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** 身上最好那把镐还剩百分之几耐久；没有镐返回 -1。 */
    public static int pickPercent(LocalPlayer player) {
        if (player == null) {
            return -1;
        }
        int best = -1;
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || !stack.isDamageableItem()) {
                continue;
            }
            String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
            if (!path.contains("pickaxe")) {
                continue;
            }
            int max = stack.getMaxDamage();
            if (max <= 0) {
                continue;
            }
            int left = 100 - (int) Math.round(100.0 * stack.getDamageValue() / max);
            if (left > best) {
                best = left;
            }
        }
        return best;
    }

    /** 背包（0-35 格）还剩几个空位。 */
    public static int freeSlots(LocalPlayer player) {
        if (player == null) {
            return -1;
        }
        int free = 0;
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < 36; slot++) {
            if (inventory.getItem(slot).isEmpty()) {
                free++;
            }
        }
        return free;
    }

    public void start() {
        config.enabled = true;
        config.save();
        state = State.IDLE;
        stateTicks = 0;
        ticksSinceDeposit = 0;
        mineIdleTicks = 0;
        mineRestarts = 0;
        startHookDone = false;
        postDepositDone = true;
        stopHookRunning = false;
        clearScript();
        if (!BaritoneHook.available()) {
            switch (BaritoneHook.status()) {
                case NO_MOD -> message("没找到男中音的模组。mods 里要放 baritone-api-fabric-1.19.0.jar"
                        + "（26.2 版），并且 Fabric Loader 要 ≥ 0.19.3。");
                case NO_API -> message("找到男中音了，但你装的是精简版 standalone 包，它没有 baritone.api，"
                        + "换 baritone-api-fabric-1.19.0.jar 就好。详细诊断见 /am diag。");
                default -> message("男中音状态异常，输入 /am diag 查看诊断。");
            }
            config.enabled = false;
            config.save();
            return;
        }
        message("开始自动采矿，已启用 " + config.mineBlocks.size() + " 种矿石、"
                + config.chests.size() + " 个箱子。");
        if (config.chests.isEmpty()) {
            message("提醒：还没指定箱子，挖满后不会自动存矿。用 /am chest add <名字> 添加。");
        }
        if (!hasPickaxe(Minecraft.getInstance().player)) {
            message("提醒：背包里好像没有镐子。");
        }
    }

    /** /am get：一次性任务，先挖够原料再合成。 */
    public void startGet(String itemId, int count) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            message("还没进世界，先别急。");
            return;
        }
        if (!BaritoneHook.available()) {
            message("男中音不可用（" + BaritoneHook.status().label + "），输入 /am diag 查原因。");
            return;
        }
        finishStop(true);
        if (mc.player.getRecipeBook() == null) {
            message("读不到配方书，没法计算合成路线。");
            return;
        }
        GetPlan plan = GetPlan.build(mc.level, mc.player.getRecipeBook(), itemId, count);
        if (!plan.ok()) {
            message(plan.error);
            return;
        }
        StringBuilder summary = new StringBuilder("收到：要 " + shortName(itemId) + " ×" + count);
        if (!plan.mines.isEmpty()) {
            summary.append("；先挖 ");
            for (int i = 0; i < plan.mines.size(); i++) {
                GetPlan.MineStep step = plan.mines.get(i);
                if (i > 0) {
                    summary.append("、");
                }
                summary.append(shortName(step.itemId())).append(" ").append(step.qty()).append(" 个");
            }
        }
        if (!plan.crafts.isEmpty()) {
            summary.append("；再合成 ").append(plan.crafts.size()).append(" 步");
        }
        message(summary.toString());
        getJob = new GetJob(plan, itemId, count, this::message);
        state = State.IDLE;
    }

    public GetJob getJob() {
        return getJob;
    }

    private static String shortName(String itemId) {
        return itemId.contains(":") ? itemId.substring(itemId.indexOf(':') + 1) : itemId;
    }

    /** 挖矿清单变了：正在挖的话立刻按新清单重启 Baritone 的挖矿进程。 */
    public void refreshMiningTargets() {
        if (!config.enabled || state != State.MINING) {
            return;
        }
        BaritoneHook.cancelAll();
        BaritoneHook.startMine(config.mineBlocks);
        stateTicks = 0;
    }

    public void stop(boolean silent) {
        if (!stopHookRunning && config.enabled && state != State.SCRIPT) {
            stopHookRunning = true;
            if (runHook(HookEvent.STOP, () -> finishStop(silent))) {
                return;
            }
            stopHookRunning = false;
        }
        finishStop(silent);
    }

    private void finishStop(boolean silent) {
        stopHookRunning = false;
        if (config.enabled) {
            config.enabled = false;
            config.save();
        }
        BaritoneHook.cancelAll();
        getJob = null;
        clearScript();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && isContainerOpen(mc.player)) {
            mc.player.closeContainer();
        }
        state = State.IDLE;
        stateTicks = 0;
        targetChest = null;
        mineIdleTicks = 0;
        mineRestarts = 0;
        pendingDepositReason = "";
        postDepositDone = true;
        startHookDone = false;
        if (!silent) {
            message("已停止自动采矿。");
        }
    }

    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;

        if (player == null || level == null) {
            if (state != State.IDLE) {
                state = State.IDLE;
                targetChest = null;
            }
            autoStarted = false;
            return;
        }

        if (getJob != null) {
            if (getJob.finished()) {
                getJob = null;
            } else {
                getJob.tick(mc, player);
                return;
            }
        }

        if (!config.enabled) {
            if (state != State.IDLE) {
                stop(true);
            }
            if (config.autoStartOnJoin && !autoStarted && Onboard.done()) {
                autoStarted = true;
                message("进入世界，自动开始挖矿（可在设置页关掉“进服自动开始”）。");
                start();
            }
            return;
        }

        if (!BaritoneHook.available()) {
            message("男中音现在不可用（" + BaritoneHook.status().label + "），自动采矿已关闭。输入 /am diag 查看原因。");
            stop(true);
            return;
        }

        stateTicks++;
        ticksSinceDeposit++;

        switch (state) {
            case IDLE -> enterMining(mc, player);
            case GOTO -> tickGoto(mc, player);
            case MINING -> tickMining(mc, player, level);
            case SCRIPT -> tickScript(mc, player);
            case WALKING -> tickWalking(mc, player, level);
            case OPENING -> tickOpening(mc, player, level);
            case DEPOSITING -> tickDepositing(mc, player, level);
            case COOLDOWN -> tickCooldown(mc, player);
            default -> state = State.IDLE;
        }
    }

    // ---------------------------------------------------------------- 挖矿

    private void enterMining(Minecraft mc, LocalPlayer player) {
        if (hasStartPoint() && !nearStart(player)) {
            state = State.GOTO;
            stateTicks = 0;
            startPointTries = 0;
            if (BaritoneHook.walkTo(startPointPos(), config.startPointRange)) {
                message("先去基地/矿区（" + fmtPos(startPointPos()) + "），到了再开挖。");
            } else {
                message("寻路到开荒点失败（男中音没就绪），先原地开挖。");
                beginMining(mc);
            }
            return;
        }
        beginMining(mc);
    }

    /** 真正开挖；第一次进入时先执行“开始挖矿”事件脚本。 */
    private void beginMining(Minecraft mc) {
        if (!startHookDone) {
            startHookDone = true;
            if (runHook(HookEvent.START, () -> startMiningNow(mc))) {
                return;
            }
        }
        startMiningNow(mc);
    }

    private void startMiningNow(Minecraft mc) {
        state = State.MINING;
        stateTicks = 0;
        mineIdleTicks = 0;
        mineRestarts = 0;
        if (!BaritoneHook.startMine(config.mineBlocks)) {
            message("Baritone 启动挖矿失败，请检查男中音是否正常工作。");
            stop(true);
        }
    }

    private boolean hasStartPoint() {
        return config.startPoint != null && config.startPoint.size() == 3;
    }

    private BlockPos startPointPos() {
        return new BlockPos(config.startPoint.get(0), config.startPoint.get(1), config.startPoint.get(2));
    }

    private boolean nearStart(LocalPlayer player) {
        if (!hasStartPoint() || player == null) {
            return true;
        }
        return player.blockPosition().distSqr(startPointPos()) <= (double) config.startPointRange * config.startPointRange;
    }

    private static String fmtPos(BlockPos p) {
        return p.getX() + "," + p.getY() + "," + p.getZ();
    }

    /** 开荒寻路：走到点就开挖；走丢/被地形卡住就重发寻路。 */
    private void tickGoto(Minecraft mc, LocalPlayer player) {
        if (!hasStartPoint()) {
            enterMining(mc, player);
            return;
        }
        if (nearStart(player)) {
            BaritoneHook.cancelAll();
            message("已到开荒点，开始挖矿。");
            enterMining(mc, player);
            return;
        }
        if (!BaritoneHook.isPathing()) {
            startPointTries++;
            if (startPointTries > 60) {
                message("走到开荒点总是失败（可能被墙/水挡住），先在跟前开挖。");
                enterMining(mc, player);
                return;
            }
            BaritoneHook.walkTo(startPointPos(), config.startPointRange);
        }
    }

    private void tickMining(Minecraft mc, LocalPlayer player, ClientLevel level) {
        if (BaritoneHook.isMining()) {
            mineIdleTicks = 0;
            mineRestarts = 0;
        } else {
            // 挖矿进程自己结束了（通常是附近找不到可挖的矿），有限次重启
            mineIdleTicks++;
            if (mineIdleTicks > 20 * 10) {
                if (mineRestarts >= 3) {
                    message("附近找不到可挖的矿石，任务暂停。换个地方后 /am start 继续。");
                    if (runHook(HookEvent.MINING_DONE, () -> stop(true))) {
                        return;
                    }
                    stop(true);
                    return;
                }
                mineRestarts++;
                mineIdleTicks = 0;
                if (!BaritoneHook.startMine(config.mineBlocks)) {
                    message("Baritone 挖矿中断，停止任务。");
                    stop(true);
                    return;
                }
            }
        }

        String reason = depositReason(player);
        if (reason == null) {
            return;
        }
        wantSparePick = config.recycle.enabled && reason.startsWith("镐");
        sparePickTried = false;
        pendingDepositReason = reason;

        if (config.chests.isEmpty()) {
            message("要回箱了（" + reason + "），但还没配置箱子。用 /am chest add <名字> 对着箱子添加。");
            config.enabled = false;
            config.save();
            BaritoneHook.cancelAll();
            state = State.IDLE;
            return;
        }

        beginDeposit(reason);
    }

    /** 回箱触发：先跑本次原因对应的脚本，再跑“回箱前”脚本，最后走向箱子。 */
    private void beginDeposit(String reason) {
        Runnable walk = this::startWalkToChest;
        Runnable pre = () -> runHookOr(HookEvent.PRE_DEPOSIT, walk);
        HookEvent reasonEvent = reasonEvent(reason);
        if (reasonEvent != null) {
            runHookOr(reasonEvent, pre);
        } else {
            pre.run();
        }
    }

    private HookEvent reasonEvent(String reason) {
        if (reason == null) {
            return null;
        }
        if (reason.startsWith("背包")) {
            return HookEvent.FULL;
        }
        if (reason.startsWith("镐")) {
            return HookEvent.LOW_PICK;
        }
        if (reason.startsWith("定时")) {
            return HookEvent.INTERVAL;
        }
        if (reason.startsWith("攒够")) {
            return HookEvent.THRESHOLD;
        }
        return null;
    }

    private void runHookOr(HookEvent event, Runnable after) {
        if (!runHook(event, after)) {
            after.run();
        }
    }

    /** 把事件挂的宏/指令/脚本放进队列；没有内容返回 false。 */
    private boolean runHook(HookEvent event, Runnable after) {
        MinerConfig.Hook hook = config.hook(event);
        if (hook == null || !hook.enabled) {
            return false;
        }
        List<String> queue = buildScriptQueue(hook);
        String macro = hook.macro == null ? "" : hook.macro.trim();
        if (queue.isEmpty() && macro.isEmpty()) {
            return false;
        }
        clearScript();
        scriptQueue.addAll(queue);
        scriptAfter = after;
        scriptLabel = event.label;
        scriptIndex = 0;
        scriptCooldown = 0;
        scriptDoneTicks = 0;
        scriptSettleTicks = hook.settleTicks;
        scriptCommandDelayTicks = hook.commandDelayTicks;
        scriptMacro = macro;
        scriptMacroPending = !macro.isEmpty();
        scriptMacroRunning = false;
        scriptBudget = 200 + queue.size() * (hook.commandDelayTicks + 5) + hook.settleTicks;
        state = State.SCRIPT;
        stateTicks = 0;
        BaritoneHook.cancelAll();
        if (!macro.isEmpty() && queue.isEmpty()) {
            message("执行[" + event.label + "]：先跑宏「" + macro + "」。");
        } else if (!macro.isEmpty()) {
            message("执行[" + event.label + "]：先跑宏「" + macro + "」，再发 " + queue.size() + " 条指令。");
        } else {
            message("执行[" + event.label + "]：先发 " + queue.size() + " 条指令。");
        }
        return true;
    }

    private List<String> buildScriptQueue(MinerConfig.Hook hook) {
        List<String> queue = new ArrayList<>();
        if (hook.commands != null) {
            for (String raw : hook.commands) {
                addCommands(queue, raw);
            }
        }
        if (hook.script != null && !hook.script.isBlank()) {
            try {
                Path script = FabricLoader.getInstance().getConfigDir().resolve(hook.script.trim());
                if (Files.isRegularFile(script) && Files.size(script) <= 64 * 1024) {
                    for (String line : Files.readAllLines(script, StandardCharsets.UTF_8)) {
                        addCommands(queue, line);
                    }
                }
            } catch (Throwable t) {
                message("读脚本失败（" + hook.script + "）：" + t.getClass().getSimpleName());
            }
        }
        return queue;
    }

    private void addCommands(List<String> out, String raw) {
        if (raw == null) {
            return;
        }
        String line = raw.replace("\uFEFF", "").trim();
        if (line.isEmpty() || line.startsWith("#")) {
            return;
        }
        for (String part : line.split("\\|")) {
            String cmd = part.trim();
            if (cmd.startsWith("/")) {
                cmd = cmd.substring(1);
            }
            if (!cmd.isEmpty() && cmd.length() <= 240 && out.size() < 50) {
                out.add(cmd);
            }
        }
    }

    private void tickScript(Minecraft mc, LocalPlayer player) {
        if (scriptMacroPending) {
            scriptMacroPending = false;
            if (MacroManager.startPlay(scriptMacro, true)) {
                scriptMacroRunning = true;
                message("[" + scriptLabel + "] 开始执行宏「" + scriptMacro + "」。");
                return;
            }
            message("[" + scriptLabel + "] 宏「" + scriptMacro + "」启动失败，继续发指令。");
        }
        if (scriptMacroRunning) {
            if (MacroManager.isPlaying()) {
                return;
            }
            scriptMacroRunning = false;
            stateTicks = 0;
            message("[" + scriptLabel + "] 宏执行结束。");
        }
        if (stateTicks > scriptBudget) {
            finishScript("脚本等待超时");
            return;
        }
        if (scriptCooldown > 0) {
            scriptCooldown--;
            return;
        }
        if (scriptIndex >= scriptQueue.size()) {
            scriptDoneTicks++;
            if (scriptDoneTicks >= Math.max(1, scriptSettleTicks)) {
                finishScript(null);
            }
            return;
        }
        String cmd = scriptQueue.get(scriptIndex++);
        try {
            if (player.connection != null) {
                player.connection.sendCommand(cmd);
                message("[" + scriptLabel + "] " + scriptIndex + "/" + scriptQueue.size() + " 已发送");
            }
        } catch (Throwable t) {
            message("[" + scriptLabel + "] 指令发送失败：" + t.getClass().getSimpleName());
        }
        scriptCooldown = Math.max(0, scriptCommandDelayTicks);
        scriptDoneTicks = 0;
    }

    private void finishScript(String note) {
        Runnable after = scriptAfter;
        String label = scriptLabel;
        clearScript();
        state = State.IDLE;
        stateTicks = 0;
        if (note != null) {
            message("[" + label + "] " + note + "，继续下一步。");
        }
        if (after != null) {
            after.run();
        }
    }

    private void clearScript() {
        if (scriptMacroRunning) {
            MacroManager.stopPlayback();
        }
        scriptQueue.clear();
        scriptIndex = 0;
        scriptCooldown = 0;
        scriptDoneTicks = 0;
        scriptBudget = 0;
        scriptAfter = null;
        scriptLabel = "";
        scriptMacro = "";
        scriptMacroPending = false;
        scriptMacroRunning = false;
        scriptSettleTicks = 20;
        scriptCommandDelayTicks = 10;
    }

    private void startWalkToChest() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            state = State.IDLE;
            return;
        }
        MinerConfig.ChestEntry entry = pickChest(level);
        if (entry == null) {
            message("所有箱子都满了，暂停挖矿。清空箱子后再 /am start。");
            config.enabled = false;
            config.save();
            BaritoneHook.cancelAll();
            state = State.IDLE;
            return;
        }
        targetChest = entry.pos();
        ticksSinceDeposit = 0;
        walkRetries = 0;
        postDepositDone = false;
        state = State.WALKING;
        stateTicks = 0;
        BaritoneHook.cancelAll();
        BaritoneHook.walkTo(targetChest, config.returnRange);
        message("准备回箱存矿（" + pendingDepositReason + "）：" + entry.describe());
        pendingDepositReason = "";
    }

    /** 为什么该回箱了；不该回就返回 null。 */
    private String depositReason(LocalPlayer player) {
        if (config.intervalSeconds > 0 && ticksSinceDeposit >= config.intervalSeconds * 20) {
            return "定时";
        }
        if (config.depositTriggerItem != null && !config.depositTriggerItem.isEmpty()
                && config.depositTriggerCount > 0
                && countItem(player, config.depositTriggerItem) >= config.depositTriggerCount) {
            return "攒够";
        }
        if (config.recycle != null && config.recycle.enabled && !config.chests.isEmpty()) {
            int pct = pickPercent(player);
            if (config.recycle.pickDurabilityPercent > 0 && pct >= 0 && pct <= config.recycle.pickDurabilityPercent) {
                return "镐子耐久 " + pct + "%";
            }
            int free = freeSlots(player);
            if (config.recycle.minFreeSlots > 0 && free >= 0 && free <= config.recycle.minFreeSlots) {
                return "背包只剩 " + free + " 格";
            }
        }
        return null;
    }

    private boolean shouldDeposit(LocalPlayer player) {
        return depositReason(player) != null;
    }

    private boolean hasPickaxe(LocalPlayer player) {
        if (player == null) {
            return true;
        }
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && stack.getItem().getDescriptionId().contains("pickaxe")) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- 走路

    private void tickWalking(Minecraft mc, LocalPlayer player, ClientLevel level) {
        if (targetChest == null) {
            state = State.MINING;
            return;
        }

        if (player.distanceToSqr(Vec3.atCenterOf(targetChest)) <= (double) config.returnRange * config.returnRange + 1.0) {
            state = State.OPENING;
            stateTicks = 0;
            return;
        }

        if (!BaritoneHook.isPathing()) {
            if (stateTicks < 20) {
                return;
            }
            if (walkRetries < 2) {
                walkRetries++;
                stateTicks = 0;
                BaritoneHook.walkTo(targetChest, config.returnRange);
                return;
            }
            message("走不到箱子，换下一个。");
            if (!nextChestOrGiveUp(level)) {
                return;
            }
            return;
        }

        // 兜底：三分钟还没到，放弃这次存矿
        if (stateTicks > 20 * 180) {
            message("前往箱子超时，先继续挖矿。");
            resumeMining();
        }
    }

    private boolean nextChestOrGiveUp(ClientLevel level) {
        MinerConfig.ChestEntry next = pickChest(level);
        if (next == null) {
            message("没有可用的箱子，先继续挖矿。");
            resumeMining();
            return false;
        }
        targetChest = next.pos();
        walkRetries = 0;
        stateTicks = 0;
        state = State.WALKING;
        BaritoneHook.walkTo(targetChest, config.returnRange);
        return true;
    }

    // ---------------------------------------------------------------- 开箱

    private void tickOpening(Minecraft mc, LocalPlayer player, ClientLevel level) {
        if (isContainerOpen(player)) {
            state = State.DEPOSITING;
            stateTicks = 0;
            emptyPasses = 0;
            return;
        }

        if (stateTicks > 20 * 10) {
            message("箱子打不开（可能被挡住或没权限），先继续挖矿。");
            resumeMining();
            return;
        }

        // 每 10 tick 点一次右键，直到容器打开
        if (stateTicks % 10 == 1 && targetChest != null) {
            Vec3 hit = Vec3.atCenterOf(targetChest);
            mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(hit, Direction.UP, targetChest, false));
        }
    }

    // ---------------------------------------------------------------- 存矿

    private void tickDepositing(Minecraft mc, LocalPlayer player, ClientLevel level) {
        if (!isContainerOpen(player)) {
            message("箱子界面已关闭，先继续挖矿。");
            resumeMining();
            return;
        }
        AbstractContainerMenu menu = player.containerMenu;

        if (stateTicks > 20 * 60) {
            message("存矿超时，先继续挖矿。");
            closeAndResume(player);
            return;
        }

        if (stateTicks % 3 != 0) {
            return;
        }

        if (moveOneStack(mc, player, menu)) {
            emptyPasses = 0;
            return;
        }

        if (wantSparePick && !sparePickTried) {
            sparePickTried = true;
            boolean got = takePickFromChest(mc, player, menu);
            if (got) {
                message("从箱子里拿了一把备用镐。");
                emptyPasses = 0;
            } else {
                message("箱子里没找到备用镐" + (config.recycle.continueWithoutSpare
                        ? "，先接着挖，记得手动换镐。" : "。"));
            }
            return;
        }

        emptyPasses++;
        if (emptyPasses >= 2) {
            closeAndResume(player);
        }
    }

    /** 把一件符合条件的物品快速移进箱子。 */
    private boolean moveOneStack(Minecraft mc, LocalPlayer player, AbstractContainerMenu menu) {
        for (Slot slot : menu.slots) {
            if (slot.container != player.getInventory()) {
                continue;
            }
            if (slot.getContainerSlot() >= 36) {
                continue; // 跳过盔甲与副手
            }
            ItemStack stack = slot.getItem();
            if (stack.isEmpty() || !shouldDeposit(stack)) {
                continue;
            }
            mc.gameMode.handleContainerInput(menu.containerId, slot.index, 0, ContainerInput.QUICK_MOVE, player);
            return true;
        }
        return false;
    }

    private boolean shouldDeposit(ItemStack stack) {
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        if (config.depositItems.contains(id)) {
            return true;
        }
        if (!config.depositAll) {
            return false;
        }
        if (config.keepItems.contains(id)) {
            return false;
        }
        // 不可堆叠的基本都是工具/装备/药水，留在身上
        return stack.getMaxStackSize() > 1;
    }

    /** 从打开的箱子里拿一把镐，拿到手后自动切到那一格。 */
    private boolean takePickFromChest(Minecraft mc, LocalPlayer player, AbstractContainerMenu menu) {
        MinerConfig.Recycle rc = config.recycle;
        String wantId = rc == null ? "" : rc.pickItemId;
        for (Slot slot : menu.slots) {
            if (slot.container == player.getInventory()) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (stack.isEmpty() || !stack.isDamageableItem()) {
                continue;
            }
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
            boolean match = (wantId != null && !wantId.isEmpty())
                    ? id.equals(wantId) || path.equals(wantId)
                    : path.contains("pickaxe");
            if (!match) {
                continue;
            }
            mc.gameMode.handleContainerInput(menu.containerId, slot.index, 0, ContainerInput.QUICK_MOVE, player);
            selectBestPick(player);
            return true;
        }
        return false;
    }

    /** 拿到新镐后，把快捷键切到耐久最好的那把。 */
    private void selectBestPick(LocalPlayer player) {
        Inventory inventory = player.getInventory();
        int bestSlot = -1;
        int bestLeft = -1;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || !stack.isDamageableItem()) {
                continue;
            }
            if (!BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().contains("pickaxe")) {
                continue;
            }
            int max = stack.getMaxDamage();
            if (max <= 0) {
                continue;
            }
            int left = max - stack.getDamageValue();
            if (left > bestLeft) {
                bestLeft = left;
                bestSlot = slot;
            }
        }
        if (bestSlot >= 0) {
            inventory.setSelectedSlot(bestSlot);
        }
    }

    private void closeAndResume(LocalPlayer player) {
        player.closeContainer();
        state = State.COOLDOWN;
        stateTicks = 0;
    }

    private void tickCooldown(Minecraft mc, LocalPlayer player) {
        if (stateTicks < 10) {
            return;
        }
        if (wantSparePick) {
            int pct = pickPercent(player);
            if (pct < 0) {
                message("身上已经没有镐了，自动采矿暂停。");
                wantSparePick = false;
                if (!config.recycle.continueWithoutSpare) {
                    stop(true);
                    return;
                }
            } else if (pct <= config.recycle.pickDurabilityPercent) {
                message("换镐没成功，现在这把只剩 " + pct + "%，继续挖但留意点。");
            }
            wantSparePick = false;
        }
        if (!postDepositDone) {
            postDepositDone = true;
            if (runHook(HookEvent.POST_DEPOSIT, this::resumeMining)) {
                return;
            }
        }
        resumeMining();
    }

    // ---------------------------------------------------------------- 工具

    private void resumeMining() {
        targetChest = null;
        walkRetries = 0;
        mineIdleTicks = 0;
        mineRestarts = 0;
        state = State.MINING;
        stateTicks = 0;
        BaritoneHook.startMine(config.mineBlocks);
    }

    private MinerConfig.ChestEntry pickChest(ClientLevel level) {
        int size = config.chests.size();
        if (size == 0) {
            return null;
        }
        for (int i = 0; i < size; i++) {
            int index = (chestIndex + i) % size;
            MinerConfig.ChestEntry entry = config.chests.get(index);
            if (hasFreeSpace(level, entry.pos())) {
                chestIndex = (index + 1) % size;
                return entry;
            }
        }
        return null;
    }

    private boolean hasFreeSpace(ClientLevel level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof Container container)) {
            return false;
        }
        int size = container.getContainerSize();
        for (int slot = 0; slot < size; slot++) {
            if (container.getItem(slot).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private boolean isContainerOpen(LocalPlayer player) {
        return player.containerMenu != null
                && player.containerMenu != player.inventoryMenu
                && player.containerMenu.containerId != player.inventoryMenu.containerId;
    }

    private void message(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal(PREFIX + text));
        }
        AutoMiner.LOGGER.info(text);
    }
}
