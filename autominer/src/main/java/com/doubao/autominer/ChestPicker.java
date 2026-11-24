package com.doubao.autominer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 用准星把箱子登记成存矿点（热键 C）。
 */
public final class ChestPicker {
    private static final String PREFIX = "§b[自动采矿]§r ";

    private static BlockPos lastTarget = null;

    private ChestPicker() {
    }

    /** 每个客户端 tick 记录准星指着的容器方块，供 GUI 按钮和热键使用。 */
    public static void refresh(Minecraft mc) {
        if (mc.level == null || mc.player == null) {
            return;
        }
        if (mc.hitResult instanceof BlockHitResult hit) {
            BlockPos pos = hit.getBlockPos();
            BlockEntity blockEntity = mc.level.getBlockEntity(pos);
            if (blockEntity instanceof Container) {
                lastTarget = pos.immutable();
            }
        }
    }

    public static BlockPos lastTarget() {
        return lastTarget;
    }

    /** 把上次准星指着的容器加进存矿列表。 */
    public static boolean addLastTarget(boolean notify) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            return false;
        }
        if (lastTarget == null) {
            if (notify) {
                message(player, "请先用准星对着箱子，再按 C 键（或打开菜单点“添加准星箱子”）。");
            }
            return false;
        }
        MinerConfig config = AutoMiner.config();
        for (MinerConfig.ChestEntry entry : config.chests) {
            if (entry.pos().equals(lastTarget)) {
                if (notify) {
                    message(player, "这个位置已经在列表里了：" + entry.describe());
                }
                return false;
            }
        }
        String name = "箱子" + (config.chests.size() + 1);
        MinerConfig.ChestEntry entry = new MinerConfig.ChestEntry(name, lastTarget);
        config.chests.add(entry);
        config.save();
        if (notify) {
            message(player, "已记录存矿点“" + name + "” → " + entry.describe());
        }
        return true;
    }

    public static void message(LocalPlayer player, String text) {
        player.sendSystemMessage(Component.literal(PREFIX + text));
    }
}
