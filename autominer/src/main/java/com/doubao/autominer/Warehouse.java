package com.doubao.autominer;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 仓库索引：走过一个箱子就把里面的东西记下来，之后 /idx find 钻石 直接告诉你哪口箱子有。
 * 只读客户端已经加载的区块，不碰服务端；走远了没加载就扫不到，这是正常现象。
 */
public final class Warehouse {
    public static class Entry {
        public String dim = "";
        public int x, y, z;
        public String type = "箱子";
        public Map<String, Integer> items = new LinkedHashMap<>();

        public double distSq(BlockPos p) {
            double dx = p.getX() - x;
            double dy = p.getY() - y;
            double dz = p.getZ() - z;
            return dx * dx + dy * dy + dz * dz;
        }
    }

    private static final Map<String, Entry> INDEX = new LinkedHashMap<>();
    private static boolean loaded = false;
    private static boolean autoScan = true;
    private static boolean dirty = false;
    private static int saveTicks = 0;
    private static String lastChunk = "";
    private static int scannedChunks = 0;

    private Warehouse() {
    }

    private static Path file() {
        return FabricLoader.getInstance().getGameDir().resolve("仓库索引.txt");
    }

    private static String key(String dim, BlockPos p) {
        return dim + "|" + p.getX() + "," + p.getY() + "," + p.getZ();
    }

