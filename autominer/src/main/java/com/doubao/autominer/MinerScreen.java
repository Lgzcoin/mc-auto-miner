package com.doubao.autominer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 图形界面：M 键打开，分四页（挖矿种类 / 存矿箱子 / 设置与运行 / 事件脚本）。
 * 只用原版控件，不自己画文字，避免不同渲染后端的兼容问题。
 */
public class MinerScreen extends Screen {
    private static final int ORE_COLUMNS = 2;
    private static final int CHEST_PER_PAGE = 8;

    private int page = 0;
    private int chestPage = 0;

    public MinerScreen() {
        super(Component.literal("自动采矿助手"));
    }

    private MinerConfig config() {
        return AutoMiner.config();
    }

    private MinerController controller() {
        return AutoMiner.controller();
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int footerY = this.height - 26;

        switch (page) {
            case 0 -> initOres(cx);
            case 1 -> initChests(cx);
            case 2 -> initSettings(cx);
            default -> initHooks(cx);
        }

        // 底部翻页
        this.addRenderableWidget(Button.builder(Component.literal("◀ 上一页"), b -> {
            page = (page + 3) % 4;
            refreshLater();
        }).bounds(cx - 150, footerY, 90, 20).build());

        this.addRenderableWidget(new StringWidget(cx - 50, footerY + 6, 100, 10,
                Component.literal("§8" + (page + 1) + " / 4"), this.font));

        this.addRenderableWidget(Button.builder(Component.literal("下一页 ▶"), b -> {
            page = (page + 1) % 4;
            refreshLater();
        }).bounds(cx + 60, footerY, 90, 20).build());

        // 男中音状态提示：哪一页都能看到
        BaritoneHook.Status st = BaritoneHook.status();
        String text = st == BaritoneHook.Status.OK
                ? "§a男中音：已就绪"
                : "§c男中音：" + st.label + "（按 /am diag 看原因）";
        this.addRenderableWidget(new StringWidget(cx - 160, footerY - 12, 320, 10,
                Component.literal(text), this.font));
    }

    // ------------------------------------------------------------ 第一页：矿石

    private void initOres(int cx) {
        var types = OreCatalog.ALL;
        int enabled = OreCatalog.enabledCount(config());
        this.addRenderableWidget(new StringWidget(cx - 200, 12, 400, 20,
                Component.literal("§e挖矿种类  §7（已选 " + enabled + " / " + types.size() + "）"), this.font));

        int btnW = Math.min(170, (this.width / 2) - 25);
        int startX = cx - btnW - 6;
        int gapX = btnW + 12;

        for (int i = 0; i < types.size(); i++) {
            OreCatalog.OreType type = types.get(i);
            int col = i % ORE_COLUMNS;
            int row = i / ORE_COLUMNS;
            int x = startX + col * gapX;
            int y = 40 + row * 22;
            boolean on = OreCatalog.isEnabled(config(), type);
            Component label = Component.literal((on ? "§a✔ §f" : "§7✘ §7") + type.label());
            this.addRenderableWidget(Button.builder(label, b -> {
                OreCatalog.setEnabled(config(), type, !OreCatalog.isEnabled(config(), type));
                AutoMiner.controller().refreshMiningTargets();
                refreshLater();
            }).bounds(x, y, btnW, 20).build());
        }

        int bulkY = Math.min(40 + 6 * 22 + 6, this.height - 26 - 28);
        int smallW = Math.min(100, (this.width - 60) / 3);
        this.addRenderableWidget(Button.builder(Component.literal("全选"), b -> {
            for (OreCatalog.OreType type : types) {
                OreCatalog.setEnabled(config(), type, true);
            }
            AutoMiner.controller().refreshMiningTargets();
            refreshLater();
        }).bounds(cx - smallW - 10 - smallW / 2, bulkY, smallW, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("全不选"), b -> {
            for (OreCatalog.OreType type : types) {
                OreCatalog.setEnabled(config(), type, false);
            }
            AutoMiner.controller().refreshMiningTargets();
            refreshLater();
        }).bounds(cx - smallW / 2, bulkY, smallW, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("默认"), b -> {
            config().mineBlocks.clear();
            config().mineBlocks.addAll(MinerConfig.DEFAULT_ORES);
            config().depositItems.clear();
            config().depositItems.addAll(MinerConfig.DEFAULT_DROPS);
            config().save();
            AutoMiner.controller().refreshMiningTargets();
            refreshLater();
        }).bounds(cx + smallW / 2 + 10, bulkY, smallW, 20).build());
    }

