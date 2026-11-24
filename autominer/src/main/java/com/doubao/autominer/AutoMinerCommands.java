package com.doubao.autominer;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 客户端命令：/autominer 或 /am
 */
public final class AutoMinerCommands {
    private AutoMinerCommands() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            // 两个入口各自注册一棵完整的命令树，避免 redirect 在某些客户端命令分发里出问题
            dispatcher.register(buildTree("autominer"));
            dispatcher.register(buildTree("am"));
        });
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> buildTree(String name) {
        return ClientCommands.literal(name)
                .then(ClientCommands.literal("start")
                        .executes(ctx -> {
                            AutoMiner.controller().start();
                            return 1;
                        }))
                .then(ClientCommands.literal("stop")
                        .executes(ctx -> {
                            AutoMiner.controller().stop(false);
                            return 1;
                        }))
                .then(ClientCommands.literal("status")
                        .executes(AutoMinerCommands::status))
                .then(ClientCommands.literal("get")
                        .then(ClientCommands.argument("thing", StringArgumentType.word())
                                .executes(ctx -> runGet(ctx, 1))
                                .then(ClientCommands.argument("count", IntegerArgumentType.integer(1, 3456))
                                        .executes(ctx -> runGet(ctx, IntegerArgumentType.getInteger(ctx, "count"))))))
                .then(ClientCommands.literal("diag")
                        .executes(ctx -> {
                            reply(ctx, BaritoneHook.diagnose());
                            return 1;
                        }))
                .then(ClientCommands.literal("reload")
                        .executes(ctx -> {
                            AutoMiner.reloadConfig();
                            reply(ctx, "配置已从 autominer.json 重新载入。");
                            return 1;
                        }))
                .then(ClientCommands.literal("base")
                        .executes(AutoMinerCommands::setBaseHere)
                        .then(ClientCommands.literal("off")
                                .executes(ctx -> {
                                    AutoMiner.config().startPoint.clear();
                                    AutoMiner.config().save();
                                    reply(ctx, "已取消开荒点，进服后原地开挖。");
                                    return 1;
                                }))
                        .then(ClientCommands.argument("x", IntegerArgumentType.integer())
                                .then(ClientCommands.argument("y", IntegerArgumentType.integer())
                                        .then(ClientCommands.argument("z", IntegerArgumentType.integer())
                                                .executes(ctx -> {
                                                    setBase(IntegerArgumentType.getInteger(ctx, "x"),
                                                            IntegerArgumentType.getInteger(ctx, "y"),
                                                            IntegerArgumentType.getInteger(ctx, "z"));
                                                    reply(ctx, "开荒点已设为 " + AutoMiner.config().startPoint.get(0) + ","
                                                            + AutoMiner.config().startPoint.get(1) + ","
                                                            + AutoMiner.config().startPoint.get(2)
                                                            + "：进服后先走过去，到了再开挖。");
                                                    return 1;
                                                })))))
                .then(ClientCommands.literal("trigger")
                        .then(ClientCommands.literal("off")
                                .executes(ctx -> {
                                    MinerConfig config = AutoMiner.config();
                                    config.depositTriggerItem = "";
                                    config.save();
                                    reply(ctx, "已关闭“攒够多少矿物回箱”，只在背包快满时回箱。");
                                    return 1;
                                }))
                        .then(ClientCommands.argument("item", StringArgumentType.word())
                                .then(ClientCommands.argument("count", IntegerArgumentType.integer(1, 3456))
                                        .executes(ctx -> {
                                            MinerConfig config = AutoMiner.config();
                                            config.depositTriggerItem = normalize(StringArgumentType.getString(ctx, "item"));
                                            config.depositTriggerCount = IntegerArgumentType.getInteger(ctx, "count");
                                            config.save();
                                            reply(ctx, "攒够 " + config.depositTriggerCount + " 个 "
                                                    + config.depositTriggerItem + " 就回箱一次。");
                                            return 1;
                                        }))))
                .then(ClientCommands.literal("interval")
                        .then(ClientCommands.argument("seconds", IntegerArgumentType.integer(0, 86400))
                                .executes(ctx -> {
                                    MinerConfig config = AutoMiner.config();
                                    config.intervalSeconds = IntegerArgumentType.getInteger(ctx, "seconds");
                                    config.save();
                                    reply(ctx, config.intervalSeconds == 0
                                            ? "已关闭定时回箱。"
                                            : "每 " + config.intervalSeconds + " 秒回箱一次。");
                                    return 1;
                                })))
                .then(ClientCommands.literal("depositall")
                        .executes(ctx -> {
                            MinerConfig config = AutoMiner.config();
                            config.depositAll = !config.depositAll;
                            config.save();
                            reply(ctx, config.depositAll
                                    ? "已开启“全部入库”：除工具装备和保留物品外都存进箱子。"
                                    : "已关闭“全部入库”：只存 depositItems 里的物品。");
                            return 1;
                        }))
                .then(ClientCommands.literal("chest")
                        .then(ClientCommands.literal("add")
                                .then(ClientCommands.argument("name", StringArgumentType.greedyString())
                                        .executes(AutoMinerCommands::addChest)))
                        .then(ClientCommands.literal("list")
                                .executes(AutoMinerCommands::listChests))
                        .then(ClientCommands.literal("remove")
                                .then(ClientCommands.argument("name", StringArgumentType.greedyString())
                                        .executes(AutoMinerCommands::removeChest)))
                        .then(ClientCommands.literal("clear")
                                .executes(ctx -> {
                                    MinerConfig config = AutoMiner.config();
                                    config.chests.clear();
                                    config.save();
                                    reply(ctx, "已清空箱子列表。");
                                    return 1;
                                })))
                .then(ClientCommands.literal("ores")
                        .then(ClientCommands.literal("add")
                                .then(ClientCommands.argument("block", StringArgumentType.word())
                                        .executes(ctx -> editList(ctx, true, "block"))))
                        .then(ClientCommands.literal("remove")
                                .then(ClientCommands.argument("block", StringArgumentType.word())
                                        .executes(ctx -> editList(ctx, false, "block"))))
                        .then(ClientCommands.literal("list")
                                .executes(ctx -> showList(ctx, "block")))
                        .then(ClientCommands.literal("default")
                                .executes(ctx -> {
                                    MinerConfig config = AutoMiner.config();
                                    config.mineBlocks.clear();
                                    config.mineBlocks.addAll(MinerConfig.DEFAULT_ORES);
                                    config.save();
                                    reply(ctx, "已恢复默认矿石清单（" + config.mineBlocks.size() + " 种）。");
                                    return 1;
                                })))
                .then(ClientCommands.literal("items")
                        .then(ClientCommands.literal("add")
                                .then(ClientCommands.argument("item", StringArgumentType.word())
                                        .executes(ctx -> editList(ctx, true, "item"))))
                        .then(ClientCommands.literal("remove")
                                .then(ClientCommands.argument("item", StringArgumentType.word())
                                        .executes(ctx -> editList(ctx, false, "item")))
                        )
                        .then(ClientCommands.literal("list")
                                .executes(ctx -> showList(ctx, "item")))
                        .then(ClientCommands.literal("default")
                                .executes(ctx -> {
                                    MinerConfig config = AutoMiner.config();
                                    config.depositItems.clear();
                                    config.depositItems.addAll(MinerConfig.DEFAULT_DROPS);
                                    config.save();
                                    reply(ctx, "已恢复默认可存物品清单（" + config.depositItems.size() + " 项）。");
                                    return 1;
                                }))
                        .then(ClientCommands.literal("keep")
                                .then(ClientCommands.argument("item", StringArgumentType.word())
                                        .executes(ctx -> {
                                            MinerConfig config = AutoMiner.config();
                                            String id = normalize(StringArgumentType.getString(ctx, "item"));
                                            if (config.keepItems.contains(id)) {
                                                config.keepItems.remove(id);
                                                reply(ctx, "不再保留 " + id);
                                            } else {
                                                config.keepItems.add(id);
                                                reply(ctx, "已加入保留清单：" + id);
                                            }
                                            config.save();
                                            return 1;
                                        }))))
                .then(ClientCommands.literal("range")
                        .then(ClientCommands.argument("blocks", IntegerArgumentType.integer(1, 6))
                                .executes(ctx -> {
                                    MinerConfig config = AutoMiner.config();
                                    config.returnRange = IntegerArgumentType.getInteger(ctx, "blocks");
                                    config.save();
                                    reply(ctx, "到箱子旁边的判定半径：" + config.returnRange + " 格。");
                                    return 1;
                                })));
    }

    // ---------------------------------------------------------------- 子命令

    private static int runGet(CommandContext<FabricClientCommandSource> ctx, int count) {
        String raw = StringArgumentType.getString(ctx, "thing").trim();
        AutoMiner.controller().startGet(normalize(raw), count);
        return 1;
    }

    private static int status(CommandContext<FabricClientCommandSource> ctx) {
        MinerConfig config = AutoMiner.config();
        MinerController controller = AutoMiner.controller();
        StringBuilder builder = new StringBuilder();
        builder.append("状态：").append(controller.state().label());
        builder.append("\n运行开关：").append(config.enabled ? "开" : "关");
        builder.append("\n矿石：").append(config.mineBlocks.size()).append(" 种");
        builder.append("\n箱子：").append(config.chests.size()).append(" 个");
        builder.append("\n回箱条件：").append(controller.depositTriggerLabel());
        builder.append("\n定时回箱：").append(config.intervalSeconds == 0 ? "关闭" : config.intervalSeconds + " 秒");
        builder.append("\n全部入库：").append(config.depositAll ? "开" : "关");
        reply(ctx, builder.toString());
        return 1;
    }

    private static int addChest(CommandContext<FabricClientCommandSource> ctx) {
        String name = StringArgumentType.getString(ctx, "name").trim();
        if (name.isEmpty()) {
            reply(ctx, "箱子名字不能为空。");
            return 0;
        }
        if (!(ctx.getSource().getClient().hitResult instanceof BlockHitResult hit)) {
            reply(ctx, "请先用准星对着箱子，再执行这条命令。");
            return 0;
        }
        BlockPos pos = hit.getBlockPos();
        BlockEntity blockEntity = ctx.getSource().getLevel().getBlockEntity(pos);
        if (!(blockEntity instanceof Container)) {
            reply(ctx, "准星对着的方块不是容器（箱子/木桶/潜影盒等）。");
            return 0;
        }
        MinerConfig config = AutoMiner.config();
        MinerConfig.ChestEntry existing = config.chestByName(name);
        if (existing != null) {
            existing.x = pos.getX();
            existing.y = pos.getY();
            existing.z = pos.getZ();
            config.save();
            reply(ctx, "已更新箱子“" + name + "” → " + existing.describe());
            return 1;
        }
        MinerConfig.ChestEntry entry = new MinerConfig.ChestEntry(name, pos);
        config.chests.add(entry);
        config.save();
        reply(ctx, "已添加箱子“" + name + "” → " + entry.describe());
        return 1;
    }

    private static int listChests(CommandContext<FabricClientCommandSource> ctx) {
        MinerConfig config = AutoMiner.config();
        if (config.chests.isEmpty()) {
            reply(ctx, "还没有箱子。对着箱子执行 /am chest add <名字>。");
            return 1;
        }
        StringBuilder builder = new StringBuilder("共 " + config.chests.size() + " 个箱子：");
        for (MinerConfig.ChestEntry entry : config.chests) {
            builder.append("\n- ").append(entry.describe());
        }
        reply(ctx, builder.toString());
        return 1;
    }

    private static int removeChest(CommandContext<FabricClientCommandSource> ctx) {
        String name = StringArgumentType.getString(ctx, "name").trim();
        MinerConfig config = AutoMiner.config();
        MinerConfig.ChestEntry entry = config.chestByName(name);
        if (entry == null) {
            reply(ctx, "没有叫“" + name + "”的箱子。");
            return 0;
        }
        config.chests.remove(entry);
        config.save();
        reply(ctx, "已移除箱子“" + name + "”。");
        return 1;
    }

    private static int editList(CommandContext<FabricClientCommandSource> ctx, boolean add, String kind) {
        String raw = StringArgumentType.getString(ctx, kind);
        String id = normalize(raw);
        MinerConfig config = AutoMiner.config();
        var list = kind.equals("block") ? config.mineBlocks : config.depositItems;
        var registry = kind.equals("block") ? BuiltInRegistries.BLOCK : BuiltInRegistries.ITEM;
        if (!registry.containsKey(Identifier.parse(id))) {
            reply(ctx, "游戏里没有这个" + (kind.equals("block") ? "方块" : "物品") + "：" + id);
            return 0;
        }
        if (add) {
            if (list.contains(id)) {
                reply(ctx, id + " 已经在列表里了。");
                return 1;
            }
            list.add(id);
            reply(ctx, "已添加 " + id + "。");
        } else {
            if (!list.contains(id)) {
                reply(ctx, id + " 不在列表里。");
                return 1;
            }
            list.remove(id);
            reply(ctx, "已移除 " + id + "。");
        }
        config.save();
        return 1;
    }

    private static int showList(CommandContext<FabricClientCommandSource> ctx, String kind) {
        MinerConfig config = AutoMiner.config();
        var list = kind.equals("block") ? config.mineBlocks : config.depositItems;
        reply(ctx, String.join(", ", list));
        return 1;
    }

    private static String normalize(String raw) {
        String id = raw.trim().toLowerCase();
        if (!id.contains(":")) {
            id = "minecraft:" + id;
        }
        return id;
    }

    private static void reply(CommandContext<FabricClientCommandSource> ctx, String text) {
        ctx.getSource().sendFeedback(Component.literal(text));
    }

    private static int setBaseHere(CommandContext<FabricClientCommandSource> ctx) {
        var player = net.minecraft.client.Minecraft.getInstance().player;
        if (player == null) {
            reply(ctx, "还没进世界。");
            return 0;
        }
        BlockPos p = player.blockPosition();
        setBase(p.getX(), p.getY(), p.getZ());
        reply(ctx, "开荒点已设为当前位置 " + p.getX() + "," + p.getY() + "," + p.getZ()
                + "：以后进服先走到这里，到了再开挖。");
        return 1;
    }

    private static void setBase(int x, int y, int z) {
        MinerConfig config = AutoMiner.config();
        config.startPoint.clear();
        config.startPoint.add(x);
        config.startPoint.add(y);
        config.startPoint.add(z);
        config.save();
    }

}
