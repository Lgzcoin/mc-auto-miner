package com.doubao.autominer;

import java.util.ArrayList;
import java.util.List;

/**
 * 宏文件格式（纯文本，方便手改）：
 * #TITLE=界面标题
 * CLICK|0.52|0.63|0|0|1200     x比例|y比例|按键(0左1右2中)|修饰键|距离上一步的毫秒
 * KEY|70|1|500                键码|修饰键|距离上一步（1=Shift，2=Ctrl，4=Alt，8=Super）
 * WAIT|1500
 *
 * 旧版 CLICK|...|按键|延时 和 KEY|键码|保留|延时 仍然能读。
 */
public final class MacroPlan {
    public record Step(String kind, double x, double y, int button, int key, int mods, long delay) {
    }

    public static final class Plan {
        public String title = "";
        public final List<Step> steps = new ArrayList<>();
    }

    private MacroPlan() {
    }

    public static Plan parse(String text) {
        Plan plan = new Plan();
        if (text == null) {
            return plan;
        }
        for (String raw : text.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#TITLE=")) {
                plan.title = line.substring("#TITLE=".length()).trim();
                continue;
            }
            if (line.startsWith("#")) {
                continue;
            }
            String[] p = line.split("\\|");
            try {
                if (p[0].equals("CLICK") && p.length >= 5) {
                    int button = Integer.parseInt(p[3]);
                    int mods = 0;
                    long delay;
                    if (p.length >= 6) {
                        mods = Integer.parseInt(p[4]);
                        delay = Long.parseLong(p[5]);
                    } else {
                        delay = Long.parseLong(p[4]);
                    }
                    plan.steps.add(new Step("CLICK", Double.parseDouble(p[1]), Double.parseDouble(p[2]),
                            button, 0, mods, Math.max(0, delay)));
                } else if (p[0].equals("KEY") && p.length >= 4) {
                    int mods = Integer.parseInt(p[2]);
                    plan.steps.add(new Step("KEY", 0, 0, 0, Integer.parseInt(p[1]), mods,
                            Math.max(0, Long.parseLong(p[3]))));
                } else if (p[0].equals("WAIT") && p.length >= 2) {
                    plan.steps.add(new Step("WAIT", 0, 0, 0, 0, 0, Math.max(0, Long.parseLong(p[1]))));
                }
            } catch (Throwable ignored) {
            }
        }
        return plan;
    }

    public static String toText(String title, List<Step> steps) {
        StringBuilder sb = new StringBuilder();
        sb.append("#TITLE=").append(title == null ? "" : title).append('\n');
        for (Step s : steps) {
            switch (s.kind()) {
                case "CLICK" -> sb.append("CLICK|").append(fmt(s.x())).append('|').append(fmt(s.y()))
                        .append('|').append(s.button()).append('|').append(s.mods())
                        .append('|').append(s.delay()).append('\n');
                case "KEY" -> sb.append("KEY|").append(s.key()).append('|').append(s.mods())
                        .append('|').append(s.delay()).append('\n');
                default -> sb.append("WAIT|").append(s.delay()).append('\n');
            }
        }
        return sb.toString();
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.4f", v);
    }
}
