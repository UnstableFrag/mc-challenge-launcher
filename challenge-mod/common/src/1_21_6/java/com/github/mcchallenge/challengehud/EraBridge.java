package com.github.mcchallenge.challengehud;

import dev.architectury.event.events.client.ClientGuiEvent;
import dev.architectury.event.events.client.ClientTickEvent;
import dev.architectury.platform.Platform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Era bridge for MC 1.21.6+ (GuiGraphics HUD, Matrix3x2fStack item pulse,
 * Component.literal, BuiltInRegistries.ITEM, architectury 17.x+ dev.* packages).
 */
public final class EraBridge {
    private EraBridge() {}

    public static void registerEvents() {
        ClientTickEvent.CLIENT_POST.register(ChallengeHud::tick);
        // 2nd lambda param is DeltaTracker (arch 17.x+) — ignored on purpose.
        ClientGuiEvent.RENDER_HUD.register((graphics, delta) -> renderHud(graphics));
    }

    public static void renderHud(GuiGraphics graphics) {
        Minecraft client = ChallengeHud.client;
        if (client == null || client.player == null || !ChallengeHud.active || ChallengeHud.targetStack.isEmpty()) return;

        int width = client.getWindow().getGuiScaledWidth();
        int height = client.getWindow().getGuiScaledHeight();

        int panelX = width - 240;
        int panelY = 40;
        int panelW = 220;
        int panelH = 180;

        graphics.fillGradient(panelX, panelY, panelX + panelW, panelY + panelH,
            0x99000000, 0xCC111111);
        // 1px border via fills — renderOutline was renamed submitOutline in 1.21.9/1.21.10
        int bx = panelX, by = panelY, bw = panelW, bh = panelH;
        graphics.fill(bx, by, bx + bw, by + 1, 0xFF89B4FA);
        graphics.fill(bx, by + bh - 1, bx + bw, by + bh, 0xFF89B4FA);
        graphics.fill(bx, by, bx + 1, by + bh, 0xFF89B4FA);
        graphics.fill(bx + bw - 1, by, bx + bw, by + bh, 0xFF89B4FA);

        float pulse = (float) (Math.sin(client.level.getGameTime() * 0.1) * 0.1 + 1.0);
        int itemX = panelX + panelW / 2 - 16;
        int itemY = panelY + 20;

        RenderUtil.drawPulsingItem(graphics, ChallengeHud.targetStack, itemX, itemY, pulse);

        String name = ChallengeHud.targetStack.getHoverName().getString();
        graphics.drawString(client.font, name,
            panelX + panelW / 2 - client.font.width(name) / 2, itemY + 40, 0xFFFFFF);

        if (ChallengeHud.startTime != null) {
            Duration elapsed = Duration.between(ChallengeHud.startTime, Instant.now());
            String time = String.format("%02d:%02d.%02d",
                elapsed.toMinutes(), elapsed.getSeconds() % 60, (elapsed.toMillis() % 1000) / 10);
            graphics.drawString(client.font, "⏱ " + time,
                panelX + panelW / 2 - client.font.width("⏱ " + time) / 2, itemY + 60, 0x89B4FA);

            drawProgressRing(graphics, panelX + panelW / 2, itemY + 90, 30,
                (elapsed.getSeconds() % 60) / 60f, 0x89B4FA);
        }

        graphics.drawString(client.font, "Get this item to win!",
            panelX + panelW / 2 - client.font.width("Get this item to win!") / 2,
            panelY + panelH - 25, 0xA6ADC8);
    }

    private static void drawProgressRing(GuiGraphics graphics, int cx, int cy, int radius, float progress, int color) {
        int segments = 60;
        for (int i = 0; i < segments * progress; i++) {
            double angle = Math.toRadians(i * 360f / segments - 90);
            int x1 = cx + (int) (Math.cos(angle) * (radius - 2));
            int y1 = cy + (int) (Math.sin(angle) * (radius - 2));
            int x2 = cx + (int) (Math.cos(angle) * radius);
            int y2 = cy + (int) (Math.sin(angle) * radius);
            graphics.fill(x1, y1, x2, y2, color);
        }
    }

    public static Path configDir() {
        return Platform.getConfigFolder();
    }

    public static List<Item> items() {
        return BuiltInRegistries.ITEM.stream().collect(Collectors.toList());
    }

    public static String itemId(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    public static String itemPath(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).getPath();
    }

    public static int countItem(Minecraft client, Item item) {
        return client.player.getInventory().countItem(item);
    }

    public static Component text(String s) {
        return Component.literal(s);
    }
}
