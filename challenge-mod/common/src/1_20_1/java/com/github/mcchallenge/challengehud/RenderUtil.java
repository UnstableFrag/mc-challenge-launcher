package com.github.mcchallenge.challengehud;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;

/** Era helpers for MC 1.20.1 (PoseStack-based rendering). */
public final class RenderUtil {
    private RenderUtil() {}

    public static void drawPulsingItem(GuiGraphics graphics, ItemStack stack, int x, int y, float pulse) {
        graphics.pose().pushPose();
        graphics.pose().translate(x + 16.0F, y + 16.0F, 0.0F);
        graphics.pose().scale(pulse, pulse, 1.0F);
        graphics.pose().translate(-16.0F, -16.0F, 0.0F);
        graphics.renderItem(stack, 0, 0);
        graphics.pose().popPose();
    }
}
