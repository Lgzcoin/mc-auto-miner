package com.doubao.autominer;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 每秒把“现在屏幕上是什么、在干什么”写进游戏目录的 autominer_status.txt，
 * 控制台读这个文件就能显示每个机器人的实时状态，不用你盯着游戏窗口。
 */
public final class StatusWriter {
    private static int ticks = 0;
    private static String last = "";

    private StatusWriter() {
    }

    public static void tick(Minecraft mc) {
        ticks++;
        if (ticks % 20 != 0) {
            return;
        }
        String body;
        try {
            body = build(mc);
        } catch (Throwable t) {
            body = "screen=?\ninworld=0\nmining=\nerror=" + t.getClass().getSimpleName() + "\n";
        }
        if (body.equals(last)) {
            return;
        }
        last = body;
        try {
            Files.writeString(FabricLoader.getInstance().getGameDir().resolve("autominer_status.txt"),
                    body, StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            // 写不进去就算了，不影响游戏
        }
    }

    private static String build(Minecraft mc) {
        StringBuilder sb = new StringBuilder(512);
        Screen s = mc.gui.screen();
        String screen = "none";
        String title = "";
        if (s != null) {
            screen = s.getClass().getSimpleName();
            if (s instanceof AbstractContainerScreen<?> cs) {
                try {
                    title = cs.getTitle().getString();
                } catch (Throwable ignored) {
                }
            } else {
                try {
                    var t = s.getTitle();
                    if (t != null) {
                        title = t.getString();
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        sb.append("screen=").append(screen).append('\n');
        sb.append("title=").append(title == null ? "" : title.replace('\n', ' ').replace('\r', ' ')).append('\n');

        MinerConfig cfg = AutoMiner.config();
        MinerController c = AutoMiner.controller();
        LocalPlayer p = mc.player;
        boolean inWorld = mc.level != null && p != null;
        sb.append("inworld=").append(inWorld ? "1" : "0").append('\n');
        if (inWorld) {
            sb.append("dim=").append(mc.level.dimension().identifier()).append('\n');
            sb.append("pos=").append(p.getBlockX()).append(',').append(p.getBlockY()).append(',').append(p.getBlockZ()).append('\n');
            sb.append("hp=").append((int) Math.ceil(p.getHealth())).append('\n');
            sb.append("food=").append(p.getFoodData().getFoodLevel()).append('\n');
            int pick = MinerController.pickPercent(p);
            sb.append("pick=").append(pick < 0 ? "无" : pick + "%").append('\n');
            sb.append("free=").append(MinerController.freeSlots(p)).append('\n');
        } else {
            sb.append("dim=\npos=\nhp=\nfood=\npick=\nfree=\n");
        }
        sb.append("mining=").append(c == null ? "" : c.stateNow().label()).append('\n');
        sb.append("enabled=").append(cfg != null && cfg.enabled ? "1" : "0").append('\n');
        sb.append("join=").append(inWorld ? "" : String.valueOf(AutoJoin.attemptsNow())).append('\n');
        sb.append("auth=").append(inWorld ? AutoAuth.stateLabel() : "").append('\n');
        if (inWorld && cfg != null && cfg.depositTriggerItem != null && !cfg.depositTriggerItem.isEmpty()) {
            sb.append("trigger=").append(cfg.depositTriggerItem.replace("minecraft:", ""))
              .append('=').append(MinerController.countItem(p, cfg.depositTriggerItem)).append('\n');
        } else {
            sb.append("trigger=\n");
        }
        return sb.toString();
    }
}
