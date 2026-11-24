package com.doubao.autominer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.function.Consumer;

/**
 * /am get 的执行器：先挖够原料，再一格格合成，全程走状态机，避免卡死。
 */
public final class GetJob {
    private enum Phase { MINING, PREP, PLACE, CHECK, TAKE, DONE }

    private final GetPlan plan;
    private final String targetItem;
    private final int targetQty;
    private final Consumer<String> messenger;

    private Phase phase = Phase.MINING;
    private int mineIndex = 0;
    private int craftIndex = 0;
    private int wait = 0;
    private int restarts = 0;
    private int attempts = 0;
    private int stuckTicks = 0;
    private int lastCount = -1;
    private int stepStartCount = 0;
    private int containerId = -1;
    private boolean tableWarned = false;
    private boolean finished = false;

    public GetJob(GetPlan plan, String targetItem, int targetQty, Consumer<String> messenger) {
        this.plan = plan;
        this.targetItem = targetItem;
        this.targetQty = targetQty;
        this.messenger = messenger;
    }

    public boolean finished() {
        return finished;
    }

    public String statusLabel() {
        return switch (phase) {
            case MINING -> "获取材料";
            case PREP, PLACE, CHECK, TAKE -> "合成中";
            case DONE -> "已完成";
        };
    }

    public void tick(Minecraft mc, LocalPlayer player) {
        if (finished) {
            return;
        }
        switch (phase) {
            case MINING -> tickMining(mc, player);
            case PREP -> tickPrep(mc, player);
            case PLACE -> tickPlace(mc, player);
            case CHECK -> tickCheck(mc, player);
            case TAKE -> tickTake(mc, player);
            default -> finished = true;
        }
    }

    // ------------------------------------------------------------ 挖材料

    private void tickMining(Minecraft mc, LocalPlayer player) {
        if (mineIndex >= plan.mines.size()) {
            BaritoneHook.cancelAll();
            beginCrafts(player);
            return;
        }
        GetPlan.MineStep step = plan.mines.get(mineIndex);
        int have = countItem(player, step.itemId());
        if (have >= step.qty()) {
            BaritoneHook.cancelAll();
            message("材料够了：" + shortName(step.itemId()) + " " + step.qty() + " 个。");
            mineIndex++;
            restarts = 0;
            lastCount = -1;
            stuckTicks = 0;
            wait = 20;
            return;
        }

        if (wait > 0) {
            wait--;
            return;
        }

        if (!BaritoneHook.isMining()) {
            if (restarts >= 3) {
                fail("挖 " + shortName(step.itemId()) + " 一直挖不到，先停下了（可能附近没有这种矿）。");
                return;
            }
            restarts++;
            BaritoneHook.startMine(List.of(step.blocks().toArray(new String[0])));
            message("开始挖 " + shortName(step.itemId()) + "（现在 " + have + "/" + step.qty() + "）。");
            lastCount = have;
            stuckTicks = 0;
            wait = 40;
            return;
        }

        if (have != lastCount) {
            lastCount = have;
            stuckTicks = 0;
        } else {
            stuckTicks++;
            if (stuckTicks > 20 * 90) {
                fail("挖 " + shortName(step.itemId()) + " 卡住了，停一下检查下背包和周围地形。");
            }
        }
    }

    private void beginCrafts(LocalPlayer player) {
        if (craftIndex >= plan.crafts.size()) {
            finish();
            return;
        }
        stepStartCount = countItem(player, plan.crafts.get(craftIndex).resultItem());
        attempts = 0;
        phase = Phase.PREP;
        wait = 0;
        tableWarned = false;
    }

    // ------------------------------------------------------------ 合成

    private void tickPrep(Minecraft mc, LocalPlayer player) {
        GetPlan.CraftStep step = plan.crafts.get(craftIndex);
        if (!step.needsTable()) {
            if (player.containerMenu instanceof AbstractCraftingMenu menu) {
                containerId = menu.containerId;
            } else {
                player.closeContainer();
                containerId = 0; // 玩家自己的 2x2 合成格永远在 0 号容器里
            }
            phase = Phase.PLACE;
            wait = 2;
            return;
        }

        if (player.containerMenu instanceof CraftingMenu menu) {
            containerId = menu.containerId;
            phase = Phase.PLACE;
            wait = 2;
            return;
        }

        // 需要 3x3：先试着自动摆工作台，摆不了就等人手动打开
        if (tryPlaceTable(mc, player)) {
            return;
        }
        if (!tableWarned) {
            tableWarned = true;
            message("合成 " + shortName(step.resultItem()) + " 需要工作台：背包里有工作台的话放脚边，"
                    + "对着它右键打开，我会接着合成。");
            wait = 20;
        }
        if (wait > 0) {
            wait--;
            return;
        }
        stuckTicks++;
        if (stuckTicks > 20 * 90) {
            fail("等不到工作台，先停下了。");
        }
    }

