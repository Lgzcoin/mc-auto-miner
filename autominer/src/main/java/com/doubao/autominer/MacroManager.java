package com.doubao.autominer;

import java.awt.GraphicsEnvironment;
import java.awt.Robot;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * 宏录制与回放：录制界面里的鼠标点击和键盘按键，按界面标题保存，可手动或自动重放。
 * 没有打开界面时按 F7 会录成「全局」宏，适合先按 Shift+F 打开服务器菜单再点按钮。
 */
public final class MacroManager {
    private static final String DIR_NAME = "宏";
    private static final String GLOBAL_TITLE = "全局";
    private static final long MAX_STEP_DELAY = 5000L;
    private static final int MAX_STEPS = 400;
    private static final long AUTO_DELAY_MS = 350L;
    private static final long KEY_GUARD_MS = 450L;
    private static final long SCREEN_WAIT_MS = 3500L;

    private static boolean auto = true;
    private static boolean loadedAuto = false;

    private static boolean recording = false;
    private static boolean recGlobal = false;
    private static String recTitle = "";
    private static final List<MacroPlan.Step> recSteps = new ArrayList<>();
    private static long recLastMs = 0L;
    private static final boolean[] prevButtons = new boolean[3];
    private static final boolean[] prevKeys = new boolean[GLFW.GLFW_KEY_LAST + 1];

    private static boolean playing = false;
    private static boolean playGlobal = false;
    private static String playTitle = "";
    private static final List<MacroPlan.Step> playSteps = new ArrayList<>();
    private static int playIndex = 0;
    private static long playStartMs = 0L;
    private static long keyGuardUntil = 0L;
    private static long screenWaitUntil = 0L;

    private static Robot robot = null;
    private static boolean robotTried = false;

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

    /** 标题比较时去掉颜色符和多余空格，避免同一个菜单因为 §6 之类颜色码对不上。 */
    private static String cleanTitle(String t) {
        if (t == null) {
            return "";
        }
        return t.replaceAll("§.", "").replaceAll("\\s+", " ").trim();
    }

    private static boolean sameTitle(String a, String b) {
        return cleanTitle(a).equals(cleanTitle(b));
    }