    public static void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            Path f = file();
            if (!Files.exists(f)) {
                return;
            }
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                if (line.startsWith("#") || line.isBlank()) {
                    continue;
                }
                String[] parts = line.split("\\|");
                if (parts.length < 5) {
                    continue;
                }
                Entry e = new Entry();
                e.dim = parts[1];
                String[] xyz = parts[2].split(",");
                if (xyz.length != 3) {
                    continue;
                }
                try {
                    e.x = Integer.parseInt(xyz[0]);
                    e.y = Integer.parseInt(xyz[1]);
                    e.z = Integer.parseInt(xyz[2]);
                } catch (NumberFormatException ex) {
                    continue;
                }
                e.type = parts[3];
                for (String kv : parts[4].split(",")) {
                    int eq = kv.lastIndexOf('=');
                    if (eq <= 0) {
                        continue;
                    }
                    try {
                        e.items.put(kv.substring(0, eq), Integer.parseInt(kv.substring(eq + 1)));
                    } catch (NumberFormatException ignored) {
                    }
                }
                INDEX.put(key(e.dim, new BlockPos(e.x, e.y, e.z)), e);
            }
            AutoMiner.LOGGER.info("仓库索引：读回 {} 个容器", INDEX.size());
        } catch (Throwable t) {
            AutoMiner.LOGGER.warn("读 仓库索引.txt 失败", t);
        }
    }

    public static boolean autoScan() {
        return autoScan;
    }

    public static void setAutoScan(boolean v) {
        autoScan = v;
    }

    public static int size() {
        return INDEX.size();
    }

    public static int scannedChunks() {
        return scannedChunks;
    }

    public static void clear() {
        INDEX.clear();
        dirty = true;
        saveNow();
    }

    public static void tick(Minecraft mc) {
        load();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) {
            return;
        }
        if (!autoScan) {
            return;
        }
        BlockPos p = mc.player.blockPosition();
        String ck = (p.getX() >> 4) + "," + (p.getZ() >> 4);
        if (ck.equals(lastChunk)) {
            saveThrottled();
            return;
        }
        lastChunk = ck;
        scan(mc, 1);
        saveThrottled();
    }

    private static void saveThrottled() {
        saveTicks++;
        if (dirty && saveTicks % 100 == 0) {
            saveNow();
        }
    }

    /** 扫描周围 radius 个区块里的容器，返回新发现/更新的容器数。 */
    public static int scan(Minecraft mc, int radius) {
        load();
        ClientLevel level = mc.level;
        if (level == null) {
            return 0;
        }
        String dim = level.dimension().identifier().toString();
        BlockPos pp = mc.player == null ? BlockPos.ZERO : mc.player.blockPosition();
        int pcx = pp.getX() >> 4;
        int pcz = pp.getZ() >> 4;
        int changed = 0;
        int chunksRead = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                LevelChunk chunk;
                try {
                    chunk = level.getChunkSource().getChunk(pcx + dx, pcz + dz, ChunkStatus.FULL, false);
                } catch (Throwable t) {
                    continue;
                }
                if (chunk == null) {
                    continue;
                }
                chunksRead++;
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    if (!(be instanceof Container container)) {
                        continue;
                    }
                    changed += indexContainer(level, dim, be, container) ? 1 : 0;
                }
            }
        }
        scannedChunks += chunksRead;
        if (changed > 0) {
            dirty = true;
        }
        return changed;
    }

    private static boolean indexContainer(ClientLevel level, String dim, BlockEntity be, Container container) {
        BlockPos pos = be.getBlockPos();
        Map<String, Integer> items = new LinkedHashMap<>();
        int slots = container.getContainerSize();
        for (int i = 0; i < slots; i++) {
            ItemStack stack = container.getItem(i);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            items.merge(id, stack.getCount(), Integer::sum);
        }
        if (items.isEmpty()) {
            INDEX.remove(key(dim, pos));
            return false;
        }
        Entry e = new Entry();
        e.dim = dim;
        e.x = pos.getX();
        e.y = pos.getY();
        e.z = pos.getZ();
        e.items = items;
        try {
            e.type = BuiltInRegistries.BLOCK.getKey(be.getBlockState().getBlock()).getPath();
        } catch (Throwable ignored) {
            e.type = "container";
        }
        String k = key(dim, pos);
        Entry old = INDEX.get(k);
        if (old != null && old.items.equals(items) && old.type.equals(e.type)) {
            return false;
        }
        INDEX.put(k, e);
        return true;
    }

    private static void saveNow() {
        try {
            Path f = file();
            StringBuilder sb = new StringBuilder();
            sb.append("# 仓库索引 autominer v1.11.0\n");
            sb.append("# 格式 CONTAINER|维度|x,y,z|方块|itemId=数量,...\n");
            for (Entry e : INDEX.values()) {
                sb.append("CONTAINER|").append(e.dim).append('|')
                        .append(e.x).append(',').append(e.y).append(',').append(e.z).append('|')
                        .append(e.type).append('|');
                boolean first = true;
                for (Map.Entry<String, Integer> it : e.items.entrySet()) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    sb.append(it.getKey()).append('=').append(it.getValue());
                }
                sb.append('\n');
            }
            Files.createDirectories(f.getParent());
            Files.writeString(f, sb.toString(), StandardCharsets.UTF_8);
            dirty = false;
        } catch (Throwable t) {
            AutoMiner.LOGGER.warn("写 仓库索引.txt 失败", t);
        }
    }

    private static final Map<String, String> ALIAS = new LinkedHashMap<>();

    static {
        ALIAS.put("钻石", "diamond");
        ALIAS.put("铁", "iron");
        ALIAS.put("金", "gold");
        ALIAS.put("煤", "coal");
        ALIAS.put("红石", "redstone");
        ALIAS.put("青金石", "lapis");
        ALIAS.put("绿宝石", "emerald");
        ALIAS.put("石英", "quartz");
        ALIAS.put("铜", "copper");
        ALIAS.put("下界合金", "netherite");
        ALIAS.put("木头", "log");
        ALIAS.put("木板", "planks");
        ALIAS.put("石头", "cobblestone");
        ALIAS.put("食物", "bread");
        ALIAS.put("火把", "torch");
        ALIAS.put("镐", "pickaxe");
    }

    private static String normQuery(String q) {
        String t = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        if (t.startsWith("minecraft:")) {
            t = t.substring(10);
        }
        for (Map.Entry<String, String> a : ALIAS.entrySet()) {
            if (t.contains(a.getKey())) {
                return a.getValue();
            }
        }
        return t;
    }

    /** 找哪个容器里有这东西，从近到远列出来。 */
    public static String find(String query) {
        load();
        Minecraft mc = Minecraft.getInstance();
        String q = normQuery(query);
        if (q.isEmpty()) {
            return "要找什么？例如 /idx find 钻石";
        }
        BlockPos me = mc.player == null ? BlockPos.ZERO : mc.player.blockPosition();
        String myDim = mc.level == null ? "" : mc.level.dimension().identifier().toString();

        List<Entry> hits = new ArrayList<>();
        int total = 0;
        for (Entry e : INDEX.values()) {
            for (Map.Entry<String, Integer> it : e.items.entrySet()) {
                String path = it.getKey().contains(":")
                        ? it.getKey().substring(it.getKey().indexOf(':') + 1)
                        : it.getKey();
                if (path.contains(q)) {
                    total += it.getValue();
                    hits.add(e);
                    break;
                }
            }
        }
        if (hits.isEmpty()) {
            return "索引里没有「" + query + "」。走近箱子让它扫到（或 /idx scan 扫一圈）。";
        }
        hits.sort(Comparator.comparingDouble(e -> e.distSq(me)));
        StringBuilder sb = new StringBuilder();
        sb.append("「").append(query).append("」共 ").append(total).append(" 个，在 ")
                .append(hits.size()).append(" 个容器里：");
        int shown = 0;
        for (Entry e : hits) {
            if (shown >= 5) {
                break;
            }
            shown++;
            int count = 0;
            for (Map.Entry<String, Integer> it : e.items.entrySet()) {
                String path = it.getKey().contains(":")
                        ? it.getKey().substring(it.getKey().indexOf(':') + 1)
                        : it.getKey();
                if (path.contains(q)) {
                    count += it.getValue();
                }
            }
            int dist = (int) Math.round(Math.sqrt(e.distSq(me)));
            String same = e.dim.equals(myDim) ? "" : "（别的维度）";
            sb.append("\n  ").append(e.type).append(" ").append(e.x).append(',').append(e.y).append(',').append(e.z)
                    .append("  ").append(count).append(" 个  离你 ").append(dist).append(" 格").append(same);
        }
        if (hits.size() > shown) {
            sb.append("\n  …还有 ").append(hits.size() - shown).append(" 个");
        }
        return sb.toString();
    }

    public static String summary() {
        load();
        return "仓库索引：" + INDEX.size() + " 个容器（自动记录：" + (autoScan ? "开" : "关") + "）";
    }
}
