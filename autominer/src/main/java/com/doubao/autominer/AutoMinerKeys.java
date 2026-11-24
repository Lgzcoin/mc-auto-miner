package com.doubao.autominer;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

public final class AutoMinerKeys {
    public static KeyMapping openMenu;
    public static KeyMapping addChest;

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
    }
}
