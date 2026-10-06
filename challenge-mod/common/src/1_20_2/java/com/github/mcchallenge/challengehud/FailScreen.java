package com.github.mcchallenge.challengehud;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;

/**
 * Death screen shown when the player dies during an ACTIVE challenge.
 * Mirrors TimeoutScreen: blocks input, auto-closes the game after ~5 seconds.
 */
public class FailScreen extends Screen {
    private int ticks = 0;

    public FailScreen() {
        super(EraBridge.text("Challenge Failed!"));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        renderBackground(graphics, mouseX, mouseY, delta);
        int cx = width / 2, cy = height / 2;

        String title = EraBridge.translate("challengehud.screen.fail.title");
        graphics.drawString(font, title, cx - font.width(title) / 2, cy - 40, 0xFFFF0000);

        String subtitle = EraBridge.translate("challengehud.screen.fail.subtitle");
        graphics.drawString(font, subtitle, cx - font.width(subtitle) / 2, cy - 10, 0xFFFFFFFF);

        String hint = EraBridge.translate("challengehud.screen.closing", 5 - ticks / 20);
        graphics.drawString(font, hint, cx - font.width(hint) / 2, cy + 20, 0xFFA6ADC8);
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
