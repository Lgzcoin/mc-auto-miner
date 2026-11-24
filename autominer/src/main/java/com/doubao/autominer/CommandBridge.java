package com.doubao.autominer;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * 命令行窗口的“通信通道”：
 *   控制台把指令写进 <游戏目录>/autominer_cmd.txt
 *   本类每秒读一次、执行、清空，并把结果追加到 autominer_out.txt
 * 这样不用把指令塞进游戏窗口，也能远程指挥每个机器人。
 */
public final class CommandBridge {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static int cooldown = 0;
    private static final long MAX_OUT_BYTES = 256 * 1024L;

    private CommandBridge() {
    }

    private static Path cmdFile() {
        return FabricLoader.getInstance().getGameDir().resolve("autominer_cmd.txt");
    }

    private static Path outFile() {
        return FabricLoader.getInstance().getGameDir().resolve("autominer_out.txt");
    }

    public static void tick(Minecraft mc) {
        if (--cooldown > 0) {
            return;
        }
        cooldown = 20;

        Path cmd = cmdFile();
        if (!Files.isRegularFile(cmd)) {
            return;
        }
        String text;
        try {
            text = Files.readString(cmd, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return;
        }
        if (text.isBlank()) {
            return;
        }
        try {
            Files.writeString(cmd, "", StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // 清不掉就下次再说，宁可重复也不要丢
        }

        for (String line : text.split("\r?\n")) {
            if (line.isBlank()) {
                continue;
            }
            String result;
            try {
                result = AutoMinerApi.execute(line);
            } catch (Throwable t) {
                result = "执行出错：" + t.getClass().getSimpleName() + " " + t.getMessage();
                AutoMiner.LOGGER.warn("远程指令执行失败：" + line, t);
            }
            if (result != null && !result.isBlank()) {
                appendOut(line.trim(), result);
            }
        }
    }

    private static void appendOut(String command, String result) {
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(LocalTime.now().format(TIME)).append("] > ").append(command).append('\n');
        for (String resultLine : result.split("\r?\n")) {
            sb.append("    ").append(resultLine).append('\n');
        }
        try {
            Path out = outFile();
            if (Files.isRegularFile(out) && Files.size(out) > MAX_OUT_BYTES) {
                Files.writeString(out, "", StandardCharsets.UTF_8);
            }
            Files.writeString(out, sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            AutoMiner.LOGGER.warn("写 autominer_out.txt 失败", e);
        }
    }
}
