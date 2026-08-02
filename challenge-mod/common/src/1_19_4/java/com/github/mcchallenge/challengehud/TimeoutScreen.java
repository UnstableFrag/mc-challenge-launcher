package com.github.mcchallenge.challengehud;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.screens.Screen;

public class TimeoutScreen extends Screen {
    private int ticks = 0;

    public TimeoutScreen() {
        super(EraBridge.text("Challenge Failed!"));
    }

    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float delta) {
        renderBackground(poseStack);
        int cx = width / 2, cy = height / 2;

        String title = "⏰  CHALLENGE FAILED!";
        drawString(poseStack, font, title, cx - font.width(title) / 2, cy - 30, 0xFF0000);

        String hint = "Time's up! Closing in " + (5 - ticks / 20) + "s...";
        drawString(poseStack, font, hint, cx - font.width(hint) / 2, cy + 10, 0xA6ADC8);
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
