package com.github.mcchallenge.challengehud;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class TimeoutScreen extends Screen {
    private int ticks = 0;

    public TimeoutScreen() {
        super(Component.literal("Challenge Failed!"));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        renderBackground(graphics, mouseX, mouseY, delta);
        int cx = width / 2, cy = height / 2;

        String title = "⏰  CHALLENGE FAILED!";
        graphics.drawString(font, title, cx - font.width(title) / 2, cy - 30, 0xFF0000);

        String hint = "Time's up! Closing in " + (5 - ticks / 20) + "s...";
        graphics.drawString(font, hint, cx - font.width(hint) / 2, cy + 10, 0xA6ADC8);
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
