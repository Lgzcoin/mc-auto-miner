package com.doubao.autominer;

import java.util.ArrayList;
import java.util.List;

/**
 * 采矿种类目录：一种矿对应若干个矿石方块 + 对应的掉落物。
 * 图形界面里勾选“钻石矿”，就会同时把钻石矿方块加入挖矿清单、把钻石加入存矿清单。
 */
public final class OreCatalog {
    public record OreType(String label, List<String> blocks, List<String> drops) {
    }

    public static final List<OreType> ALL = List.of(
            new OreType("煤矿", List.of("minecraft:coal_ore", "minecraft:deepslate_coal_ore"),
                    List.of("minecraft:coal")),
            new OreType("铁矿", List.of("minecraft:iron_ore", "minecraft:deepslate_iron_ore"),
                    List.of("minecraft:raw_iron")),
            new OreType("铜矿", List.of("minecraft:copper_ore", "minecraft:deepslate_copper_ore"),
                    List.of("minecraft:raw_copper")),
            new OreType("金矿", List.of("minecraft:gold_ore", "minecraft:deepslate_gold_ore"),
                    List.of("minecraft:raw_gold")),
            new OreType("下界金矿", List.of("minecraft:nether_gold_ore"),
                    List.of("minecraft:gold_nugget")),
            new OreType("红石", List.of("minecraft:redstone_ore", "minecraft:deepslate_redstone_ore"),
                    List.of("minecraft:redstone")),
            new OreType("青金石", List.of("minecraft:lapis_ore", "minecraft:deepslate_lapis_ore"),
                    List.of("minecraft:lapis_lazuli")),
            new OreType("钻石", List.of("minecraft:diamond_ore", "minecraft:deepslate_diamond_ore"),
                    List.of("minecraft:diamond")),
            new OreType("绿宝石", List.of("minecraft:emerald_ore", "minecraft:deepslate_emerald_ore"),
                    List.of("minecraft:emerald")),
            new OreType("下界石英", List.of("minecraft:nether_quartz_ore"),
                    List.of("minecraft:quartz")),
            new OreType("远古残骸", List.of("minecraft:ancient_debris"),
                    List.of("minecraft:netherite_scrap", "minecraft:ancient_debris")),
            new OreType("紫水晶", List.of("minecraft:amethyst_cluster", "minecraft:budding_amethyst"),
                    List.of("minecraft:amethyst_shard"))
    );

    private OreCatalog() {
    }

    public static boolean isEnabled(MinerConfig config, OreType type) {
        return !type.blocks().isEmpty() && config.mineBlocks.containsAll(type.blocks());
    }

    public static void setEnabled(MinerConfig config, OreType type, boolean enabled) {
        List<String> blocks = new ArrayList<>(config.mineBlocks);
        List<String> drops = new ArrayList<>(config.depositItems);
        if (enabled) {
            for (String block : type.blocks()) {
                if (!blocks.contains(block)) {
                    blocks.add(block);
                }
            }
            for (String drop : type.drops()) {
                if (!drops.contains(drop)) {
                    drops.add(drop);
                }
            }
        } else {
            blocks.removeAll(type.blocks());
            drops.removeAll(type.drops());
        }
        config.mineBlocks = blocks;
        config.depositItems = drops;
        config.save();
    }

    public static int enabledCount(MinerConfig config) {
        int count = 0;
        for (OreType type : ALL) {
            if (isEnabled(config, type)) {
                count++;
            }
        }
        return count;
    }
}
