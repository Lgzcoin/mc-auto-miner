package com.doubao.autominer;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * 所有对男中音（Baritone）的调用都集中在这里。
 * 这样在没装 Baritone 时也不会因为类加载失败而让游戏崩溃。
 *
 * 注意：Baritone 有两种发行包——
 *   baritone-api-fabric-*.jar     含完整的 baritone.api 包（推荐，本模组必须用它）
 *   baritone-standalone-fabric-*.jar  精简版，剥离了 baritone.api（装它就会“识别不到”）
 */
public final class BaritoneHook {

    /** 男中音的可用状态，用于把“没装”和“装了但版本不对”区分开给用户看。 */
    public enum Status {
        OK("已就绪"),
        NO_MOD("没装男中音"),
        NO_API("装的是精简版，缺少 API");

        public final String label;

        Status(String label) {
            this.label = label;
        }
    }

    private static Status status;

    private BaritoneHook() {
    }

    public static synchronized Status status() {
        if (status == null) {
            status = detect();
        }
        return status;
    }

    public static boolean available() {
        return status() == Status.OK;
    }

    /** 重新检测一遍（比如用户中途加了 jar 不太可能，但方便 /am diag 复查）。 */
    public static synchronized void reset() {
        status = null;
    }

    private static Status detect() {
        if (!modPresent()) {
            return Status.NO_MOD;
        }
        try {
            Class.forName("baritone.api.BaritoneAPI", false, BaritoneHook.class.getClassLoader());
            return Status.OK;
        } catch (Throwable t) {
            return Status.NO_API;
        }
    }

    /** 扫描所有已加载的模组，id 等于或以 baritone 开头的都算（兼容改名/内嵌的变体）。 */
    public static boolean modPresent() {
        return !baritoneModIds().isEmpty();
    }

    public static List<String> baritoneModIds() {
        List<String> ids = new ArrayList<>();
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            String id = mod.getMetadata().getId();
            if (id.toLowerCase().contains("baritone")) {
                ids.add(id + "@" + mod.getMetadata().getVersion().getFriendlyString());
            }
        }
        return ids;
    }

    /** 给 /am diag 用的一行诊断信息。 */
    public static String diagnose() {
        reset();
        StringBuilder sb = new StringBuilder();
        sb.append("男中音状态：").append(status().label);
        sb.append("\n找到的 baritone 模组：").append(modPresent() ? String.join("、", baritoneModIds()) : "无");
        sb.append("\nbaritone.api 类：");
        try {
            Class.forName("baritone.api.BaritoneAPI", false, BaritoneHook.class.getClassLoader());
            sb.append("可加载");
        } catch (Throwable t) {
            sb.append("缺失（").append(t.getClass().getSimpleName()).append("）");
        }
        sb.append("\nFabric Loader：").append(FabricLoader.getInstance().getModContainer("fabricloader")
                .map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("未知"));
        sb.append("\n结论：");
        switch (status()) {
            case OK -> sb.append("一切正常，可以开始挖矿。");
            case NO_MOD -> sb.append("mods 里没有 Baritone。请放入 baritone-api-fabric-<26.2 对应版本>.jar，"
                    + "并确认 Fabric Loader ≥ 0.19.3。");
            case NO_API -> sb.append("Baritone 在，但装的是 stripping 过的 standalone 包，没有 baritone.api。"
                    + "换成 baritone-api-fabric-1.19.0.jar 即可。");
        }
        return sb.toString();
    }

    private static baritone.api.IBaritone baritone() {
        return baritone.api.BaritoneAPI.getProvider().getPrimaryBaritone();
    }

    /** 开始按矿石清单挖矿；数量 0 = 一直挖到被我们叫停。 */
    public static boolean startMine(List<String> blockIds) {
        if (!available()) {
            return false;
        }
        try {
            String[] names = blockIds.stream()
                    .map(id -> id.contains(":") ? id.substring(id.indexOf(':') + 1) : id)
                    .toArray(String[]::new);
            if (names.length == 0) {
                return false;
            }
            baritone.api.Settings settings = baritone.api.BaritoneAPI.getSettings();
            settings.mineScanDroppedItems.value = true;
            settings.allowBreak.value = true;
            baritone().getMineProcess().mineByName(0, names);
            return true;
        } catch (Throwable t) {
            AutoMiner.LOGGER.warn("启动 Baritone 挖矿失败", t);
            return false;
        }
    }

    public static boolean isMining() {
        if (!available()) {
            return false;
        }
        try {
            return baritone().getMineProcess().isActive();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 走到指定方块附近。 */
    public static boolean walkTo(BlockPos pos, int range) {
        if (!available()) {
            return false;
        }
        try {
            baritone().getCustomGoalProcess().setGoalAndPath(
                    new baritone.api.pathing.goals.GoalNear(pos, range));
            return true;
        } catch (Throwable t) {
            AutoMiner.LOGGER.warn("Baritone 寻路失败", t);
            return false;
        }
    }

    public static boolean isPathing() {
        if (!available()) {
            return false;
        }
        try {
            return baritone().getPathingBehavior().isPathing();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 取消 Baritone 当前的一切动作（挖矿 / 寻路）。 */
    public static void cancelAll() {
        if (!available()) {
            return;
        }
        try {
            baritone().getPathingBehavior().cancelEverything();
        } catch (Throwable t) {
            AutoMiner.LOGGER.warn("取消 Baritone 任务失败", t);
        }
    }
}
