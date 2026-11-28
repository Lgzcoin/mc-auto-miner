package com.doubao.autominer;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;

public class AutoMiner implements ClientModInitializer {
    public static final String MOD_ID = "autominer";
    public static final String MOD_VERSION = "1.15.6";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static MinerConfig config;
    private static MinerController controller;
    private static int hotTicks = 0;

    public static MinerConfig config() {
        return config;
    }

    public static MinerController controller() {
        return controller;
    }

    public static void reloadConfig() {
        config = MinerConfig.load();
        controller = new MinerController(config);
        GuiLogin.reset();
    }

    @Override
    public void onInitializeClient() {
        reloadConfig();
        AutoAuth.init();
        AutoMinerCommands.register();
        AutoMinerKeys.register();

        if (MinerConfig.lastModified() < 0) {
            MinerConfig.touchLastModified();
        }

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            hotTicks++;
            if (hotTicks % 20 == 0) {
                checkConfigReload();
            }
            ChestPicker.refresh(client);
            controller.tick();
            CommandBridge.tick(client);
            AutoAuth.tick(client);
            Onboard.tick(client);
            AutoJoin.tick(client);
            if (!MacroManager.isBusy()) {
                GuiLogin.tick(client);
            }
            MacroManager.tickHotkeys(client);
            MacroManager.tick(client);
            StatusWriter.tick(client);
            Warehouse.tick(client);
            EyeLocator.tick(client);

            if (AutoMinerKeys.openMenu != null) {
                while (AutoMinerKeys.openMenu.consumeClick()) {
                    if (client.gui.screen() == null) {
                        client.gui.setScreen(new MinerScreen());
                    }
                }
            }
            if (AutoMinerKeys.addChest != null) {
                while (AutoMinerKeys.addChest.consumeClick()) {
                    ChestPicker.addLastTarget(true);
                }
            }
        });

        LOGGER.info("自动采矿助手 v{} 已加载（男中音可用: {}）", MOD_VERSION, BaritoneHook.available());
    }
    /** 配置文件被外部改过（手改 json 或控制台写了新值）就自动重载，不用重启游戏。 */
    private static void checkConfigReload() {
        try {
            var path = MinerConfig.configPath();
            if (!Files.exists(path)) {
                return;
            }
            long m = Files.getLastModifiedTime(path).toMillis();
            if (m != MinerConfig.lastModified()) {
                reloadConfig();
                Onboard.reset();
                AutoJoin.reset();
                GuiLogin.reset();
                LOGGER.info("autominer.json 有变化，已自动重载（正在跑的挖矿会重置）。");
            }
        } catch (Exception e) {
            LOGGER.warn("热重载检查失败", e);
        }
    }
}
