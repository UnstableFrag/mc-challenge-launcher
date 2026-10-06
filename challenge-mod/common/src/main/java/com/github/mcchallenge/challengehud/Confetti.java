package com.github.mcchallenge.challengehud;

import java.util.Random;

/**
 * Shared confetti burst used by the per-era {@code WinScreen}s. Era-agnostic:
 * rendering is delegated to a {@link Filler} so each MC version can use its own
 * fill primitive (GuiGraphics.fill vs AbstractGui.fill on a PoseStack).
 *
 * <p>Java 8 compatible (targets the 1.16.5 toolchain too): no lambdas, no var.
 */
public class Confetti {

    /** Fills a rectangle. Implemented per era (graphics.fill / poseStack fill). */
    public interface Filler {
        void fill(int x1, int y1, int x2, int y2, int color);
    }

    private static final int COUNT = 60;
    private static final int FADE_AFTER = 32;
    private static final int MAX_AGE = 60;
    private static final int[] PALETTE = {
        0xFF89B4FA, 0xFFA6E3A1, 0xFFF9E2AF, 0xFFFFFFFF
    };
    private static final Random RNG = new Random();

    private final float[] px = new float[COUNT];
    private final float[] py = new float[COUNT];
    private final float[] vx = new float[COUNT];
    private final float[] vy = new float[COUNT];
    private final float[] size = new float[COUNT];
    private final int[] color = new int[COUNT];
    private final int[] age = new int[COUNT];

    public Confetti(int panelW) {
        for (int i = 0; i < COUNT; i++) {
            spawn(i, panelW);
        }
    }

    private void spawn(int i, int panelW) {
        px[i] = RNG.nextInt(Math.max(1, panelW));
        py[i] = -10f - RNG.nextInt(30);
        vx[i] = (RNG.nextFloat() - 0.5f) * 3f;
        vy[i] = 1f + RNG.nextFloat() * 2.5f;
        size[i] = 4f + RNG.nextInt(5);
        color[i] = PALETTE[RNG.nextInt(PALETTE.length)];
        age[i] = 0;
    }

    /** Advances the particles one frame (falling + staggered cascade respawn). */
    public void update(int panelW) {
        for (int i = 0; i < COUNT; i++) {
            if (age[i] > MAX_AGE) {
                // Cascade: an aged particle respawns from the top once its rolled
                // "chance" triggers, keeping the burst continuously populated.
                if (RNG.nextInt(30) == 0) {
                    spawn(i, panelW);
                }
                continue;
            }
            age[i]++;
            px[i] += vx[i];
            py[i] += vy[i];
            vy[i] += 0.15f;
        }
    }

    /** Draws all live particles; fades alpha after {@value FADE_AFTER} frames. */
    public void render(Filler filler) {
        for (int i = 0; i < COUNT; i++) {
            if (age[i] <= 0) continue;
            int alpha = 255;
            if (age[i] > FADE_AFTER) {
                alpha = Math.max(0, 255 - (age[i] - FADE_AFTER) * 12);
            }
            int col = (color[i] & 0xFFFFFF) | (alpha << 24);
            int s = (int) size[i];
            filler.fill((int) px[i], (int) py[i], (int) px[i] + s, (int) py[i] + s, col);
        }
    }
}
