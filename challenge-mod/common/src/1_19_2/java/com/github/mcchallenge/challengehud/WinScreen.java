package com.github.mcchallenge.challengehud;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.awt.Color;
import java.time.Duration;

public class WinScreen extends Screen {
    private final Duration time;
    private final ItemStack stack;
    private int ticks = 0;

    public WinScreen(Duration time, ItemStack stack) {
        super(EraBridge.text("Challenge Complete!"));
        this.time = time;
        this.stack = stack;
    }

    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float delta) {
        renderBackground(poseStack);
        int cx = width / 2, cy = height / 2;

        for (int i = 0; i < 30; i++) {
            float angle = (float) (Math.random() * Math.PI * 2);
            float dist = 100 + ticks * 0.5f + (float) (Math.random() * 200);
            int px = cx + (int) (Math.cos(angle) * dist);
            int py = cy + (int) (Math.sin(angle) * dist);
            int color = Color.HSBtoRGB((ticks * 0.01f + i * 0.03f) % 1f, 1f, 1f) | 0xFF000000;
            fill(poseStack, px, py, px + 4, py + 4, color);
        }

        minecraft.getItemRenderer().renderGuiItem(stack, cx - 16, cy - 112);

        String title = "🏁  CHALLENGE COMPLETE!";
        drawString(poseStack, font, title, cx - font.width(title) / 2, cy - 60, 0x00FF00);

        String itemName = stack.getHoverName().getString();
        drawString(poseStack, font, "Item: " + itemName, cx - font.width("Item: " + itemName) / 2, cy - 20, 0xFFFFFF);

        String timeStr = String.format("Time: %02d:%02d.%02d",
            time.toMinutes(), time.getSeconds() % 60, (time.toMillis() % 1000) / 10);
        drawString(poseStack, font, timeStr, cx - font.width(timeStr) / 2, cy + 10, 0x89B4FA);

        String hint = "Closing in " + (5 - ticks / 20) + "s...";
        drawString(poseStack, font, hint, cx - font.width(hint) / 2, cy + 50, 0xA6ADC8);
    }

    @Override
    public boolean shouldCloseOnEsc() { return false; }

    @Override
    public void tick() {
        ticks++;
        if (ticks > 100) {
            minecraft.setScreen(null);
            minecraft.execute(() -> minecraft.stop());
        }
    }
}
