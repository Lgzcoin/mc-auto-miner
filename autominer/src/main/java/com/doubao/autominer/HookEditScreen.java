package com.doubao.autominer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 单个事件的图形化动作编辑器：勾选宏 / 指令 / 宏+指令。
 */
public class HookEditScreen extends Screen {
    private final Screen parent;
    private final HookEvent event;
    private final MinerConfig config;
    private final MinerConfig.Hook hook;
    private final List<String> macroNames = new ArrayList<>();
    private int macroIndex = 0;

    public HookEditScreen(Screen parent, HookEvent event) {
        super(Component.literal("事件动作 · " + event.label));
        this.parent = parent;
        this.event = event;
        this.config = AutoMiner.config();
        this.hook = config.hookOrCreate(event);
        if (hook.action == null || hook.action.isBlank()) {
            boolean hasMacro = hook.macro != null && !hook.macro.isBlank();
            boolean hasCommands = hook.commands != null && !hook.commands.isEmpty();
            hook.action = hasMacro && hasCommands ? "both" : (hasMacro ? "macro" : "command");
        }
        macroNames.addAll(MacroManager.listNames());
        if (hook.macro != null && !hook.macro.isBlank() && !macroNames.contains(hook.macro)) {
            macroNames.add(0, hook.macro);
        }
        macroIndex = Math.max(0, macroNames.indexOf(hook.macro == null ? "" : hook.macro));
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int left = 20;
        int width = this.width - 40;

        this.addRenderableWidget(new StringWidget(left, 4, width, 12,
                Component.literal("§e" + event.label + "  §7" + event.description), this.font));

        this.addRenderableWidget(Button.builder(
                Component.literal("启用：" + (hook.enabled ? "§a开" : "§7关")),
                b -> {
                    hook.enabled = !hook.enabled;
                    config.save();
                    rebuild();
                }).bounds(cx - 70, 20, 140, 16).build());

        this.addRenderableWidget(new StringWidget(left, 40, width, 10,
                Component.literal("§7执行动作（勾选一种；宏和指令都有时，先宏后指令）"), this.font));

        int bw = 72;
        int gap = 7;
        int bx = left;
        this.addRenderableWidget(actionButton("macro", "宏", bx, 52, bw));
        bx += bw + gap;
        this.addRenderableWidget(actionButton("command", "指令", bx, 52, bw));
        bx += bw + gap;
        this.addRenderableWidget(actionButton("both", "宏+指令", bx, 52, bw));

        this.addRenderableWidget(new StringWidget(left, 74, width, 10,
                Component.literal("§7宏（点击左右切换；宏文件在 宏/<名字>.txt）"), this.font));
        this.addRenderableWidget(Button.builder(Component.literal("◀"), b -> cycleMacro(-1))
                .bounds(left, 86, 24, 16).build());
        this.addRenderableWidget(Button.builder(Component.literal(macroLabel()), b -> cycleMacro(1))
                .bounds(left + 28, 86, Math.min(260, width - 56), 16).build());
        this.addRenderableWidget(Button.builder(Component.literal("▶"), b -> cycleMacro(1))
                .bounds(left + 28 + Math.min(260, width - 56) + 4, 86, 24, 16).build());
        if (macroNames.isEmpty()) {
            this.addRenderableWidget(new StringWidget(left + 300, 89, 260, 10,
                    Component.literal("§8没有宏，先按 F7 录一个"), this.font));
        }

        this.addRenderableWidget(new StringWidget(left, 108, width, 10,
                Component.literal("§7游戏指令（用 | 分隔；宏模式下不执行；例：home|kit miner）"), this.font));
        EditBox commandBox = new EditBox(this.font, left, 120, width, 16, Component.literal("home|kit miner"));
        commandBox.setMaxLength(512);
        commandBox.setValue(String.join("|", hook.commands));
        commandBox.setEditable(!"macro".equals(hook.action));
        commandBox.setResponder(text -> {
            hook.commands.clear();
            for (String part : text.split("\\|")) {
                String cmd = part.trim();
                if (!cmd.isEmpty()) {
                    hook.commands.add(cmd);
                }
            }
            config.save();
        });
        this.addRenderableWidget(commandBox);

        this.addRenderableWidget(new StringWidget(left, 142, width, 10,
                Component.literal("§7脚本文件（放 config 目录；指令类，宏模式下不读；空 = 不读）"), this.font));
        EditBox scriptBox = new EditBox(this.font, left, 154, Math.min(300, width), 16,
                Component.literal("hook_home.txt"));
        scriptBox.setMaxLength(128);
        scriptBox.setValue(hook.script == null ? "" : hook.script);
        scriptBox.setEditable(!"macro".equals(hook.action));
        scriptBox.setResponder(text -> {
            hook.script = text.trim();
            config.save();
        });
        this.addRenderableWidget(scriptBox);

        this.addRenderableWidget(new StringWidget(left, 176, 170, 10,
                Component.literal("§7指令间隔（tick，20=1秒）"), this.font));
        EditBox delayBox = new EditBox(this.font, left, 188, 70, 16, Component.literal("10"));
        delayBox.setMaxLength(3);
        delayBox.setValue(String.valueOf(hook.commandDelayTicks));
        delayBox.setResponder(text -> {
            hook.commandDelayTicks = clamp(parse(text, hook.commandDelayTicks), 0, 200);
            config.save();
        });
        this.addRenderableWidget(delayBox);

        this.addRenderableWidget(new StringWidget(left + 100, 176, 170, 10,
                Component.literal("§7执行完再等（tick）"), this.font));
        EditBox settleBox = new EditBox(this.font, left + 100, 188, 70, 16, Component.literal("20"));
        settleBox.setMaxLength(3);
        settleBox.setValue(String.valueOf(hook.settleTicks));
        settleBox.setResponder(text -> {
            hook.settleTicks = clamp(parse(text, hook.settleTicks), 0, 600);
            config.save();
        });
        this.addRenderableWidget(settleBox);

        this.addRenderableWidget(Button.builder(Component.literal("清空这个事件"), b -> {
            hook.commands.clear();
            hook.macro = "";
            hook.script = "";
            hook.action = "command";
            config.save();
            rebuild();
        }).bounds(cx - 155, 210, 100, 18).build());

        this.addRenderableWidget(Button.builder(Component.literal("返回"), b -> onClose())
                .bounds(cx + 55, 210, 100, 18).build());

        this.addRenderableWidget(new StringWidget(left, 230, width, 10,
                Component.literal("§7改动即时保存；勾选宏后只跑宏，勾选指令后只发指令。"), this.font));
    }

    private Button actionButton(String action, String label, int x, int y, int width) {
        boolean on = action.equals(hook.action);
        return Button.builder(Component.literal((on ? "§a[✓] " : "§7[ ] ") + label),
                b -> {
                    hook.action = action;
                    config.save();
                    rebuild();
                }).bounds(x, y, width, 16).build();
    }

    private String macroLabel() {
        if (hook.macro == null || hook.macro.isBlank()) {
            return "§8无宏（点这里选）";
        }
        String name = hook.macro;
        return "§b" + (name.length() > 28 ? name.substring(0, 28) + "…" : name);
    }

    private void cycleMacro(int delta) {
        if (macroNames.isEmpty()) {
            return;
        }
        macroIndex = Math.floorMod(macroIndex + delta, macroNames.size());
        hook.macro = macroNames.get(macroIndex);
        if (!"macro".equals(hook.action) && !"both".equals(hook.action)) {
            hook.action = "macro";
        }
        config.save();
        rebuild();
    }

    private void rebuild() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.gui.screen() == this) {
                this.rebuildWidgets();
            }
        });
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int parse(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Override
    public void onClose() {
        Minecraft mc = Minecraft.getInstance();
        mc.gui.setScreen(parent);
    }
}