    // ------------------------------------------------------------ 第二页：箱子

    private void initChests(int cx) {
        MinerConfig config = config();
        int total = config.chests.size();
        int rows = Math.max(3, Math.min(CHEST_PER_PAGE, (this.height - 26 - 44 - 30) / 24));
        int pages = Math.max(1, (total + rows - 1) / rows);
        chestPage = Math.min(chestPage, pages - 1);

        this.addRenderableWidget(new StringWidget(cx - 220, 12, 440, 20,
                Component.literal("§e存矿箱子  §7（准星对准箱子按 §fC §7键即可添加）"), this.font));

        int listW = Math.min(320, this.width - 60);
        int listX = cx - listW / 2;
        int start = chestPage * rows;
        int shown = Math.min(rows, total - start);

        if (total == 0) {
            this.addRenderableWidget(new StringWidget(cx - 220, 56, 440, 20,
                    Component.literal("§7还没有箱子：先用准星对着箱子，再按 §fC §7键"), this.font));
        }

        for (int i = 0; i < shown; i++) {
            MinerConfig.ChestEntry entry = config.chests.get(start + i);
            int y = 44 + i * 24;
            this.addRenderableWidget(Button.builder(
                    Component.literal("§c✖ §f" + entry.name + " §7(" + entry.x + ", " + entry.y + ", " + entry.z + ")"),
                    b -> {
                        config.chests.remove(entry);
                        config.save();
                        refreshLater();
                    }).bounds(listX, y, listW, 20).build());
        }

        int rowY = 44 + rows * 24 - 4;
        this.addRenderableWidget(Button.builder(Component.literal("添加准星箱子"), b -> {
            ChestPicker.addLastTarget(true);
            refreshLater();
        }).bounds(cx - 160, rowY, 150, 20).build());

        Button prev = Button.builder(Component.literal("◀"), b -> {
            chestPage = Math.max(0, chestPage - 1);
            refreshLater();
        }).bounds(cx - 5, rowY, 40, 20).build();
        prev.active = chestPage > 0;
        this.addRenderableWidget(prev);

        Button next = Button.builder(Component.literal("▶"), b -> {
            chestPage = Math.min(pages - 1, chestPage + 1);
            refreshLater();
        }).bounds(cx + 40, rowY, 40, 20).build();
        next.active = chestPage < pages - 1;
        this.addRenderableWidget(next);

        this.addRenderableWidget(new StringWidget(cx + 90, rowY + 6, 90, 10,
                Component.literal("§8第 " + (chestPage + 1) + "/" + pages + " 页"), this.font));
    }

    // ------------------------------------------------------------ 第三页：设置

