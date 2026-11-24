package com.doubao.autominer;

import net.minecraft.client.ClientRecipeBook;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.display.FurnaceRecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把“我要 N 个某物品”拆成一份可执行的计划：
 * 先按方块逐个挖够原料，再按配方从下往上合成。
 */
public final class GetPlan {

    /** 挖矿步骤：一直挖这些方块，直到背包里 itemId 的数量够 qty。 */
    public record MineStep(String itemId, int qty, List<String> blocks) {
    }

    /** 合成步骤：用 displayId 这个配方合成 qty 个产物（每个产物 perCraft 个）。 */
    public record CraftStep(RecipeDisplayId displayId, String resultItem, int perCraft, int qty,
                           int width, int height) {
        public boolean needsTable() {
            return width > 2 || height > 2;
        }
    }

    public final List<MineStep> mines = new ArrayList<>();
    public final List<CraftStep> crafts = new ArrayList<>();
    public String error;

    public boolean ok() {
        return error == null && (!mines.isEmpty() || !crafts.isEmpty());
    }

    // ------------------------------------------------------------ 构建

    public static GetPlan build(Level level, ClientRecipeBook book, String itemId, int qty) {
        GetPlan plan = new GetPlan();
        Identifier id = Identifier.tryParse(itemId);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            plan.error = "游戏里没有这个物品：" + itemId;
            return plan;
        }
        Item target = BuiltInRegistries.ITEM.getValue(id);
        Builder builder = new Builder(level, book);
        if (!builder.resolve(target, qty, new ArrayList<>())) {
            plan.error = builder.error != null ? builder.error : "没找到能弄到它的办法。";
            return plan;
        }
        plan.mines.addAll(builder.mines.values());
        plan.crafts.addAll(builder.crafts);
        if (plan.mines.isEmpty() && plan.crafts.isEmpty()) {
            plan.error = "这个物品没法通过挖矿或合成获得。";
        }
        return plan;
    }

    private static final class Builder {
        final Level level;
        final ClientRecipeBook book;
        final Map<String, MineStep> mines = new LinkedHashMap<>();
        final List<CraftStep> crafts = new ArrayList<>();
        String error;

        Builder(Level level, ClientRecipeBook book) {
            this.level = level;
            this.book = book;
        }

        boolean resolve(Item item, int qty, List<Item> stack) {
            if (qty <= 0) {
                return true;
            }
            Identifier id = BuiltInRegistries.ITEM.getKey(item);
            if (id == null) {
                error = "物品没有注册名。";
                return false;
            }
            if (stack.contains(item)) {
                error = "配方里出现了循环依赖：" + id;
                return false;
            }
            if (stack.size() > 10) {
                error = "配方层级太深，放弃。";
                return false;
            }

            // 1) 能直接挖到的，优先挖
            List<String> blocks = blocksFor(item);
            if (!blocks.isEmpty()) {
                String key = id.toString();
                MineStep old = mines.get(key);
                mines.put(key, new MineStep(key, (old == null ? 0 : old.qty()) + qty, blocks));
                return true;
            }

            // 2) 只能熔炼的，明确告诉他
            RecipeDisplayEntry furnace = findFurnace(item);
            if (furnace != null) {
                error = id.getPath() + " 只能靠熔炉熔炼，这个版本还不支持自动熔炼（材料可以手动烧）。";
                return false;
            }

            // 3) 合成：挑一个尺寸最小、原料都能解决的配方
            RecipeDisplayEntry recipe = chooseCrafting(item, qty, stack);
            if (recipe == null) {
                if (error == null) {
                    error = "配方书里没有 " + id.getPath() + " 的合成配方（可能还没解锁）。";
                }
                return false;
            }

            // chooseCrafting 已经把这条配方连同它需要的原料（含子配方）整条规划好并合并进计划了，
            // 这里再递归一遍会把原料数量算成两倍，所以直接返回。
            return true;
        }

        private void craftsListAdd(RecipeDisplayId displayId, String resultItem, int perCraft, int crafts,
                                   int width, int height) {
            for (int i = 0; i < crafts().size(); i++) {
                CraftStep old = crafts().get(i);
                if (old.displayId().equals(displayId)) {
                    crafts().set(i, new CraftStep(displayId, resultItem, perCraft, old.qty() + crafts, width, height));
                    return;
                }
            }
            crafts().add(new CraftStep(displayId, resultItem, perCraft, crafts, width, height));
        }

        private List<CraftStep> crafts() {
            return crafts;
        }

        private int amountOf(net.minecraft.world.item.crafting.display.SlotDisplay display) {
            try {
                List<ItemStack> stacks = display.resolveForStacks(SlotDisplayContext.fromLevel(level));
                if (!stacks.isEmpty()) {
                    return Math.max(1, stacks.get(0).getCount());
                }
            } catch (Throwable ignored) {
                // 拿不到就当 1 个
            }
            return 1;
        }

        private RecipeDisplayEntry findFurnace(Item item) {
            for (RecipeDisplayEntry entry : allEntries()) {
                if (entry.display() instanceof FurnaceRecipeDisplay) {
                    if (produces(entry, item)) {
                        return entry;
                    }
                }
            }
            return null;
        }

        private boolean produces(RecipeDisplayEntry entry, Item item) {
            try {
                return entry.display().result()
                        .resolveForStacks(SlotDisplayContext.fromLevel(level))
                        .stream().anyMatch(s -> s.getItem() == item);
            } catch (Throwable t) {
                return false;
            }
        }

        private RecipeDisplayEntry chooseCrafting(Item item, int qty, List<Item> stack) {
            List<RecipeDisplayEntry> candidates = new ArrayList<>();
            for (RecipeDisplayEntry entry : allEntries()) {
                RecipeDisplay display = entry.display();
                if (!(display instanceof ShapedCraftingRecipeDisplay) && !(display instanceof ShapelessCraftingRecipeDisplay)) {
                    continue;
                }
                if (entry.craftingRequirements().isEmpty()) {
                    continue;
                }
                if (produces(entry, item)) {
                    candidates.add(entry);
                }
            }
            candidates.sort((a, b) -> Integer.compare(sizeOf(a), sizeOf(b)));
            int best = Integer.MAX_VALUE;
            RecipeDisplayEntry bestEntry = null;
            for (RecipeDisplayEntry candidate : candidates) {
                Builder trial = new Builder(level, book);
                trial.mines.putAll(this.mines);
                trial.crafts.addAll(this.crafts);
                if (trial.tryResolveWith(candidate, item, qty, stack)) {
                    int size = sizeOf(candidate);
                    int cost = trial.crafts.size() * 10 + trial.mines.size();
                    if (size + cost < best) {
                        best = size + cost;
                        bestEntry = candidate;
                        this.mines.clear();
                        this.mines.putAll(trial.mines);
                        this.crafts.clear();
                        this.crafts.addAll(trial.crafts);
                        this.error = null;
                    }
                }
            }
            if (bestEntry == null) {
                error = null;
                return null;
            }
            return bestEntry;
        }

        private boolean tryResolveWith(RecipeDisplayEntry entry, Item item, int qty, List<Item> outer) {
            RecipeDisplay display = entry.display();
            int width;
            int height;
            List<Ingredient> ingredients = entry.craftingRequirements().orElse(List.of());
            if (display instanceof ShapedCraftingRecipeDisplay s) {
                width = s.width();
                height = s.height();
            } else {
                // 无序配方按原料格数算：≤4 格用 2x2，更多才需要工作台
                int grid = ingredients.size() <= 4 ? 2 : 3;
                width = grid;
                height = grid;
            }
            int perCraft = display instanceof ShapedCraftingRecipeDisplay s
                    ? amountOf(s.result()) : amountOf(display.result());
            if (perCraft <= 0) {
                perCraft = 1;
            }
            int crafts = (qty + perCraft - 1) / perCraft;
            outer.add(item);
            for (Ingredient ingredient : ingredients) {
                boolean okOne = false;
                String lastError = null;
                for (Holder<Item> option : ingredient.items().toList()) {
                    if (resolve(option.value(), crafts, outer)) {
                        okOne = true;
                        break;
                    }
                    lastError = error;
                }
                if (!okOne) {
                    error = lastError;
                    outer.remove(outer.size() - 1);
                    return false;
                }
            }
            outer.remove(outer.size() - 1);
            craftsListAdd(entry.id(), BuiltInRegistries.ITEM.getKey(item).toString(), perCraft, crafts, width, height);
            return true;
        }

        private int sizeOf(RecipeDisplayEntry entry) {
            RecipeDisplay display = entry.display();
            if (display instanceof ShapedCraftingRecipeDisplay shaped) {
                return shaped.width() * shaped.height();
            }
            return 4;
        }

        private List<RecipeDisplayEntry> allEntries() {
            List<RecipeDisplayEntry> all = new ArrayList<>();
            if (book == null) {
                return all;
            }
            for (var collection : book.getCollections()) {
                all.addAll(collection.getRecipes());
            }
            return all;
        }
    }

    /** 把物品映射成“能挖出它的方块”。 */
    static List<String> blocksFor(Item item) {
        Identifier id = BuiltInRegistries.ITEM.getKey(item);
        if (id == null) {
            return List.of();
        }
        String path = id.getPath();
        List<String> explicit = switch (path) {
            case "coal" -> List.of("coal_ore", "deepslate_coal_ore");
            case "diamond" -> List.of("diamond_ore", "deepslate_diamond_ore");
            case "emerald" -> List.of("emerald_ore", "deepslate_emerald_ore");
            case "lapis_lazuli" -> List.of("lapis_ore", "deepslate_lapis_ore");
            case "redstone" -> List.of("redstone_ore", "deepslate_redstone_ore");
            case "quartz" -> List.of("nether_quartz_ore");
            case "raw_iron" -> List.of("iron_ore", "deepslate_iron_ore");
            case "raw_gold" -> List.of("gold_ore", "deepslate_gold_ore", "nether_gold_ore");
            case "raw_copper" -> List.of("copper_ore", "deepslate_copper_ore");
            case "ancient_debris" -> List.of("ancient_debris");
            case "amethyst_shard" -> List.of("amethyst_cluster");
            case "glowstone_dust" -> List.of("glowstone");
            case "cobblestone" -> List.of("stone", "cobblestone");
            case "cobbled_deepslate" -> List.of("deepslate");
            case "clay_ball" -> List.of("clay");
            case "snowball" -> List.of("snow_block");
            case "string" -> List.of();
            default -> List.of();
        };
        List<String> blocks = new ArrayList<>();
        if (!explicit.isEmpty()) {
            for (String candidate : explicit) {
                if (BuiltInRegistries.BLOCK.containsKey(Identifier.parse("minecraft:" + candidate))) {
                    blocks.add("minecraft:" + candidate);
                }
            }
            return blocks;
        }

        // 通用规则：方块 id 和物品 id 相同，或 物品名_ore 之类
        List<String> candidates = new ArrayList<>();
        candidates.add(path);
        candidates.add(path + "_ore");
        candidates.add("deepslate_" + path + "_ore");
        candidates.add("nether_" + path + "_ore");
        candidates.add(path + "_block");
        for (String candidate : candidates) {
            if (BuiltInRegistries.BLOCK.containsKey(Identifier.parse("minecraft:" + candidate))) {
                blocks.add("minecraft:" + candidate);
            }
        }
        return blocks;
    }
}
