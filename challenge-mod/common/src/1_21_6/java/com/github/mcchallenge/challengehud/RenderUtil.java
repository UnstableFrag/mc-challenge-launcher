package com.github.mcchallenge.challengehud;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;

/** Era helpers for MC 1.21.6+ (Matrix3x2fStack-based rendering). */
public final class RenderUtil {
    private RenderUtil() {}

    public static void drawPulsingItem(GuiGraphics graphics, ItemStack stack, int x, int y, float pulse) {
        var pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(x + 16.0F, y + 16.0F);
        pose.scale(pulse, pulse);
        pose.translate(-16.0F, -16.0F);
        graphics.renderItem(stack, 0, 0);
        pose.popMatrix();
    }
}
