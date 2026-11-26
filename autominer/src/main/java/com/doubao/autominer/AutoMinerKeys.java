package com.doubao.autominer;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

public final class AutoMinerKeys {
    public static KeyMapping openMenu;
    public static KeyMapping addChest;
    public static KeyMapping macroRecord;
    public static KeyMapping macroPlay;
    public static KeyMapping macroAuto;

    private AutoMinerKeys() {
    }

    public static void register() {
        openMenu = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.autominer.menu",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_M,
                KeyMapping.Category.MISC));
        addChest = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.autominer.add_chest",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_C,
                KeyMapping.Category.MISC));
        macroRecord = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.autominer.macro_record",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_F7,
                KeyMapping.Category.MISC));
        macroPlay = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.autominer.macro_play",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_F8,
                KeyMapping.Category.MISC));
        macroAuto = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.autominer.macro_auto",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_F9,
                KeyMapping.Category.MISC));
    }
}
