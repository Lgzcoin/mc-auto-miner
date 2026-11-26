package com.doubao.autominer;

import com.mojang.blaze3d.platform.InputConstants;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** 宏录制与回放：录制界面里的鼠标点击，按界面标题保存，可手动或自动重放。 */
public final class MacroManager {
    private static final String DIR_NAME = "宏";
    private static final long MAX_STEP_DELAY = 5000L;
    private static final int MAX_STEPS = 400;
    private static final long AUTO_DELAY_MS = 350L;

    private static boolean auto = true;
    private static boolean loadedAuto = false;

    private static boolean recording = false;
    private static String recTitle = "";
    private static final List<MacroPlan.Step> recSteps = new ArrayList<>();
    private static long recLastMs = 0L;
    private static final boolean[] prevButtons = new boolean[3];

    private static boolean playing = false;
    private static String playTitle = "";
    private static final List<MacroPlan.Step> playSteps = new ArrayList<>();
    private static int playIndex = 0;
    private static long playStartMs = 0L;

    private static String pendingName = null;
    private static long pendingAtMs = 0L;
    private static String lastScreenTitle = null;
    private static long lastAutoPlayMs = 0L;
    private static final long AUTO_COOLDOWN_MS = 3000L;

    private MacroManager() {
    }

    public static Path dir() {
        return FabricLoader.getInstance().getGameDir().resolve(DIR_NAME);
    }

    private static void ensureAutoLoaded() {
        if (loadedAuto) {
            return;
        }
        loadedAuto = true;
        try {
            Path cfg = FabricLoader.getInstance().getGameDir().resolve("宏配置.txt");
            if (Files.exists(cfg)) {
                String t = Files.readString(cfg, StandardCharsets.UTF_8);
                auto = !t.contains("自动播放=关");
            }
        } catch (Throwable ignored) {
        }
    }

