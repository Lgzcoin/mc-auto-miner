package com.doubao.autominer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 图形登录（第三方菜单）自动化：
 * 进服弹出容器菜单 → 自动找带“登录/进入/确认/密码”字样的格子点 → 关掉后发一句聊天密码；
 * 如果点出来的是铁砧界面，就把密码写进铁砧并确认。
 * clickSlot >= 0 时按老办法点固定格子；clickSlot = -1 时按 slotKeyword 自动挑。
 */
public final class GuiLogin {
    private enum State { WAIT, CLICKED, DONE }

    private static State state = State.WAIT;
    private static int cd = 0;
    private static Object lastConnection = null;
    private static final Set<Integer> tried = new HashSet<>();
    private static int attempts = 0;
    private static String lastWarnedTitle = "";

    private GuiLogin() {
    }

    public static void reset() {
        state = State.WAIT;
        cd = 0;
        tried.clear();
        attempts = 0;
        lastWarnedTitle = "";
    }

    public static void tick(Minecraft mc) {
        MinerConfig cfg = AutoMiner.config();
        if (cfg == null || cfg.guiLogin == null || !cfg.guiLogin.enabled) {
            return;
        }
        if (mc.player == null) {
            state = State.WAIT;
            lastConnection = null;
            return;
        }
        if (mc.player.connection != lastConnection) {
            lastConnection = mc.player.connection;
            reset();
        }
        if (state == State.DONE) {
            return;
        }
        Screen screen = mc.gui.screen();
        if (state == State.WAIT) {
            if (!(screen instanceof AbstractContainerScreen<?> cs)) {
                return;
            }
            String text = titleOf(cs);
            if (!matches(text, cfg.guiLogin.titleKeyword)) {
                return;
            }
            if (cd <= 0 && attempts == 0) {
                cd = Math.max(1, cfg.guiLogin.delaySeconds * 20);
            }
            if (cd > 0) {
                cd--;
                return;
            }
            int slot = pickSlot(cs, cfg.guiLogin);
            if (slot < 0) {
                if (lastWarnedTitle != text) {
                    lastWarnedTitle = text;
                    AutoMiner.LOGGER.info("登录菜单“{}”里没找到带“{}”字样的格子；用 gui dump 看内容，或用 gui set 指定格子", text,
                            cfg.guiLogin.slotKeyword == null ? "" : cfg.guiLogin.slotKeyword);
                }
                state = State.DONE;
                return;
            }
            try {
                mc.gameMode.handleContainerInput(cs.getMenu().containerId, slot, 0, ContainerInput.PICKUP, mc.player);
                tried.add(slot);
                attempts++;
                AutoMiner.LOGGER.info("已点击登录菜单“{}”第 {} 格（第 {} 次）", text, slot, attempts);
            } catch (Throwable t) {
                AutoMiner.LOGGER.warn("点击菜单失败", t);
                state = State.DONE;
                return;
            }
            state = State.CLICKED;
            cd = 40;
            return;
        }
        // CLICKED：等铁砧，或者菜单关了之后发聊天密码
        if (screen instanceof AnvilScreen anvil) {
            if (typeIntoAnvil(mc, anvil, cfg.guiLogin.chatText)) {
                state = State.DONE;
            }
            return;
        }
        if (cd > 0) {
            cd--;
            return;
        }
        // 菜单还在（点了没反应/点出下一层菜单）：换一个格子再试，最多 maxTries 次
        if (screen instanceof AbstractContainerScreen<?> cs && matches(titleOf(cs), cfg.guiLogin.titleKeyword)
                && attempts < Math.max(1, cfg.guiLogin.maxTries)) {
            state = State.WAIT;
            cd = 20;
            return;
        }
        String chat = cfg.guiLogin.chatText;
        if (chat != null && !chat.isBlank() && mc.player.connection != null) {
            try {
                mc.player.connection.sendChat(chat);
                AutoMiner.LOGGER.info("已发送菜单密码（内容不记录）");
            } catch (Throwable t) {
                AutoMiner.LOGGER.warn("发送菜单密码失败", t);
            }
        }
        state = State.DONE;
    }

    private static String titleOf(AbstractContainerScreen<?> cs) {
        Component title = cs.getTitle();
        return title == null ? "" : title.getString();
    }

    private static boolean matches(String title, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return true;
        }
        return title != null && title.contains(keyword);
    }

    /** 按物品名字找“登录/进入/确认/密码”这类的格子；找不到再退回固定的 clickSlot。 */
    private static int pickSlot(AbstractContainerScreen<?> cs, MinerConfig.GuiLogin g) {
        int n = cs.getMenu().slots.size();
        List<String> keys = splitKeywords(g.slotKeyword);
        if (!keys.isEmpty()) {
            for (int i = 0; i < n; i++) {
                if (tried.contains(i)) {
                    continue;
                }
                ItemStack st = cs.getMenu().slots.get(i).getItem();
                if (st == null || st.isEmpty()) {
                    continue;
                }
                String name = st.getHoverName().getString();
                String low = name.toLowerCase(Locale.ROOT);
                for (String k : keys) {
                    if (low.contains(k)) {
                        return i;
                    }
                }
            }
        }
        int fixed = g.clickSlot;
        if (fixed >= 0 && fixed < n && !tried.contains(fixed)) {
            return fixed;
        }
        return -1;
    }

    private static List<String> splitKeywords(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String s : raw.split("[,，;；|/\\s]+")) {
            if (!s.isBlank()) {
                out.add(s.trim().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    /** 把文字填进铁砧，再点结果格提交。 */
    private static boolean typeIntoAnvil(Minecraft mc, AnvilScreen anvil, String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        try {
            Field f = AnvilScreen.class.getDeclaredField("name");
            f.setAccessible(true);
            Object box = f.get(anvil);
            if (box instanceof net.minecraft.client.gui.components.EditBox eb) {
                eb.setValue(text);
            } else {
                return false;
            }
            mc.gameMode.handleContainerInput(anvil.getMenu().containerId, 2, 0, ContainerInput.PICKUP, mc.player);
            AutoMiner.LOGGER.info("已在铁砧里填密码并提交");
            return true;
        } catch (Throwable t) {
            AutoMiner.LOGGER.warn("铁砧填密码失败：{}", t.toString());
            return false;
        }
    }

    /** 把当前打开的菜单导出来，方便挑格子。 */
    public static String dump(Minecraft mc) {
        Screen screen = mc.gui.screen();
        if (!(screen instanceof AbstractContainerScreen<?> cs)) {
            return "现在没有打开任何菜单。先让游戏停在登录菜单上，再执行 gui dump。";
        }
        StringBuilder sb = new StringBuilder();
        Component title = cs.getTitle();
        sb.append("菜单标题：").append(title == null ? "" : title.getString()).append("\n");
        int n = cs.getMenu().slots.size();
        sb.append("共 ").append(n).append(" 格：\n");
        for (int i = 0; i < n; i++) {
            ItemStack st = cs.getMenu().slots.get(i).getItem();
            if (st.isEmpty()) {
                continue;
            }
            sb.append("  第 ").append(i).append(" 格 = ").append(st.getItem().toString());
            String name = st.getHoverName().getString();
            if (name != null && !name.isBlank()) {
                sb.append(" “").append(name).append("”");
            }
            sb.append("\n");
        }
        sb.append("现在一般不用挑了：自动模式会自己找带“登录/进入/确认/密码”的格子。\n");
        sb.append("要写死就：gui set <标题关键词> <格子号> <密码>");
        return sb.toString();
    }
}
