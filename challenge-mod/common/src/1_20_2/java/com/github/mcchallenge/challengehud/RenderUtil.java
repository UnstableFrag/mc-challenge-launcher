package com.github.mcchallenge.challengehud;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;

/** Era helpers for MC 1.20.2..1.21.5 (PoseStack-based rendering). */
public final class RenderUtil {
    private RenderUtil() {}

    /** Static item render (no pose manipulation) used by the HUD panel. */
    public static void drawItem(GuiGraphics graphics, ItemStack stack, int x, int y) {
        graphics.renderItem(stack, x, y);
    }
}