    private static void saveAuto() {
        try {
            Files.createDirectories(dir());
            Files.writeString(FabricLoader.getInstance().getGameDir().resolve("宏配置.txt"),
                    "自动播放=" + (auto ? "开" : "关") + "\n", StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
        }
    }

    private static String sanitize(String name) {
        String s = name == null ? "" : name.trim();
        s = s.replaceAll("§.", "").replaceAll("[\\\\/:*?\"<>|]", "_").replaceAll("\\s+", "_");
        if (s.isEmpty()) {
            s = "默认";
        }
        return s.length() > 40 ? s.substring(0, 40) : s;
    }

    public static List<String> listNames() {
        List<String> out = new ArrayList<>();
        try {
            Files.createDirectories(dir());
            try (var st = Files.list(dir())) {
                st.filter(p -> p.getFileName().toString().endsWith(".txt"))
                        .forEach(p -> out.add(p.getFileName().toString().replace(".txt", "")));
            }
        } catch (Throwable ignored) {
        }
        out.sort(String::compareTo);
        return out;
    }

    public static boolean exists(String name) {
        return Files.exists(dir().resolve(sanitize(name) + ".txt"));
    }

    private static MacroPlan.Plan load(String name) {
        try {
            Path p = dir().resolve(sanitize(name) + ".txt");
            if (!Files.exists(p)) {
                return null;
            }
            return MacroPlan.parse(Files.readString(p, StandardCharsets.UTF_8));
        } catch (Throwable t) {
            return null;
        }
    }

    private static void save(String title, List<MacroPlan.Step> steps) {
        try {
            Files.createDirectories(dir());
            Files.writeString(dir().resolve(sanitize(title) + ".txt"),
                    MacroPlan.toText(title, steps), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            msg("[宏] §c宏保存失败：" + t.getMessage());
        }
    }

    public static void delete(String name) {
        try {
            Files.deleteIfExists(dir().resolve(sanitize(name) + ".txt"));
        } catch (Throwable ignored) {
        }
    }

    private static String screenTitle(Screen s) {
        try {
            return s == null ? null : s.getTitle().getString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** F7：开始/结束录制（只在有界面时能开始）。 */
    public static void toggleRecord() {
        Minecraft mc = Minecraft.getInstance();
        if (playing) {
            msg("[宏] §e正在回放，先按 F8 或等它结束再录制。");
            return;
        }
        if (recording) {
            stopRecording();
            return;
        }
        Screen s = mc.gui.screen();
        String title = screenTitle(s);
        if (title == null || title.isBlank()) {
            msg("[宏] §e先打开要录制的界面（箱子/商店/菜单），再按 F7 开始录制。");
            return;
        }
        recTitle = title;
        recSteps.clear();
        recLastMs = System.currentTimeMillis();
        pendingName = null;
        long handle = mc.getWindow().handle();
        for (int i = 0; i < prevButtons.length; i++) {
            prevButtons[i] = GLFW.glfwGetMouseButton(handle, i) == GLFW.GLFW_PRESS;
        }
        recording = true;
        msg("[宏] §a开始录制「" + title + "」：正常点击按钮，再按 F7 结束。");
    }

    private static void stopRecording() {
        recording = false;
        if (recSteps.isEmpty()) {
            msg("[宏] §e没录到点击，已丢弃。");
            return;
        }
        save(recTitle, new ArrayList<>(recSteps));
        msg("[宏] §a已保存宏「" + recTitle + "」，共 " + recSteps.size() + " 步。F8 可手动重放。");
        recSteps.clear();
    }

    /** F8：重放当前界面对应的宏；没有则重放最近一个。 */
    public static void playCurrent() {
        Minecraft mc = Minecraft.getInstance();
        if (playing) {
            stopPlay("§e已停止回放。");
            return;
        }
        if (recording) {
            msg("[宏] §e正在录制，先按 F7 结束。");
            return;
        }
        Screen s = mc.gui.screen();
        if (s == null) {
            msg("[宏] §e先打开界面再按 F8。");
            return;
        }
        String title = screenTitle(s);
        String name = title == null ? "" : sanitize(title);
        if (!exists(name)) {
            List<String> all = listNames();
            if (all.isEmpty()) {
                msg("[宏] §e还没有宏，先按 F7 录一个。");
                return;
            }
            name = all.get(all.size() - 1);
        }
        startPlay(name, false);
    }

    public static boolean startPlay(String name, boolean forceTitle) {
        Minecraft mc = Minecraft.getInstance();
        MacroPlan.Plan plan = load(name);
        if (plan == null || plan.steps.isEmpty()) {
            msg("[宏] §e宏「" + name + "」不存在或是空的。");
            return false;
        }
        String cur = screenTitle(mc.gui.screen());
        if (cur == null) {
            msg("[宏] §e先打开界面再回放。");
            return false;
        }
        if (!forceTitle && !plan.title.isBlank() && !plan.title.equals(cur)) {
            msg("[宏] §e这个宏是给「" + plan.title + "」用的，当前界面是「" + cur + "」。");
            return false;
        }
        playSteps.clear();
        playSteps.addAll(plan.steps);
        playTitle = plan.title;
        playIndex = 0;
        playStartMs = System.currentTimeMillis();
        playing = true;
        msg("[宏] §a回放宏「" + sanitize(name) + "」，共 " + playSteps.size() + " 步。F8 可中途停止。");
        return true;
    }

    private static void stopPlay(String reason) {
        playing = false;
        playSteps.clear();
        playIndex = 0;
        if (reason != null) {
            msg(reason);
        }
    }

    /** F9：开关“同一界面打开时自动回放”。 */
    public static void toggleAuto() {
        ensureAutoLoaded();
        auto = !auto;
        saveAuto();
        msg(auto ? "[宏] §a已开启：打开对应界面会自动回放宏。" : "[宏] §e已关闭自动回放。");
    }

    public static boolean isBusy() {
        return recording || playing;
    }

    public static void setAuto(boolean on) {
        ensureAutoLoaded();
        auto = on;
        saveAuto();
    }

    public static void stopPlayback() {
        if (playing) {
            stopPlay("§e已停止回放。");
        }
    }

    public static String statusText() {
        ensureAutoLoaded();
        StringBuilder sb = new StringBuilder();
        sb.append("宏：").append(listNames().size()).append(" 个");
        sb.append("，自动回放").append(auto ? "开" : "关");
        if (recording) {
            sb.append("，§e录制中(").append(recSteps.size()).append("步)");
        }
        if (playing) {
            sb.append("，§b回放中(").append(playIndex).append('/').append(playSteps.size()).append(')');
        }
        return sb.toString();
    }

    public static void tick(Minecraft mc) {
        ensureAutoLoaded();
        if (mc.player == null) {
            return;
        }
        Screen screen = mc.gui.screen();
        String title = screenTitle(screen);

        if (recording) {
            pollRecord(mc, screen);
            return;
        }

        if (playing) {
            if (screen == null) {
                stopPlay("§e界面关了，回放停止。");
            } else {
                stepPlay(mc, screen);
            }
        }

        // 自动回放：界面从无到有（或换了新界面）时触发一次
        if (!playing && !recording) {
            if (title == null) {
                lastScreenTitle = null;
                pendingName = null;
            } else if (lastScreenTitle == null || !lastScreenTitle.equals(title)) {
                lastScreenTitle = title;
                pendingName = null;
                if (auto && System.currentTimeMillis() - lastAutoPlayMs > AUTO_COOLDOWN_MS) {
                    String name = sanitize(title);
                    if (exists(name)) {
                        pendingName = name;
                        pendingAtMs = System.currentTimeMillis() + AUTO_DELAY_MS;
                    }
                }
            } else if (pendingName != null && System.currentTimeMillis() >= pendingAtMs) {
                String n = pendingName;
                pendingName = null;
                if (startPlay(n, true)) {
                    lastAutoPlayMs = System.currentTimeMillis();
                }
            }
        }
    }

    private static void pollRecord(Minecraft mc, Screen screen) {
        long now = System.currentTimeMillis();
        long handle = mc.getWindow().handle();
        int guiW = Math.max(1, mc.getWindow().getGuiScaledWidth());
        int guiH = Math.max(1, mc.getWindow().getGuiScaledHeight());
        for (int b = 0; b < prevButtons.length; b++) {
            boolean down = GLFW.glfwGetMouseButton(handle, b) == GLFW.GLFW_PRESS;
            if (down && !prevButtons[b]) {
                double gx = mc.mouseHandler.getScaledXPos(mc.getWindow());
                double gy = mc.mouseHandler.getScaledYPos(mc.getWindow());
                double fx = Math.max(0, Math.min(1, gx / guiW));
                double fy = Math.max(0, Math.min(1, gy / guiH));
                long delay = Math.max(0, Math.min(MAX_STEP_DELAY, now - recLastMs));
                if (recSteps.size() < MAX_STEPS) {
                    recSteps.add(new MacroPlan.Step("CLICK", fx, fy, b, 0, delay));
                }
                recLastMs = now;
            }
            prevButtons[b] = down;
        }
        if (screen == null) {
            stopRecording();
        }
    }

    private static void stepPlay(Minecraft mc, Screen screen) {
        long now = System.currentTimeMillis();
        int guiW = Math.max(1, mc.getWindow().getGuiScaledWidth());
        int guiH = Math.max(1, mc.getWindow().getGuiScaledHeight());
        long elapsed = now - playStartMs;
        long cursor = 0L;
        while (playIndex < playSteps.size()) {
            MacroPlan.Step s = playSteps.get(playIndex);
            cursor += s.delay();
            if (elapsed < cursor) {
                break;
            }
            playIndex++;
            try {
                switch (s.kind()) {
                    case "CLICK" -> {
                        Screen cur = mc.gui.screen();
                        if (cur == null) {
                            stopPlay(null);
                            msg("[宏] §a宏执行完毕（界面关闭）。");
                            return;
                        }
                        double gx = s.x() * guiW;
                        double gy = s.y() * guiH;
                        cur.mouseMoved(gx, gy);
                        MouseButtonEvent ev = new MouseButtonEvent(gx, gy, new MouseButtonInfo(s.button(), 0));
                        cur.mouseClicked(ev, false);
                        cur.mouseReleased(ev);
                    }
                    case "KEY" -> {
                        Screen cur = mc.gui.screen();
                        if (cur != null) {
                            cur.keyPressed(new KeyEvent(s.key(), 0, 0));
                        }
                    }
                    default -> {
                    }
                }
            } catch (Throwable t) {
                stopPlay("§c回放出错：" + t.getMessage());
                return;
            }
            if (mc.gui.screen() == null) {
                stopPlay(null);
                msg("[宏] §a宏执行完毕（最后一步关闭了界面）。");
                return;
            }
        }
        if (playIndex >= playSteps.size()) {
            stopPlay(null);
            msg("[宏] §a宏执行完毕。");
        }
    }

    private static void msg(String text) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.sendSystemMessage(Component.literal(text));
            }
        } catch (Throwable ignored) {
        }
    }
}