    /** F7：开始/结束录制；没有界面时录「全局」宏，可以先录 Shift+F。 */
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
        boolean global = title == null || title.isBlank();
        if (global && mc.player == null) {
            msg("[宏] §e先进入游戏再录制。");
            return;
        }
        recTitle = global ? GLOBAL_TITLE : title;
        recGlobal = global;
        recSteps.clear();
        recLastMs = System.currentTimeMillis();
        pendingName = null;
        long handle = mc.getWindow().handle();
        for (int i = 0; i < prevButtons.length; i++) {
            prevButtons[i] = GLFW.glfwGetMouseButton(handle, i) == GLFW.GLFW_PRESS;
        }
        for (int i = 0; i < prevKeys.length; i++) {
            prevKeys[i] = isKeyDown(handle, i);
        }
        recording = true;
        if (global) {
            msg("[宏] §a开始录制「全局」：先按 Shift+F 打开菜单，再点按钮，最后按 F7 结束。");
        } else {
            msg("[宏] §a开始录制「" + title + "」：按键和鼠标点击都会录，再按 F7 结束。");
        }
    }

    private static void stopRecording() {
        recording = false;
        if (recSteps.isEmpty()) {
            recSteps.clear();
            recGlobal = false;
            msg("[宏] §e没录到点击或按键，已丢弃。");
            return;
        }
        save(recTitle, new ArrayList<>(recSteps));
        msg("[宏] §a已保存宏「" + recTitle + "」，共 " + recSteps.size()
                + " 步。F8 可手动重放。");
        recSteps.clear();
        recGlobal = false;
    }

    /** F8：有界面时重放对应宏；没有界面时重放「全局」宏。 */
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
            if (!exists(GLOBAL_TITLE)) {
                msg("[宏] §e没有「全局」宏。先在游戏里按 F7 录一个 Shift+F 开菜单的宏。");
                return;
            }
            startPlay(GLOBAL_TITLE, true);
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
            String fallback = null;
            for (String n : all) {
                if (sameTitle(n, name)) {
                    fallback = n;
                    break;
                }
            }
            if (fallback == null && all.size() == 1) {
                fallback = all.get(0);
            }
            if (fallback == null) {
                msg("[宏] §e当前界面没有对应的宏。已有：" + String.join("、", all)
                        + "。先打开对应菜单再按 F8，或用 /am macro play 名字。");
                return;
            }
            name = fallback;
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
        boolean global = plan.title.isBlank() || sameTitle(plan.title, GLOBAL_TITLE);
        if (cur == null && !global) {
            msg("[宏] §e这个宏是界面宏，先按 Shift+F 打开对应菜单再按 F8。");
            return false;
        }
        if (cur != null && !forceTitle && !global && !plan.title.isBlank() && !sameTitle(plan.title, cur)) {
            msg("[宏] §e这个宏是给「" + plan.title + "」用的，当前界面是「" + cur + "」。");
            return false;
        }
        playSteps.clear();
        playSteps.addAll(plan.steps);
        playTitle = plan.title;
        playGlobal = global;
        playIndex = 0;
        playStartMs = System.currentTimeMillis();
        keyGuardUntil = 0L;
        screenWaitUntil = playGlobal ? System.currentTimeMillis() + SCREEN_WAIT_MS : 0L;
        playing = true;
        msg("[宏] §a回放宏「" + sanitize(name) + "」，共 " + playSteps.size() + " 步。F8 可中途停止。");
        return true;
    }

    private static void stopPlay(String reason) {
        playing = false;
        playGlobal = false;
        playSteps.clear();
        playIndex = 0;
        keyGuardUntil = 0L;
        screenWaitUntil = 0L;
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
            pollRecord(mc);
            return;
        }

        if (playing) {
            if (screen == null && !playGlobal) {
                stopPlay("§e界面关了，回放停止。");
            } else {
                stepPlay(mc);
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

    private static void pollRecord(Minecraft mc) {
        long now = System.currentTimeMillis();
        long handle = mc.getWindow().handle();
        Screen screen = mc.gui.screen();
        int guiW = Math.max(1, mc.getWindow().getGuiScaledWidth());
        int guiH = Math.max(1, mc.getWindow().getGuiScaledHeight());
        int mods = currentMods(handle);

        // 只在有界面时记录鼠标点击，避免在世界里攻击/使用物品被录进去。
        if (screen != null) {
            for (int b = 0; b < prevButtons.length; b++) {
                boolean down = GLFW.glfwGetMouseButton(handle, b) == GLFW.GLFW_PRESS;
                if (down && !prevButtons[b]) {
                    double gx = mc.mouseHandler.getScaledXPos(mc.getWindow());
                    double gy = mc.mouseHandler.getScaledYPos(mc.getWindow());
                    double fx = Math.max(0, Math.min(1, gx / guiW));
                    double fy = Math.max(0, Math.min(1, gy / guiH));
                    long delay = Math.max(0, Math.min(MAX_STEP_DELAY, now - recLastMs));
                    if (recSteps.size() < MAX_STEPS) {
                        recSteps.add(new MacroPlan.Step("CLICK", fx, fy, b, 0, mods, delay));
                    }
                    recLastMs = now;
                }
                prevButtons[b] = down;
            }
        } else {
            for (int b = 0; b < prevButtons.length; b++) {
                prevButtons[b] = GLFW.glfwGetMouseButton(handle, b) == GLFW.GLFW_PRESS;
            }
        }

        // 键盘：记录按下瞬间，Shift/Ctrl/Alt 作为修饰键附在按键或点击上。
        for (int key = GLFW.GLFW_KEY_SPACE; key <= GLFW.GLFW_KEY_LAST; key++) {
            boolean down = isKeyDown(handle, key);
            if (down && !prevKeys[key] && !isModifierKey(key) && !isMacroHotkey(key)) {
                long delay = Math.max(0, Math.min(MAX_STEP_DELAY, now - recLastMs));
                if (recSteps.size() < MAX_STEPS) {
                    recSteps.add(new MacroPlan.Step("KEY", 0, 0, 0, key, mods, delay));
                }
                recLastMs = now;
            }
            prevKeys[key] = down;
        }

        if (screen == null && !recGlobal) {
            stopRecording();
        }
    }

    private static void stepPlay(Minecraft mc) {
        long now = System.currentTimeMillis();
        if (now < keyGuardUntil) {
            return;
        }
        Screen screen = mc.gui.screen();
        if (screen == null && !playGlobal) {
            stopPlay("§e界面关了，回放停止。");
            return;
        }
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
            if ("CLICK".equals(s.kind()) && mc.gui.screen() == null) {
                if (playGlobal && now < screenWaitUntil) {
                    return;
                }
                stopPlay("§e菜单没打开：Shift+F 没有生效，或服务器菜单加载太慢。");
                return;
            }
            playIndex++;
            try {
                switch (s.kind()) {
                    case "CLICK" -> {
                        Screen cur = mc.gui.screen();
                        if (cur == null) {
                            stopPlay("§e菜单没打开，回放停止。");
                            return;
                        }
                        double gx = s.x() * guiW;
                        double gy = s.y() * guiH;
                        cur.mouseMoved(gx, gy);
                        MouseButtonEvent ev = new MouseButtonEvent(gx, gy,
                                new MouseButtonInfo(s.button(), s.mods()));
                        cur.mouseClicked(ev, false);
                        cur.mouseReleased(ev);
                    }
                    case "KEY" -> {
                        if (!injectKey(mc, s.key(), s.mods())) {
                            stopPlay("§c按键回放失败：系统不允许模拟按键，请保持游戏窗口在前台。");
                            return;
                        }
                        // 给菜单/界面留出响应时间，避免下一步点击太早。
                        keyGuardUntil = now + KEY_GUARD_MS;
                        screenWaitUntil = now + SCREEN_WAIT_MS;
                        return;
                    }
                    default -> {
                    }
                }
            } catch (Throwable t) {
                stopPlay("§c回放出错：" + t.getMessage());
                return;
            }
            if (mc.gui.screen() == null && !playGlobal) {
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

    /** 优先发真实系统按键，这样 Shift+F 能触发服务器/模组的全局快捷键；失败再退回界面事件。 */
    private static boolean injectKey(Minecraft mc, int key, int mods) {
        if (mc.isWindowActive() && sendKeyWithRobot(key, mods)) {
            return true;
        }
        Screen cur = mc.gui.screen();
        if (cur != null) {
            KeyEvent event = new KeyEvent(key, 0, mods);
            cur.keyPressed(event);
            cur.keyReleased(event);
            return true;
        }
        return false;
    }

    private static boolean sendKeyWithRobot(int key, int mods) {
        Robot r = robot();
        int awtKey = toAwtKey(key);
        if (r == null || awtKey == 0) {
            return false;
        }
        boolean shift = (mods & GLFW.GLFW_MOD_SHIFT) != 0;
        boolean control = (mods & GLFW.GLFW_MOD_CONTROL) != 0;
        boolean alt = (mods & GLFW.GLFW_MOD_ALT) != 0;
        boolean meta = (mods & GLFW.GLFW_MOD_SUPER) != 0;
        boolean shiftDown = false;
        boolean controlDown = false;
        boolean altDown = false;
        boolean metaDown = false;
        try {
            if (shift) {
                r.keyPress(java.awt.event.KeyEvent.VK_SHIFT);
                shiftDown = true;
            }
            if (control) {
                r.keyPress(java.awt.event.KeyEvent.VK_CONTROL);
                controlDown = true;
            }
            if (alt) {
                r.keyPress(java.awt.event.KeyEvent.VK_ALT);
                altDown = true;
            }
            if (meta) {
                r.keyPress(java.awt.event.KeyEvent.VK_META);
                metaDown = true;
            }
            r.keyPress(awtKey);
            r.keyRelease(awtKey);
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            try {
                if (metaDown) r.keyRelease(java.awt.event.KeyEvent.VK_META);
                if (altDown) r.keyRelease(java.awt.event.KeyEvent.VK_ALT);
                if (controlDown) r.keyRelease(java.awt.event.KeyEvent.VK_CONTROL);
                if (shiftDown) r.keyRelease(java.awt.event.KeyEvent.VK_SHIFT);
            } catch (Throwable ignored) {
            }
        }
    }

    private static Robot robot() {
        if (!robotTried) {
            robotTried = true;
            try {
                if (!GraphicsEnvironment.isHeadless()) {
                    robot = new Robot();
                }
            } catch (Throwable ignored) {
                robot = null;
            }
        }
        return robot;
    }

    private static int currentMods(long handle) {
        int mods = 0;
        if (isKeyDown(handle, GLFW.GLFW_KEY_LEFT_SHIFT) || isKeyDown(handle, GLFW.GLFW_KEY_RIGHT_SHIFT)) {
            mods |= GLFW.GLFW_MOD_SHIFT;
        }
        if (isKeyDown(handle, GLFW.GLFW_KEY_LEFT_CONTROL) || isKeyDown(handle, GLFW.GLFW_KEY_RIGHT_CONTROL)) {
            mods |= GLFW.GLFW_MOD_CONTROL;
        }
        if (isKeyDown(handle, GLFW.GLFW_KEY_LEFT_ALT) || isKeyDown(handle, GLFW.GLFW_KEY_RIGHT_ALT)) {
            mods |= GLFW.GLFW_MOD_ALT;
        }
        if (isKeyDown(handle, GLFW.GLFW_KEY_LEFT_SUPER) || isKeyDown(handle, GLFW.GLFW_KEY_RIGHT_SUPER)) {
            mods |= GLFW.GLFW_MOD_SUPER;
        }
        return mods;
    }

    private static boolean isKeyDown(long handle, int key) {
        return GLFW.glfwGetKey(handle, key) == GLFW.GLFW_PRESS;
    }

    private static boolean isModifierKey(int key) {
        return key == GLFW.GLFW_KEY_LEFT_SHIFT || key == GLFW.GLFW_KEY_RIGHT_SHIFT
                || key == GLFW.GLFW_KEY_LEFT_CONTROL || key == GLFW.GLFW_KEY_RIGHT_CONTROL
                || key == GLFW.GLFW_KEY_LEFT_ALT || key == GLFW.GLFW_KEY_RIGHT_ALT
                || key == GLFW.GLFW_KEY_LEFT_SUPER || key == GLFW.GLFW_KEY_RIGHT_SUPER;
    }

    private static boolean isMacroHotkey(int key) {
        return key == GLFW.GLFW_KEY_F7 || key == GLFW.GLFW_KEY_F8 || key == GLFW.GLFW_KEY_F9;
    }

    /** GLFW 键码转 AWT 键码；只覆盖常用键，未知键回退到界面事件。 */
    private static int toAwtKey(int key) {
        if (key >= GLFW.GLFW_KEY_A && key <= GLFW.GLFW_KEY_Z) {
            return key;
        }
        if (key >= GLFW.GLFW_KEY_0 && key <= GLFW.GLFW_KEY_9) {
            return key;
        }
        if (key >= GLFW.GLFW_KEY_F1 && key <= GLFW.GLFW_KEY_F12) {
            return java.awt.event.KeyEvent.VK_F1 + (key - GLFW.GLFW_KEY_F1);
        }
        if (key >= GLFW.GLFW_KEY_KP_0 && key <= GLFW.GLFW_KEY_KP_9) {
            return java.awt.event.KeyEvent.VK_NUMPAD0 + (key - GLFW.GLFW_KEY_KP_0);
        }
        return switch (key) {
            case GLFW.GLFW_KEY_SPACE -> java.awt.event.KeyEvent.VK_SPACE;
            case GLFW.GLFW_KEY_ENTER -> java.awt.event.KeyEvent.VK_ENTER;
            case GLFW.GLFW_KEY_TAB -> java.awt.event.KeyEvent.VK_TAB;
            case GLFW.GLFW_KEY_BACKSPACE -> java.awt.event.KeyEvent.VK_BACK_SPACE;
            case GLFW.GLFW_KEY_ESCAPE -> java.awt.event.KeyEvent.VK_ESCAPE;
            case GLFW.GLFW_KEY_INSERT -> java.awt.event.KeyEvent.VK_INSERT;
            case GLFW.GLFW_KEY_DELETE -> java.awt.event.KeyEvent.VK_DELETE;
            case GLFW.GLFW_KEY_HOME -> java.awt.event.KeyEvent.VK_HOME;
            case GLFW.GLFW_KEY_END -> java.awt.event.KeyEvent.VK_END;
            case GLFW.GLFW_KEY_PAGE_UP -> java.awt.event.KeyEvent.VK_PAGE_UP;
            case GLFW.GLFW_KEY_PAGE_DOWN -> java.awt.event.KeyEvent.VK_PAGE_DOWN;
            case GLFW.GLFW_KEY_LEFT -> java.awt.event.KeyEvent.VK_LEFT;
            case GLFW.GLFW_KEY_RIGHT -> java.awt.event.KeyEvent.VK_RIGHT;
            case GLFW.GLFW_KEY_UP -> java.awt.event.KeyEvent.VK_UP;
            case GLFW.GLFW_KEY_DOWN -> java.awt.event.KeyEvent.VK_DOWN;
            case GLFW.GLFW_KEY_MINUS -> java.awt.event.KeyEvent.VK_MINUS;
            case GLFW.GLFW_KEY_EQUAL -> java.awt.event.KeyEvent.VK_EQUALS;
            case GLFW.GLFW_KEY_COMMA -> java.awt.event.KeyEvent.VK_COMMA;
            case GLFW.GLFW_KEY_PERIOD -> java.awt.event.KeyEvent.VK_PERIOD;
            case GLFW.GLFW_KEY_SLASH -> java.awt.event.KeyEvent.VK_SLASH;
            case GLFW.GLFW_KEY_SEMICOLON -> java.awt.event.KeyEvent.VK_SEMICOLON;
            case GLFW.GLFW_KEY_APOSTROPHE -> java.awt.event.KeyEvent.VK_QUOTE;
            case GLFW.GLFW_KEY_GRAVE_ACCENT -> java.awt.event.KeyEvent.VK_BACK_QUOTE;
            case GLFW.GLFW_KEY_LEFT_BRACKET -> java.awt.event.KeyEvent.VK_OPEN_BRACKET;
            case GLFW.GLFW_KEY_RIGHT_BRACKET -> java.awt.event.KeyEvent.VK_CLOSE_BRACKET;
            case GLFW.GLFW_KEY_BACKSLASH -> java.awt.event.KeyEvent.VK_BACK_SLASH;
            case GLFW.GLFW_KEY_KP_DECIMAL -> java.awt.event.KeyEvent.VK_DECIMAL;
            case GLFW.GLFW_KEY_KP_ENTER -> java.awt.event.KeyEvent.VK_ENTER;
            default -> 0;
        };
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
