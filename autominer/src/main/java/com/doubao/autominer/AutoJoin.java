package com.doubao.autominer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

/**
 * 自动进服：等游戏停在可以点“多人游戏”的那种界面（标题界面 / 断开提示界面）几秒后，自己连配置里的服务器。
 * 用来兜住 --quickPlayMultiplayer 被启动器/包装层吞掉的情况。
 */
public final class AutoJoin {
    private static int seenTicks = -1;
    private static int attempts = 0;
    private static String loggedAddr = null;
    private static int policyTicks = 0;
    private static boolean policyAccepted = false;
    private static Screen lastPolicyScreen = null;
    private static int policyTries = 0;
    private static boolean exhaustedLogged = false;

    private AutoJoin() {
    }

    public static void tick(Minecraft mc) {
        // 行为准则弹窗优先处理：这时可能已经有连接了，不能因为 getConnection()!=null 就跳过
        Screen now = mc.gui.screen();
        if (now != null && now.getClass().getSimpleName().contains("CodeOfConduct")) {
            tickPolicy(now, mc);
            return;
        }
        lastPolicyScreen = null;
        if (mc.level != null || mc.getConnection() != null) {
            seenTicks = -1;
            return;
        }
        policyTicks = 0;
        String addr = AutoMiner.config() == null ? "" : AutoMiner.config().autoJoinServer;
        if (addr == null) {
            addr = "";
        }
        addr = addr.trim();
        if (addr.isEmpty()) {
            seenTicks = -1;
            return;
        }
        if (loggedAddr == null || !loggedAddr.equals(addr)) {
            loggedAddr = addr;
            AutoMiner.LOGGER.info("自动进服已启用：{}（等 {} 秒连，失败隔 {} 秒重试，最多 {} 次）",
                    addr, AutoMiner.config().autoJoinDelaySeconds,
                    AutoMiner.config().autoJoinRetrySeconds, AutoMiner.config().autoJoinMaxAttempts);
        }
        int max = Math.max(1, AutoMiner.config().autoJoinMaxAttempts);
        boolean exhausted = attempts >= max;
        if (exhausted && !exhaustedLogged) {
            exhaustedLogged = true;
            AutoMiner.LOGGER.warn("已连续 {} 次进不去（多半是服务器满/被踢），改成每 {} 秒再试一次，不再放弃。", attempts, AutoMiner.config().autoJoinRetrySeconds);
        }
        Screen screen = mc.gui.screen();
        if (screen == null) {
            return;
        }
        // 已经在连了 / 在加载世界，就别插一脚
        if (screen instanceof ConnectScreen) {
            return;
        }
        String name = screen.getClass().getSimpleName();
        if (name.contains("Loading") || name.contains("Progress") || name.contains("Splash") || name.contains("Overlay")) {
            return;
        }
        if (seenTicks < 0) {
            seenTicks = 0;
            return;
        }
        seenTicks++;
        int waitSec = AutoMiner.config().autoJoinDelaySeconds;
        if (attempts > 0) {
            waitSec = Math.max(waitSec, AutoMiner.config().autoJoinRetrySeconds);
        }
        if (exhausted) {
            waitSec = Math.max(3, AutoMiner.config().autoJoinRetrySeconds);
        }
        int need = Math.max(0, waitSec) * 20;
        if (seenTicks < need) {
            return;
        }
        seenTicks = -1;
        attempts++;
        AutoMiner.LOGGER.info("第 {} 次尝试进服（当前界面 {}）", attempts, name);
        connect(mc, addr);
    }

    /** 立刻连一个地址（控制台 join 指令也走这里）。 */
    public static boolean connect(Minecraft mc, String rawAddr) {
        String addr = rawAddr == null ? "" : rawAddr.trim();
        if (addr.isEmpty()) {
            return false;
        }
        if (mc.gui.screen() instanceof ConnectScreen) {
            AutoMiner.LOGGER.info("已经在连接中，跳过重复连接：{}", addr);
            return false;
        }
        try {
            ServerAddress address = ServerAddress.parseString(addr);
            ServerData data = new ServerData("多开服务器", addr, ServerData.Type.OTHER);
            ConnectScreen.startConnecting(new net.minecraft.client.gui.screens.TitleScreen(), mc, address, data, false, null);
            AutoMiner.LOGGER.info("正在连接服务器：{}", addr);
            return true;
        } catch (Throwable t) {
            AutoMiner.LOGGER.warn("连接服务器失败：{}", addr, t);
            return false;
        }
    }

    /** 服务器（或客户端）弹行为准则时自动点“同意”，否则多人游戏根本连不进去。 */
    private static void tickPolicy(Screen screen, Minecraft mc) {
        // 每弹一次新的准则界面都要重新点一次（重连后原来的标记不能沿用）
        if (lastPolicyScreen != screen) {
            lastPolicyScreen = screen;
            policyAccepted = false;
            policyTicks = 0;
            policyTries = 0;
        }
        if (policyAccepted) {
            return;
        }
        policyTicks++;
        if (policyTicks < 20) {
            return;
        }
        if (policyTries >= 5) {
            return;
        }
        policyTicks = 0;
        policyTries++;
        boolean ok = false;
        // 1) 直接调 onResult(true)（安全点：从当前类往上找）
        Class<?> c = screen.getClass();
        while (c != null && !ok) {
            try {
                java.lang.reflect.Method m = c.getDeclaredMethod("onResult", boolean.class);
                m.setAccessible(true);
                m.invoke(screen, Boolean.TRUE);
                ok = true;
            } catch (NoSuchMethodException e) {
                c = c.getSuperclass();
            } catch (Throwable t) {
                break;
            }
        }
        // 2) 调不动就拿出内部的 resultConsumer 直接 accept(true)
        if (!ok) {
            try {
                java.lang.reflect.Field f = screen.getClass().getDeclaredField("resultConsumer");
                f.setAccessible(true);
                Object consumer = f.get(screen);
                if (consumer != null) {
                    consumer.getClass().getMethod("accept", boolean.class).invoke(consumer, Boolean.TRUE);
                    ok = true;
                }
            } catch (Throwable ignored) {
            }
        }
        if (ok) {
            policyAccepted = true;
            AutoMiner.LOGGER.info("已自动同意行为准则（CodeOfConduct），继续进服");
        }
    }

    public static int attemptsNow() {
        return attempts;
    }

    /** 换了地址或手动改了配置后，把重试次数清掉。 */
    public static void reset() {
        seenTicks = -1;
        attempts = 0;
        loggedAddr = null;
        policyTicks = 0;
        policyAccepted = false;
        lastPolicyScreen = null;
        policyTries = 0;
        exhaustedLogged = false;
    }
}
