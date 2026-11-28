package com.doubao.autominer;

/**
 * 可以挂自定义指令/脚本的生命周期事件。
 */
public enum HookEvent {
    START("start", "开始挖矿", "真正开始挖矿前执行"),
    PRE_DEPOSIT("pre_deposit", "回箱前", "所有回箱条件命中后、走向箱子前"),
    FULL("full", "背包满/爆仓", "背包空位不足触发回箱时"),
    LOW_PICK("low_pick", "镐子快坏", "镐子耐久低于阈值触发回箱时"),
    INTERVAL("interval", "定时回箱", "定时回箱触发时"),
    THRESHOLD("threshold", "攒够矿物", "指定矿物攒到数量触发回箱时"),
    POST_DEPOSIT("post_deposit", "回箱后", "存完矿/关箱后、继续挖矿前"),
    MINING_DONE("mining_done", "挖完/找不到矿", "附近没有可挖矿石、准备暂停时"),
    STOP("stop", "停止挖矿", "手动或自动停止时");

    public final String key;
    public final String label;
    public final String description;

    HookEvent(String key, String label, String description) {
        this.key = key;
        this.label = label;
        this.description = description;
    }

    public static HookEvent byKey(String key) {
        if (key != null) {
            for (HookEvent event : values()) {
                if (event.key.equals(key)) {
                    return event;
                }
            }
        }
        return null;
    }
}