    private void tickPlace(Minecraft mc, LocalPlayer player) {
        GetPlan.CraftStep step = plan.crafts.get(craftIndex);
        if (wait > 0) {
            wait--;
            return;
        }
        try {
            mc.gameMode.handlePlaceRecipe(containerId, step.displayId(), true);
        } catch (Throwable t) {
            AutoMiner.LOGGER.warn("摆放配方失败", t);
        }
        phase = Phase.CHECK;
        wait = 6;
    }

    private void tickCheck(Minecraft mc, LocalPlayer player) {
        if (wait > 0) {
            wait--;
            return;
        }
        ItemStack result = player.containerMenu.getSlot(0).getItem();
        if (result.isEmpty()) {
            attempts++;
            if (attempts >= 3) {
                fail("服务器没有把材料摆进合成格（可能这个配方还没解锁，或者材料不够）。");
                return;
            }
            phase = Phase.PLACE;
            wait = 5;
            return;
        }
        phase = Phase.TAKE;
        wait = 0;
    }

    private void tickTake(Minecraft mc, LocalPlayer player) {
        GetPlan.CraftStep step = plan.crafts.get(craftIndex);
        if (wait > 0) {
            wait--;
            return;
        }
        try {
            mc.gameMode.handleContainerInput(containerId, 0, 0, ContainerInput.QUICK_MOVE, player);
        } catch (Throwable t) {
            AutoMiner.LOGGER.warn("取出合成结果失败", t);
        }
        int produced = countItem(player, step.resultItem()) - stepStartCount;
        if (produced >= step.qty()) {
            message("合成完成：" + shortName(step.resultItem()) + " ×" + step.qty());
            craftIndex++;
            restarts = 0;
            stuckTicks = 0;
            beginCrafts(player);
            return;
        }
        attempts++;
        if (attempts > step.qty() + 4) {
            fail("合成一直在原地打转，停下了。");
            return;
        }
        phase = Phase.PLACE;
        wait = 3;
    }

    // ------------------------------------------------------------ 工具

    private boolean tryPlaceTable(Minecraft mc, LocalPlayer player) {
        if (mc.level == null) {
            return false;
        }
        ItemStack table = findInInventory(player, "minecraft:crafting_table");
        if (table.isEmpty()) {
            return false;
        }
        if (player.containerMenu instanceof CraftingMenu) {
            return true;
        }
        if (player.getBlockY() <= 0) {
            return false;
        }
        BlockPos base = null;
        for (int dy = -1; dy <= 1 && base == null; dy++) {
            for (int dx = -1; dx <= 1 && base == null; dx++) {
                for (int dz = -1; dz <= 1 && base == null; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    BlockPos candidate = player.blockPosition().offset(dx, dy, dz);
                    if (!mc.level.getBlockState(candidate).canBeReplaced()) {
                        continue;
                    }
                    BlockPos below = candidate.below();
                    if (mc.level.getBlockState(below).blocksMotion()) {
                        base = candidate;
                    }
                }
            }
        }
        if (base == null) {
            return false;
        }

        // 1) 对着下方方块顶面摆放工作台
        BlockPos below = base.below();
        BlockHitResult placeHit = new BlockHitResult(Vec3.atBottomCenterOf(below).add(0, 1, 0), Direction.UP, below, false);
        mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, placeHit);
        if (!mc.level.getBlockState(base).is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE)) {
            return false;
        }
        // 2) 右键打开它
        BlockHitResult openHit = new BlockHitResult(Vec3.atCenterOf(base), Direction.UP, base, false);
        mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, openHit);
        if (player.containerMenu instanceof CraftingMenu) {
            containerId = player.containerMenu.containerId;
            phase = Phase.PLACE;
            wait = 4;
            message("已自动摆好工作台并打开。");
            return true;
        }
        return false;
    }

    private static ItemStack findInInventory(LocalPlayer player, String itemId) {
        Identifier id = Identifier.tryParse(itemId);
        if (id == null) {
            return ItemStack.EMPTY;
        }
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(id)) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    static int countItem(LocalPlayer player, String itemId) {
        Identifier id = Identifier.tryParse(itemId);
        if (id == null) {
            return 0;
        }
        int total = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(id)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private void finish() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null) {
            p.closeContainer();
        }
        BaritoneHook.cancelAll();
        message("搞定：包里 " + shortName(targetItem) + " 现在有 "
                + (p == null ? 0 : countItem(p, targetItem)) + " 个（目标 " + targetQty + "）。");
        phase = Phase.DONE;
        finished = true;
    }

    private void fail(String reason) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null) {
            p.closeContainer();
        }
        BaritoneHook.cancelAll();
        message("没拿到：" + reason);
        phase = Phase.DONE;
        finished = true;
    }

    private void message(String text) {
        messenger.accept(text);
    }

    private static String shortName(String itemId) {
        return itemId.contains(":") ? itemId.substring(itemId.indexOf(':') + 1) : itemId;
    }
}
