package com.github.mcchallenge.challengehud;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.ItemStack;

/**
 * Victory screen (MC 1.17.1 and 1.18.2): RTA display + NEW PB marker + shared confetti.
 * Blocks input and auto-closes the game after ~5 seconds. Java 8 source compatible.
 */
public class WinScreen extends Screen {
    private static final int COLOR_TEXT = 0xFFA6ADC8;
    private static final int COLOR_ACCENT = 0xFF89B4FA;
    private static final int COLOR_NAME = 0xFFFFFFFF;
    private static final int COLOR_SUCCESS = 0xFFA6E3A1;

    private final long timeMs;
    private final boolean newpb;
    private final ItemStack stack;
    private int ticks = 0;
    private Confetti confetti = null;

    public WinScreen(long timeMs, boolean newpb, ItemStack stack) {
        super(EraBridge.text("Challenge Complete!"));
        this.timeMs = timeMs;
        this.newpb = newpb;
        this.stack = stack;
    }

    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float delta) {
        renderBackground(poseStack);
        int cx = width / 2, cy = height / 2;

        if (confetti == null) {
            confetti = new Confetti(width);
        }
        confetti.update(width);
        confetti.render(new Confetti.Filler() {
            @Override
            public void fill(int x1, int y1, int x2, int y2, int color) {
                WinScreen.this.fill(poseStack, x1, y1, x2, y2, color);
            }
        });

        minecraft.getItemRenderer().renderGuiItem(stack, cx - 16, cy - 112);

        String title = EraBridge.translate("challengehud.screen.win.title");
        drawString(poseStack, font, title, cx - font.width(title) / 2, cy - 60, COLOR_SUCCESS);

        // RTA time (mm:ss.cs), matching the PB metric used for records.
        String timeLine = EraBridge.translate("challengehud.screen.win.time",
            ChallengeHud.formatTime(timeMs));
        drawString(poseStack, font, timeLine, cx - font.width(timeLine) / 2, cy - 24, COLOR_NAME);

        if (newpb) {
            String pb = EraBridge.translate("challengehud.screen.win.newpb");
            drawString(poseStack, font, pb, cx - font.width(pb) / 2, cy - 2, COLOR_ACCENT);
        }

        String hint = EraBridge.translate("challengehud.screen.closing", 5 - ticks / 20);
        drawString(poseStack, font, hint, cx - font.width(hint) / 2, cy + 50, COLOR_TEXT);
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
