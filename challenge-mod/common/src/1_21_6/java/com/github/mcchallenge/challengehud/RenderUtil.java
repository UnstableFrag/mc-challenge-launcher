package com.github.mcchallenge.challengehud;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;

/**
 * Era helpers for MC 1.21.6+ (Matrix3x2fStack-based rendering).
 *
 * <p>Important: since 1.21.6 GuiGraphics.renderItem SNAPSHOTS the current pose matrix
 * into a deferred {@code GuiItemRenderState} and submits it (see GuiGraphics.renderItem:
 * {@code new Matrix3x2f(pose)} followed by {@code guiRenderState.submitItem(...)}).
 * Any pushMatrix/translate/scale wrapped around the call is baked into that deferred
 * state and, combined with the new deferred GuiRenderState, breaks the frame — the item
 * renders tiny/blinking and can clobber the pose for subsequent draws. Items are drawn
 * statically instead.
 */
public final class RenderUtil {
    private RenderUtil() {}

    public static void drawItem(GuiGraphics graphics, ItemStack stack, int x, int y) {
        graphics.renderItem(stack, x, y);
    }
}
