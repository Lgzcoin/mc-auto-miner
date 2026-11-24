package com.doubao.autominer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * 开荒流程里“进服后要做的杂事”：
 * 登录成功之后，把配置里的 onJoinCommands 按顺序发一遍（比如 /home base、/tpa 队友、/kit）。
 * 只在新进一次世界后执行一次，掉线重连会重新执行。
 */
public final class Onboard {
    private static ClientLevel lastLevel = null;
    private static List<String> queue = new ArrayList<>();
    private static int index = 0;
    private static int gap = 0;
    private static int authWait = 0;
    private static boolean done = true;

    private Onboard() {
    }

    public static boolean done() {
        return done;
    }

    public static void reset() {
        lastLevel = null;
        queue = new ArrayList<>();
        index = 0;
        gap = 0;
        authWait = 0;
        done = true;
    }

    public static void tick(Minecraft mc) {
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null || player.connection == null) {
            reset();
            return;
        }
        if (level != lastLevel) {
            lastLevel = level;
            MinerConfig cfg = AutoMiner.config();
            queue = cfg == null ? new ArrayList<>() : new ArrayList<>(cfg.onJoinCommands);
            index = 0;
            gap = 0;
            authWait = 200;          // 最多等 10 秒登录
            done = queue.isEmpty();
            if (!queue.isEmpty()) {
                AutoMiner.LOGGER.info("开荒：排队 {} 条进服指令", queue.size());
            }
            return;
        }
        if (done || queue.isEmpty()) {
            return;
        }
        if (authWait > 0) {
            authWait--;
            // 配了认证指令就等服务器确认登录；没配就当已经能发
            MinerConfig cfg = AutoMiner.config();
            boolean needAuth = cfg != null && cfg.autoAuthCommands != null && !cfg.autoAuthCommands.isBlank();
            if (!needAuth || AutoAuth.isLoggedIn()) {
                authWait = 0;
            }
            if (authWait > 0) {
                return;
            }
        }
        if (gap > 0) {
            gap--;
            return;
        }
        String cmd = queue.get(index++);
        if (cmd == null || cmd.isBlank()) {
            if (index >= queue.size()) {
                done = true;
            }
            return;
        }
        cmd = cmd.trim();
        try {
            if (cmd.startsWith("/")) {
                player.connection.sendCommand(cmd.substring(1));
            } else {
                player.connection.sendChat(cmd);
            }
            AutoMiner.LOGGER.info("开荒：已发送第 {} 条指令", index);
        } catch (Throwable t) {
            AutoMiner.LOGGER.warn("开荒指令发送失败: {}", cmd, t);
        }
        gap = 30;                // 每条间隔 1.5 秒
        if (index >= queue.size()) {
            done = true;
        }
    }
}
