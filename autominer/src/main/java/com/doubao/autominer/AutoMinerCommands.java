package com.doubao.autominer;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
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
            dispatcher.register(buildIdxTree("idx"));
            dispatcher.register(buildIdxTree("warehouse"));
            dispatcher.register(buildEyeTree("eye"));
        });
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> buildIdxTree(String name) {
        return ClientCommands.literal(name)
                .executes(ctx -> {
                    reply(ctx, Warehouse.summary());
                    return 1;
                })
                .then(ClientCommands.literal("scan").executes(ctx -> {
                    int n = Warehouse.scan(Minecraft.getInstance(), 5);
                    reply(ctx, n > 0 ? ("扫到/更新 " + n + " 个容器，记进 仓库索引.txt")
                            : "附近已加载的区块里没扫到新箱子，走两步再来。");
                    return 1;
                }))
                .then(ClientCommands.literal("find")
                        .then(ClientCommands.argument("东西", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    reply(ctx, Warehouse.find(StringArgumentType.getString(ctx, "东西")));
                                    return 1;
                                })))
                .then(ClientCommands.literal("auto")
                        .then(ClientCommands.literal("on").executes(ctx -> {
                            Warehouse.setAutoScan(true);
                            reply(ctx, "已开启：走过新箱子自动记录。");
                            return 1;
                        }))
                        .then(ClientCommands.literal("off").executes(ctx -> {
                            Warehouse.setAutoScan(false);
                            reply(ctx, "已关闭自动记录（/idx scan 手动扫）。");
                            return 1;
                        })))
                .then(ClientCommands.literal("clear").executes(ctx -> {
                    Warehouse.clear();
                    reply(ctx, "仓库索引已清空。");
                    return 1;
                }))
                .then(ClientCommands.literal("help").executes(ctx -> {
                    reply(ctx, "仓库索引：/idx 概况；/idx scan 扫附近 11×11 区块；/idx find 钻石 查在哪；/idx auto on|off；/idx clear");
                    return 1;
                }));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> buildEyeTree(String name) {
        return ClientCommands.literal(name)
                .executes(ctx -> {
                    reply(ctx, "末影之眼定位：站着扔眼睛，模组自动记方向；扔两次以上 /eye calc 算要塞坐标；/eye list 看记录；/eye clear 清空");
                    return 1;
                })
                .then(ClientCommands.literal("calc").executes(ctx -> {
                    String text = EyeLocator.calc();
                    var src = ctx.getSource();
                    if (!Double.isNaN(EyeLocator.LAST_X) && src.getPlayer() != null) {
                        int dist = (int) Math.round(Math.hypot(src.getPlayer().getX() - EyeLocator.LAST_X,
                                src.getPlayer().getZ() - EyeLocator.LAST_Z));
                        text = text + "\n离你约 " + dist + " 格（直线）";
                    }
                    reply(ctx, text);
                    return 1;
                }))
                .then(ClientCommands.literal("list").executes(ctx -> {
                    reply(ctx, EyeLocator.list());
                    return 1;
                }))
                .then(ClientCommands.literal("clear").executes(ctx -> {
                    EyeLocator.clear();
                    reply(ctx, "眼睛记录已清空。");
                    return 1;
                }));
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
                .then(ClientCommands.literal("macro")
                        .executes(ctx -> {
                            reply(ctx, MacroManager.statusText());
                            reply(ctx, "宏列表：" + (MacroManager.listNames().isEmpty()
                                    ? "（空）" : String.join("、", MacroManager.listNames())));
                            return 1;
                        })
                        .then(ClientCommands.literal("list")
                                .executes(ctx -> {
                                    reply(ctx, "宏列表：" + (MacroManager.listNames().isEmpty()
                                            ? "（空）" : String.join("、", MacroManager.listNames())));
                                    return 1;
                                }))
                        .then(ClientCommands.literal("rec")
                                .executes(ctx -> {
                                    MacroManager.toggleRecord();
                                    return 1;
                                }))
                        .then(ClientCommands.literal("stop")
                                .executes(ctx -> {
                                    MacroManager.stopPlayback();
                                    return 1;
                                }))
                        .then(ClientCommands.literal("play")
                                .then(ClientCommands.argument("name", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            MacroManager.startPlay(StringArgumentType.getString(ctx, "name"), false);
                                            return 1;
                                        })))
                        .then(ClientCommands.literal("del")
                                .then(ClientCommands.argument("name", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            String n = StringArgumentType.getString(ctx, "name");
                                            MacroManager.delete(n);
                                            reply(ctx, "已删除宏「" + n + "」。");
                                            return 1;
                                        })))
                        .then(ClientCommands.literal("auto")
                                .then(ClientCommands.literal("on")
                                        .executes(ctx -> {
                                            MacroManager.setAuto(true);
                                            reply(ctx, "自动回放已开启。");
                                            return 1;
                                        }))
                                .then(ClientCommands.literal("off")
                                        .executes(ctx -> {
                                            MacroManager.setAuto(false);
                                            reply(ctx, "自动回放已关闭。");
                                            return 1;
                                        }))))
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
                .then(ClientCommands.literal("hook")
                        .executes(ctx -> {
                            reply(ctx, hookList());
                            return 1;
                        })
                        .then(ClientCommands.literal("list").executes(ctx -> {
                            reply(ctx, hookList());
                            return 1;
                        }))
                        .then(ClientCommands.argument("事件", StringArgumentType.word())
                                .executes(ctx -> {
                                    HookEvent event = findHookEvent(StringArgumentType.getString(ctx, "事件"));
                                    if (event == null) {
                                        reply(ctx, "没有这个事件。可用：" + hookKeys());
                                        return 1;
                                    }
                                    reply(ctx, hookStatus(event));
                                    return 1;
                                })
                                .then(ClientCommands.literal("on").executes(ctx -> {
                                    HookEvent event = findHookEvent(StringArgumentType.getString(ctx, "事件"));
                                    if (event == null) {
                                        reply(ctx, "没有这个事件。可用：" + hookKeys());
                                        return 1;
                                    }
                                    AutoMiner.config().hookOrCreate(event).enabled = true;
                                    AutoMiner.config().save();
                                    reply(ctx, "已开启[" + event.label + "]事件脚本。");
                                    return 1;
                                }))
                                .then(ClientCommands.literal("off").executes(ctx -> {
                                    HookEvent event = findHookEvent(StringArgumentType.getString(ctx, "事件"));
                                    if (event == null) {
                                        reply(ctx, "没有这个事件。可用：" + hookKeys());
                                        return 1;
                                    }
                                    AutoMiner.config().hookOrCreate(event).enabled = false;
                                    AutoMiner.config().save();
                                    reply(ctx, "已关闭[" + event.label + "]事件脚本。");
                                    return 1;
                                }))
                                .then(ClientCommands.literal("add")
                                        .then(ClientCommands.argument("指令", StringArgumentType.greedyString())
                                                .executes(ctx -> {
                                                    HookEvent event = findHookEvent(StringArgumentType.getString(ctx, "事件"));
                                                    if (event == null) {
                                                        reply(ctx, "没有这个事件。可用：" + hookKeys());
                                                        return 1;
                                                    }
                                                    String cmd = StringArgumentType.getString(ctx, "指令").trim();
                                                    if (cmd.startsWith("/")) {
                                                        cmd = cmd.substring(1);
                                                    }
                                                    if (cmd.isEmpty()) {
                                                        reply(ctx, "指令不能为空。");
                                                        return 1;
                                                    }
                                                    MinerConfig.Hook hook = AutoMiner.config().hookOrCreate(event);
                                                    hook.commands.add(cmd);
                                                    AutoMiner.config().save();
                                                    reply(ctx, "已给[" + event.label + "]添加第 " + hook.commands.size() + " 条：" + cmd);
                                                    return 1;
                                                })))
                                .then(ClientCommands.literal("del")
                                        .then(ClientCommands.argument("序号", IntegerArgumentType.integer(1, 999))
                                                .executes(ctx -> {
                                                    HookEvent event = findHookEvent(StringArgumentType.getString(ctx, "事件"));
                                                    if (event == null) {
                                                        reply(ctx, "没有这个事件。可用：" + hookKeys());
                                                        return 1;
                                                    }
                                                    MinerConfig.Hook hook = AutoMiner.config().hookOrCreate(event);
                                                    int index = IntegerArgumentType.getInteger(ctx, "序号") - 1;
                                                    if (index < 0 || index >= hook.commands.size()) {
                                                        reply(ctx, "没有这个序号，先用 /am hook " + event.key + " 看。");
                                                        return 1;
                                                    }
                                                    String removed = hook.commands.remove(index);
                                                    AutoMiner.config().save();
                                                    reply(ctx, "已删除[" + event.label + "]指令：" + removed);
                                                    return 1;
                                                })))
                                .then(ClientCommands.literal("macro")
                                        .executes(ctx -> {
                                            HookEvent event = findHookEvent(StringArgumentType.getString(ctx, "事件"));
                                            if (event == null) {
                                                reply(ctx, "没有这个事件。可用：" + hookKeys());
                                                return 1;
                                            }
                                            MinerConfig.Hook hook = AutoMiner.config().hookOrCreate(event);
                                            reply(ctx, "[" + event.label + "]宏："
                                                    + (hook.macro == null || hook.macro.isBlank() ? "空" : hook.macro));
                                            return 1;
                                        })
                                        .then(ClientCommands.argument("名字", StringArgumentType.greedyString())
                                                .executes(ctx -> {
                                                    HookEvent event = findHookEvent(StringArgumentType.getString(ctx, "事件"));
                                                    if (event == null) {
                                                        reply(ctx, "没有这个事件。可用：" + hookKeys());
                                                        return 1;
                                                    }
                                                    String macroName = StringArgumentType.getString(ctx, "名字").trim();
                                                    MinerConfig.Hook hook = AutoMiner.config().hookOrCreate(event);
                                                    if (macroName.equalsIgnoreCase("off") || macroName.equals("无")) {
                                                        hook.macro = "";
                                                        if ("macro".equals(hook.action)) hook.action = "command";
                                                        AutoMiner.config().save();
                                                        reply(ctx, "已清空[" + event.label + "]的宏。");
                                                    } else {
                                                        hook.macro = macroName;
                                                        hook.action = "macro";
                                                        AutoMiner.config().save();
                                                        reply(ctx, "[" + event.label + "]宏：" + macroName
                                                                + "（动作已切到宏；宏文件在 宏/" + macroName + ".txt）");
                                                    }
                                                    return 1;
                                                })))
                                .then(ClientCommands.literal("action")
                                        .then(ClientCommands.argument("类型", StringArgumentType.word())
                                                .executes(ctx -> {
                                                    HookEvent event = findHookEvent(StringArgumentType.getString(ctx, "事件"));
                                                    if (event == null) {
                                                        reply(ctx, "没有这个事件。可用：" + hookKeys());
                                                        return 1;
                                                    }
                                                    String type = StringArgumentType.getString(ctx, "类型").toLowerCase();
                                                    if (!type.equals("macro") && !type.equals("command") && !type.equals("both")) {
                                                        reply(ctx, "类型只能是 macro、command、both。");
                                                        return 1;
                                                    }
                                                    MinerConfig.Hook hook = AutoMiner.config().hookOrCreate(event);
                                                    hook.action = type;
                                                    AutoMiner.config().save();
                                                    reply(ctx, "[" + event.label + "]动作：" + actionLabel(type));
                                                    return 1;
                                                })))
                                .then(ClientCommands.literal("clear").executes(ctx -> {
                                    HookEvent event = findHookEvent(StringArgumentType.getString(ctx, "事件"));
                                    if (event == null) {
                                        reply(ctx, "没有这个事件。可用：" + hookKeys());
                                        return 1;
                                    }
                                    MinerConfig.Hook hook = AutoMiner.config().hookOrCreate(event);
                                    hook.commands.clear();
                                    hook.macro = "";
                                    hook.action = "command";
                                    AutoMiner.config().save();
                                    reply(ctx, "已清空[" + event.label + "]的宏和指令（脚本文件仍会读）。");
                                    return 1;
                                }))
                                .then(ClientCommands.literal("script")
                                        .then(ClientCommands.argument("文件名", StringArgumentType.word())
                                                .executes(ctx -> {
                                                    HookEvent event = findHookEvent(StringArgumentType.getString(ctx, "事件"));
                                                    if (event == null) {
                                                        reply(ctx, "没有这个事件。可用：" + hookKeys());
                                                        return 1;
                                                    }
                                                    MinerConfig.Hook hook = AutoMiner.config().hookOrCreate(event);
                                                    hook.script = StringArgumentType.getString(ctx, "文件名");
                                                    AutoMiner.config().save();
                                                    reply(ctx, "[" + event.label + "]脚本文件：" + hook.script
                                                            + "（放 config 目录，一行一条，空 = 不读）");
                                                    return 1;
                                                })))
                                .then(ClientCommands.literal("delay")
                                        .then(ClientCommands.argument("tick", IntegerArgumentType.integer(0, 200))
                                                .executes(ctx -> {
                                                    HookEvent event = findHookEvent(StringArgumentType.getString(ctx, "事件"));
                                                    if (event == null) {
                                                        reply(ctx, "没有这个事件。可用：" + hookKeys());
                                                        return 1;
                                                    }
                                                    MinerConfig.Hook hook = AutoMiner.config().hookOrCreate(event);
                                                    hook.commandDelayTicks = IntegerArgumentType.getInteger(ctx, "tick");
                                                    AutoMiner.config().save();
                                                    reply(ctx, "[" + event.label + "]指令间隔 " + hook.commandDelayTicks + " tick。");
                                                    return 1;
                                                })))
                                .then(ClientCommands.literal("settle")
                                        .then(ClientCommands.argument("tick", IntegerArgumentType.integer(0, 600))
                                                .executes(ctx -> {
                                                    HookEvent event = findHookEvent(StringArgumentType.getString(ctx, "事件"));
                                                    if (event == null) {
                                                        reply(ctx, "没有这个事件。可用：" + hookKeys());
                                                        return 1;
                                                    }
                                                    MinerConfig.Hook hook = AutoMiner.config().hookOrCreate(event);
                                                    hook.settleTicks = IntegerArgumentType.getInteger(ctx, "tick");
                                                    AutoMiner.config().save();
                                                    reply(ctx, "[" + event.label + "]执行完等 " + hook.settleTicks + " tick。");
                                                    return 1;
                                                })))))
                .then(ClientCommands.literal("predeposit").executes(ctx -> {
                    reply(ctx, hookStatus(HookEvent.PRE_DEPOSIT));
                    return 1;
                }))
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
                .then(ClientCommands.literal("recycle")
                        .executes(ctx -> {
                            MinerConfig cfg = AutoMiner.config();
                            reply(ctx, "自动回收：" + (cfg.recycle.enabled ? "开" : "关")
                                    + "；镐子低于 " + cfg.recycle.pickDurabilityPercent + "% 回箱"
                                    + "；背包空位 ≤ " + cfg.recycle.minFreeSlots + " 回箱");
                            return 1;
                        })
                        .then(ClientCommands.literal("on").executes(ctx -> {
                            AutoMiner.config().recycle.enabled = true;
                            AutoMiner.config().save();
                            reply(ctx, "已开启自动回收（镐快坏了/背包快满会回箱拿备用镐）。");
                            return 1;
                        }))
                        .then(ClientCommands.literal("off").executes(ctx -> {
                            AutoMiner.config().recycle.enabled = false;
                            AutoMiner.config().save();
                            reply(ctx, "已关闭自动回收。");
                            return 1;
                        }))
                        .then(ClientCommands.literal("pick")
                                .then(ClientCommands.argument("百分比", IntegerArgumentType.integer(0, 90))
                                        .executes(ctx -> {
                                            int v = IntegerArgumentType.getInteger(ctx, "百分比");
                                            AutoMiner.config().recycle.pickDurabilityPercent = v;
                                            AutoMiner.config().save();
                                            reply(ctx, v == 0 ? "已关闭按耐久回箱。" : "镐子剩 " + v + "% 就回箱换镐。");
                                            return 1;
                                        })))
                        .then(ClientCommands.literal("slots")
                                .then(ClientCommands.argument("空位", IntegerArgumentType.integer(0, 20))
                                        .executes(ctx -> {
                                            int v = IntegerArgumentType.getInteger(ctx, "空位");
                                            AutoMiner.config().recycle.minFreeSlots = v;
                                            AutoMiner.config().save();
                                            reply(ctx, v == 0 ? "已关闭按空位回箱。" : "背包只剩 " + v + " 格就回箱。");
                                            return 1;
                                        }))))
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

    private static String actionLabel(String action) {
        if ("macro".equals(action)) return "宏";
        if ("both".equals(action)) return "宏+指令";
        return "指令";
    }

    private static String hookList() {
        StringBuilder sb = new StringBuilder("事件脚本（每个事件独立开关；/am hook <事件> 看详情）：");
        for (HookEvent event : HookEvent.values()) {
            MinerConfig.Hook hook = AutoMiner.config().hookOrCreate(event);
            sb.append('\n').append(hook.enabled ? "§a开" : "§7关").append("  ")
                    .append(event.label).append("  §7").append(event.key)
                    .append("  §8").append(hook.commands.size()).append(" 条")
                    .append("  §8").append(actionLabel(hook.action))
                    .append(hook.macro == null || hook.macro.isBlank() ? "" : "  §b宏:" + hook.macro)
                    .append(hook.script == null || hook.script.isBlank() ? "" : " + " + hook.script);
        }
        return sb.toString();
    }

    private static String hookStatus(HookEvent event) {
        MinerConfig.Hook hook = AutoMiner.config().hookOrCreate(event);
        StringBuilder sb = new StringBuilder(event.label + "（" + event.key + "）：")
                .append(hook.enabled ? "开" : "关");
        sb.append("\n").append(event.description);
        sb.append("\n动作：").append(actionLabel(hook.action));
        sb.append("\n宏：").append(hook.macro == null || hook.macro.isBlank() ? "空" : hook.macro);
        sb.append("\n脚本：").append(hook.script == null || hook.script.isBlank() ? "不读" : hook.script);
        sb.append("；间隔 ").append(hook.commandDelayTicks).append(" tick")
                .append("；执行完等 ").append(hook.settleTicks).append(" tick");
        if (hook.commands.isEmpty()) {
            sb.append("\n指令：空（/am hook ").append(event.key).append(" add /home）");
        } else {
            sb.append("\n指令：");
            for (int i = 0; i < hook.commands.size(); i++) {
                sb.append('\n').append(i + 1).append(". ").append(hook.commands.get(i));
            }
        }
        return sb.toString();
    }

    private static HookEvent findHookEvent(String raw) {
        HookEvent event = HookEvent.byKey(raw);
        if (event != null) {
            return event;
        }
        if (raw != null) {
            for (HookEvent candidate : HookEvent.values()) {
                if (candidate.label.equals(raw)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static String hookKeys() {
        StringBuilder sb = new StringBuilder();
        for (HookEvent event : HookEvent.values()) {
            if (!sb.isEmpty()) {
                sb.append('、');
            }
            sb.append(event.key).append("(").append(event.label).append(")");
        }
        return sb.toString();
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
        builder.append("\n事件脚本：");
        for (HookEvent event : HookEvent.values()) {
            MinerConfig.Hook hook = config.hookOrCreate(event);
            builder.append('\n').append(hook.enabled ? "开 " : "关 ").append(event.label)
                    .append("：").append(hook.commands.size()).append(" 条")
                    .append(" + ").append(actionLabel(hook.action))
                    .append(hook.macro == null || hook.macro.isBlank() ? "" : " + 宏:" + hook.macro)
                    .append(hook.script == null || hook.script.isBlank() ? "" : " + " + hook.script);
        }
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
