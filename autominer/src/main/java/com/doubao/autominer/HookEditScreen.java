package com.doubao.autominer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 单个事件的指令/脚本编辑器。改动即时写入 autominer.json。
 */
public class HookEditScreen extends Screen {
    private final Screen parent;
    private final HookEvent event;
    private final MinerConfig config;
    private final MinerConfig.Hook hook;

    public HookEditScreen(Screen parent, HookEvent event) {
        super(Component.literal("事件脚本 · " + event.label));
        this.parent = parent;
        this.event = event;
        this.config = AutoMiner.config();
        this.hook = config.hookOrCreate(event);
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int left = 20;
        int width = this.width - 40;

        this.addRenderableWidget(new StringWidget(left, 10, width, 18,
                Component.literal("§e" + event.label + "  §7" + event.description), this.font));

        this.addRenderableWidget(Button.builder(
                Component.literal("启用：" + (hook.enabled ? "§a开" : "§7关")),
                b -> {
                    hook.enabled = !hook.enabled;
                    config.save();
                    rebuild();
                }).bounds(cx - 70, 32, 140, 18).build());

        this.addRenderableWidget(new StringWidget(left, 58, width, 14,
                Component.literal("游戏指令（用 | 分隔，按顺序发；不用写开头 /；例：/home|/kit miner）"), this.font));
        EditBox commandBox = new EditBox(this.font, left, 74, width, 18, Component.literal("/home|/kit miner"));
        commandBox.setMaxLength(512);
        commandBox.setValue(String.join("|", hook.commands));
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

        this.addRenderableWidget(new StringWidget(left, 100, width, 14,
                Component.literal("脚本文件（放 config 目录；一行一条，也可 | 分隔；空 = 不读）"), this.font));
        EditBox scriptBox = new EditBox(this.font, left, 116, Math.min(260, width), 18,
                Component.literal("例如 hook_home.txt"));
        scriptBox.setMaxLength(128);
        scriptBox.setValue(hook.script == null ? "" : hook.script);
        scriptBox.setResponder(text -> {
            hook.script = text.trim();
            config.save();
        });
        this.addRenderableWidget(scriptBox);

        this.addRenderableWidget(new StringWidget(left, 142, 170, 14,
                Component.literal("指令间隔（tick，20=1秒）"), this.font));
        EditBox delayBox = new EditBox(this.font, left, 158, 80, 18, Component.literal("10"));
        delayBox.setMaxLength(3);
        delayBox.setValue(String.valueOf(hook.commandDelayTicks));
        delayBox.setResponder(text -> {
            hook.commandDelayTicks = clamp(parse(text, hook.commandDelayTicks), 0, 200);
            config.save();
        });
        this.addRenderableWidget(delayBox);

        this.addRenderableWidget(new StringWidget(left + 105, 142, 170, 14,
                Component.literal("执行完再等（tick）"), this.font));
        EditBox settleBox = new EditBox(this.font, left + 105, 158, 80, 18, Component.literal("20"));
        settleBox.setMaxLength(3);
        settleBox.setValue(String.valueOf(hook.settleTicks));
        settleBox.setResponder(text -> {
            hook.settleTicks = clamp(parse(text, hook.settleTicks), 0, 600);
            config.save();
        });
        this.addRenderableWidget(settleBox);

        this.addRenderableWidget(Button.builder(Component.literal("清空这个事件"), b -> {
            hook.commands.clear();
            hook.script = "";
            config.save();
            rebuild();
        }).bounds(cx - 155, 190, 100, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("返回"), b -> onClose())
                .bounds(cx + 55, 190, 100, 20).build());

        this.addRenderableWidget(new StringWidget(left, 214, width, 12,
                Component.literal("§7改动即时保存；脚本只发游戏指令，不运行 .bat/.sh。"), this.font));
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