    private void initSettings(int cx) {
        MinerConfig config = config();
        MinerController controller = controller();

        this.addRenderableWidget(new StringWidget(cx - 220, 12, 440, 20,
                Component.literal("§e运行设置（数值都直接输入）"), this.font));

        int labelW = Math.min(420, this.width - 40);
        int labelX = cx - labelW / 2;

        // 定时回箱：直接输入秒数
        this.addRenderableWidget(new StringWidget(labelX, 34, labelW, 20,
                Component.literal("定时回箱（秒，0 = 关闭）：§7到点就回箱存一次"), this.font));
        EditBox intervalBox = new EditBox(this.font, cx - 60, 54, 120, 18, Component.literal("900"));
        intervalBox.setMaxLength(6);
        intervalBox.setValue(String.valueOf(config.intervalSeconds));
        intervalBox.setResponder(text -> {
            int value = parseInt(text, config.intervalSeconds);
            config.intervalSeconds = Math.max(0, Math.min(86400, value));
            config.save();
        });
        this.addRenderableWidget(intervalBox);

        // 攒够回箱：矿物 id + 数量
        this.addRenderableWidget(new StringWidget(labelX, 82, labelW, 20,
                Component.literal("攒够回箱：矿物 id 留空 = 关闭（例 diamond / raw_iron）"), this.font));
        EditBox itemBox = new EditBox(this.font, cx - 210, 102, 280, 18, Component.literal("diamond"));
        itemBox.setMaxLength(64);
        itemBox.setValue(config.depositTriggerItem == null ? "" : config.depositTriggerItem);
        itemBox.setResponder(text -> {
            String raw = text.trim().toLowerCase();
            if (raw.isEmpty()) {
                config.depositTriggerItem = "";
            } else {
                config.depositTriggerItem = raw.contains(":") ? raw : "minecraft:" + raw;
            }
            config.save();
        });
        this.addRenderableWidget(itemBox);

        EditBox countBox = new EditBox(this.font, cx + 80, 102, 130, 18, Component.literal("64"));
        countBox.setMaxLength(4);
        countBox.setValue(String.valueOf(config.depositTriggerCount));
        countBox.setResponder(text -> {
            int value = parseInt(text, config.depositTriggerCount);
            config.depositTriggerCount = Math.max(1, Math.min(3456, value));
            config.save();
        });
        this.addRenderableWidget(countBox);

        // 开关
        this.addRenderableWidget(Button.builder(
                Component.literal("全部入库：" + (config.depositAll ? "§a开" : "§7关")),
                b -> {
                    config.depositAll = !config.depositAll;
                    config.save();
                    refreshLater();
                }).bounds(cx - 205, 134, 130, 20).build());

        this.addRenderableWidget(Button.builder(
                Component.literal("进服自动开始：" + (config.autoStartOnJoin ? "§a开" : "§7关")),
                b -> {
                    config.autoStartOnJoin = !config.autoStartOnJoin;
                    config.save();
                    refreshLater();
                }).bounds(cx - 65, 134, 160, 20).build());

        // 开始 / 停止
        this.addRenderableWidget(Button.builder(
                Component.literal(config.enabled ? "§c停止自动采矿" : "§a开始自动采矿"),
                b -> {
                    if (config.enabled) {
                        controller().stop(false);
                    } else {
                        controller().start();
                    }
                    refreshLater();
                }).bounds(cx - 100, 162, 200, 20).build());

        // 状态
        int free = 0;
        var player = Minecraft.getInstance().player;
        if (player != null) {
            for (int slot = 0; slot < 36; slot++) {
                if (player.getInventory().getItem(slot).isEmpty()) {
                    free++;
                }
            }
        }
        String status = "状态：§f" + controller().state().label()
                + "  §7背包空位：§f" + free + "/36"
                + "\n§7回箱条件：§f" + controller().depositTriggerLabel()
                + "  §7箱子：§f" + config.chests.size() + " 个";
        this.addRenderableWidget(new MultiLineTextWidget(cx - labelW / 2, 192,
                Component.literal(status), this.font).setMaxWidth(labelW).setMaxRows(2));
    }

    // ------------------------------------------------------------ 第四页：事件脚本

    private void initHooks(int cx) {
        MinerConfig config = config();
        this.addRenderableWidget(new StringWidget(cx - 210, 8, 420, 18,
                Component.literal("§e事件脚本  §7（每个事件独立开关；点“编辑”配指令/脚本）"), this.font));

        int rowY = 28;
        for (HookEvent event : HookEvent.values()) {
            MinerConfig.Hook hook = config.hookOrCreate(event);
            String mark = hook.enabled ? "§a开" : "§7关";
            String count = hook.commands == null || hook.commands.isEmpty()
                    ? "无指令" : (hook.commands.size() + " 条");
            String script = hook.script == null || hook.script.isBlank() ? "" : " §8+脚本";
            this.addRenderableWidget(new StringWidget(cx - 205, rowY + 5, 180, 12,
                    Component.literal("§f" + event.label + " §7" + mark + " §8" + count + script), this.font));

            this.addRenderableWidget(Button.builder(
                    Component.literal(hook.enabled ? "§a开" : "§7关"),
                    b -> {
                        hook.enabled = !hook.enabled;
                        config.save();
                        refreshLater();
                    }).bounds(cx + 15, rowY, 48, 17).build());

            this.addRenderableWidget(Button.builder(
                    Component.literal("编辑"),
                    b -> {
                        Minecraft mc = Minecraft.getInstance();
                        mc.gui.setScreen(new HookEditScreen(this, event));
                    }).bounds(cx + 69, rowY, 48, 17).build());
            rowY += 17;
        }
        this.addRenderableWidget(new StringWidget(cx - 205, rowY + 2, 410, 12,
                Component.literal("§7执行顺序举例：爆仓 → 爆仓事件 → 回箱前 → 走向箱子 → 回箱后 → 继续挖矿"), this.font));
    }

    private static int parseInt(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void refreshLater() {
        Minecraft mc = this.minecraft;
        mc.execute(() -> {
            if (this.minecraft.gui.screen() == this) {
                this.rebuildWidgets();
            }
        });
    }

    @Override
    public void onClose() {
        super.onClose();
    }
}
