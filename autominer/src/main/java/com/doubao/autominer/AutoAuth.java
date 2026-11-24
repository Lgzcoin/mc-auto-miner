package com.doubao.autominer;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 进服自动认证：连上服务器后，等几秒把配置里的指令依次发出去。
 * 常用于需要 /register /login 的离线验证服。
 * 注意：内容不写日志，避免密码落到 latest.log 里。
 */
public final class AutoAuth {
    private static ClientPacketListener lastConnection = null;
    private static List<String> queue = new ArrayList<>();
    private static int index = 0;
    private static int countdown = -1;
    private static int gap = 0;
    private static boolean loggedIn = false;
    private static boolean sentThisSession = false;
    private static String lastSent = "";
    private static String pendingLogin = null;
    private static int reloginDelay = -1;
    private static int loginRetries = 0;

    private AutoAuth() {
    }

    /** 监听服务器聊天：还提示没登录就自动补发一次登录；已经注册过就改发登录。 */
    public static void init() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> onServerMessage(message));
    }

    public static boolean isLoggedIn() {
        return loggedIn;
    }

    public static String stateLabel() {
        if (!sentThisSession) {
            return "未发送";
        }
        return loggedIn ? "已登录" : "未登录";
    }

    private static void onServerMessage(Component message) {
        String text;
        try {
            text = message == null ? "" : message.getString();
        } catch (Throwable t) {
            return;
        }
        if (text.isEmpty()) {
            return;
        }
        if (text.contains("登录成功") || text.contains("注册成功") || text.contains("欢迎第")) {
            if (!loggedIn) {
                AutoMiner.LOGGER.info("服务器确认登录成功。");
            }
            loggedIn = true;
            loginRetries = 0;
            pendingLogin = null;
            reloginDelay = -1;
            return;
        }
        if (text.contains("已经注册过了")) {
            String login = toLogin(lastSent);
            if (login != null) {
                pendingLogin = login;
                reloginDelay = 20;
                AutoMiner.LOGGER.info("服务器说这个号已注册过，改成发登录指令重试。");
            }
            return;
        }
        if ((text.contains("你还未登录") || text.contains("请先登录") || text.contains("未登录")) && !loggedIn) {
            if (pendingLogin == null && loginRetries < 6) {
                String cmd = firstAuthCommand();
                if (cmd != null) {
                    pendingLogin = toLogin(cmd);
                    if (pendingLogin == null) {
                        pendingLogin = cmd;
                    }
                    reloginDelay = 60;
                    AutoMiner.LOGGER.info("服务器仍在提示未登录，{} 秒后再补发一次（第 {} 次）。", 3, loginRetries + 1);
                }
            }
        }
    }

    /** /reg 密码 重复密码 -> /login 密码；本来就是 /login 就原样返回。 */
    private static String toLogin(String cmd) {
        if (cmd == null) {
            return null;
        }
        String c = cmd.trim();
        if (c.startsWith("/reg ")) {
            String[] parts = c.split("\\s+");
            if (parts.length >= 3) {
                return "/login " + parts[2];
            }
        }
        if (c.startsWith("/login ")) {
            return c;
        }
        return null;
    }

    private static String firstAuthCommand() {
        List<String> list = split(AutoMiner.config().autoAuthCommands);
        return list.isEmpty() ? null : list.get(0);
    }

    public static void tick(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            lastConnection = null;
            queue = new ArrayList<>();
            return;
        }
        if (player.connection == null) {
            return;
        }
        if (player.connection != lastConnection) {
            lastConnection = player.connection;
            queue = split(AutoMiner.config().autoAuthCommands);
            index = 0;
            gap = 0;
            loggedIn = false;
            loginRetries = 0;
            pendingLogin = null;
            reloginDelay = -1;
            sentThisSession = !queue.isEmpty();
            countdown = Math.max(0, AutoMiner.config().autoAuthDelaySeconds * 20);
            if (!queue.isEmpty()) {
                AutoMiner.LOGGER.info("已排队 {} 条认证指令，{} 秒后发送", queue.size(),
                        AutoMiner.config().autoAuthDelaySeconds);
            }
            return;
        }

        // 服务器说没登录：过几秒补发登录指令
        if (reloginDelay > 0) {
            reloginDelay--;
            if (reloginDelay == 0 && pendingLogin != null) {
                String cmd = pendingLogin;
                pendingLogin = null;
                reloginDelay = -1;
                loginRetries++;
                lastSent = cmd;
                send(player, cmd);
            }
            return;
        }

        if (queue.isEmpty()) {
            return;
        }
        if (countdown > 0) {
            countdown--;
            return;
        }
        if (gap > 0) {
            gap--;
            return;
        }
        if (index >= queue.size()) {
            queue = new ArrayList<>();
            return;
        }
        String text = queue.get(index++);
        lastSent = text;
        sentThisSession = true;
        send(player, text);
        gap = 20; // 每条之间隔 1 秒
        if (index >= queue.size()) {
            queue = new ArrayList<>();
            AutoMiner.LOGGER.info("认证指令已全部发送。");
        }
    }

    /** 立即发送一条：以 / 开头当命令，否则当聊天。 */
    public static boolean send(LocalPlayer player, String raw) {
        if (player == null || player.connection == null) {
            return false;
        }
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            return false;
        }
        if (text.startsWith("/")) {
            player.connection.sendCommand(text.substring(1));
        } else {
            player.connection.sendChat(text);
        }
        // 只记条数不记内容，密码不进日志
        AutoMiner.LOGGER.info("已发送认证指令（内容已隐去）");
        return true;
    }

    private static List<String> split(String raw) {
        List<String> list = new ArrayList<>();
        if (raw == null) {
            return list;
        }
        for (String piece : raw.split("\\|")) {
            String text = piece.trim();
            if (!text.isEmpty()) {
                list.add(text);
            }
        }
        return list;
    }
}
