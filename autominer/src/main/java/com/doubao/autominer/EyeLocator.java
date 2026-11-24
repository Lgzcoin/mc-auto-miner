package com.doubao.autominer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.EyeOfEnder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 末影之眼三角定位：自动记录每次扔出去的末影之眼飞的方向，
 * 两次以上就能算出射线的交点 ≈ 要塞位置（不需要 OP、不需要种子）。
 */
public final class EyeLocator {
    private static class Track {
        double sx, sy, sz;
        double lx, ly, lz;
        int age;
    }

    private static class Ray {
        double x, z, dx, dz;
        long when;
    }

    /** 最近一次算出来的坐标，给命令层算距离用。 */
    public static double LAST_X = Double.NaN;
    public static double LAST_Z = Double.NaN;

    private static final Map<Integer, Track> TRACKS = new HashMap<>();
    private static final List<Ray> RAYS = new ArrayList<>();

    private EyeLocator() {
    }

    public static int rayCount() {
        return RAYS.size();
    }

    public static void clear() {
        RAYS.clear();
        TRACKS.clear();
    }

    public static void tick(Minecraft mc) {
        ClientLevel level = mc.level;
        if (level == null) {
            TRACKS.clear();
            return;
        }
        Set<Integer> seen = new HashSet<>();
        try {
            for (Entity e : level.entitiesForRendering()) {
                if (!(e instanceof EyeOfEnder)) {
                    continue;
                }
                int id = e.getId();
                seen.add(id);
                Track t = TRACKS.get(id);
                if (t == null) {
                    t = new Track();
                    t.sx = e.getX();
                    t.sy = e.getY();
                    t.sz = e.getZ();
                    t.lx = t.sx;
                    t.ly = t.sy;
                    t.lz = t.sz;
                    TRACKS.put(id, t);
                    continue;
                }
                t.age++;
                t.lx = e.getX();
                t.ly = e.getY();
                t.lz = e.getZ();
                if (t.age > 240) {
                    finalizeTrack(t);
                    TRACKS.remove(id);
                }
            }
        } catch (Throwable ignore) {
        }
        // 眼睛消失（落地/被移除）时结算
        Iterator<Map.Entry<Integer, Track>> it = TRACKS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Track> en = it.next();
            if (!seen.contains(en.getKey())) {
                finalizeTrack(en.getValue());
                it.remove();
            }
        }
    }

    private static void finalizeTrack(Track t) {
        double ddx = t.lx - t.sx;
        double ddz = t.lz - t.sz;
        double len = Math.sqrt(ddx * ddx + ddz * ddz);
        if (len < 3.0 || t.age < 4) {
            return;
        }
        Ray r = new Ray();
        r.x = t.sx;
        r.z = t.sz;
        r.dx = ddx / len;
        r.dz = ddz / len;
        r.when = System.currentTimeMillis();
        // 同一条方向的重复记录没意义，丢接近的
        for (Ray old : RAYS) {
            if (Math.abs(old.dx * r.dx + old.dz * r.dz) > 0.995
                    && Math.abs(old.x - r.x) < 8 && Math.abs(old.z - r.z) < 8) {
                return;
            }
        }
        RAYS.add(r);
        AutoMiner.LOGGER.info("记录末影之眼方向 {} -> 长度 {}", (int) r.x + "," + (int) r.z, (int) len);
    }

    /** 两两求交点，返回估算坐标。 */
    public static String calc() {
        if (RAYS.size() < 2) {
            return "现在只有 " + RAYS.size() + " 条眼睛轨迹。扔两次以上（最好从两个相隔较远的位置），再 /eye calc。";
        }
        List<double[]> points = new ArrayList<>();
        for (int i = 0; i < RAYS.size(); i++) {
            for (int j = i + 1; j < RAYS.size(); j++) {
                Ray a = RAYS.get(i);
                Ray b = RAYS.get(j);
                double cross = a.dx * b.dz - b.dx * a.dz;
                if (Math.abs(cross) < 0.25) {
                    continue; // 夹角太小，交点会飘
                }
                double rx = b.x - a.x;
                double rz = b.z - a.z;
                double det = -cross;
                double t = (-rx * b.dz + b.dx * rz) / det;
                double s = (a.dx * rz - a.dz * rx) / det;
                if (t <= 0 || s <= 0) {
                    continue;
                }
                points.add(new double[]{a.x + a.dx * t, a.z + a.dz * t});
            }
        }
        if (points.isEmpty()) {
            return "这几条眼睛方向几乎平行，算不出交点。换两个角度差大一点的位置再扔。";
        }
        double mx = 0, mz = 0;
        for (double[] p : points) {
            mx += p[0];
            mz += p[1];
        }
        mx /= points.size();
        mz /= points.size();
        LAST_X = mx;
        LAST_Z = mz;
        double worst = 0;
        for (double[] p : points) {
            double d = Math.hypot(p[0] - mx, p[1] - mz);
            if (d > worst) {
                worst = d;
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("估算要塞在 X=").append((int) Math.round(mx)).append(" Z=").append((int) Math.round(mz));
        sb.append("\n用 ").append(RAYS.size()).append(" 条轨迹、").append(points.size())
                .append(" 个交点，点位散布约 ±").append((int) Math.round(worst)).append(" 格。");
        if (worst > 300) {
            sb.append("\n散布有点大：再找一个远一点、角度不同的位置扔一次，然后 /eye calc。");
        }
        return sb.toString();
    }

    public static String list() {
        if (RAYS.isEmpty()) {
            return "还没有记录到眼睛。站着扔一个末影之眼，等它飞走，模组会自动记方向。";
        }
        StringBuilder sb = new StringBuilder("已记录 " + RAYS.size() + " 条眼睛轨迹：");
        int n = 0;
        for (Ray r : RAYS) {
            n++;
            if (n > 8) {
                break;
            }
            sb.append("\n  ").append(n).append(". 起点 ").append((int) r.x).append(',').append((int) r.z)
                    .append(" 方向 ").append((int) Math.round(r.dx * 100)).append("% / ")
                    .append((int) Math.round(r.dz * 100)).append("%");
        }
        return sb.toString();
    }
}
