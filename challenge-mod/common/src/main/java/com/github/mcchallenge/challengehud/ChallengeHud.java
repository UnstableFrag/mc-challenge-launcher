package com.github.mcchallenge.challengehud;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import dev.architectury.event.events.client.ClientGuiEvent;
import dev.architectury.event.events.client.ClientTickEvent;
import dev.architectury.platform.Platform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class ChallengeHud {
    public static final String MOD_ID = "challengehud";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final long CHALLENGE_TICKS = 6000; // 5 min (20 ticks/sec * 300 sec)
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Random RNG = new Random();

    private static Minecraft client = Minecraft.getInstance();
    private static ItemStack targetStack = ItemStack.EMPTY;
    private static long deadlineTicks = 0;
    private static boolean active = false;
    private static boolean completed = false;
    private static Instant startTime;
    private static String playerName = "";
    private static ChallengeType challengeType = ChallengeType.ITEM;

    enum ChallengeType { ITEM, MOB_KILL, EXPLORE }

    /** Called from the platform entrypoints (fabric/forge/neoforge) on client setup. */
    public static void init() {
        ClientTickEvent.CLIENT_POST.register(ChallengeHud::tick);
        ClientGuiEvent.RENDER_HUD.register((graphics, delta) -> render(graphics));
        loadConfig();
        LOGGER.info("Challenge HUD loaded");
    }

    private static void pickRandomTarget() {
        List<Item> candidates = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM.stream().toList()) {
            if (item == Items.AIR) continue;
            var id = BuiltInRegistries.ITEM.getKey(item);
            String path = id.getPath();
            if (path.contains("spawn_egg")) continue;
            if (path.contains("command_block")) continue;
            if (path.contains("barrier")) continue;
            if (path.contains("debug")) continue;
            if (path.equals("light")) continue;
            if (path.startsWith("knowledge_book")) continue;
            candidates.add(item);
        }

        if (candidates.isEmpty()) {
            targetStack = new ItemStack(Items.DIAMOND);
            LOGGER.warn("No items found, defaulting to diamond");
        } else {
            Item picked = candidates.get(RNG.nextInt(candidates.size()));
            targetStack = new ItemStack(picked);
            LOGGER.info("Random target: {} ({})", BuiltInRegistries.ITEM.getKey(picked),
                new ItemStack(picked).getHoverName().getString());
        }
    }

    private static void tick(Minecraft client) {
        if (client.player == null || !active || completed) return;

        if (deadlineTicks > 0 && client.level.getGameTime() >= deadlineTicks) {
            timeout(client);
            return;
        }

        switch (challengeType) {
            case ITEM -> {
                if (client.player.getInventory().countItem(targetStack.getItem()) > 0) {
                    complete(client);
                }
            }
            case MOB_KILL -> {}
            case EXPLORE -> {}
        }
    }

    private static void timeout(Minecraft client) {
        if (completed) return;
        completed = true;
        active = false;
        client.execute(() -> client.setScreen(new TimeoutScreen()));
        LOGGER.info("Challenge timed out!");
    }

    private static void render(GuiGraphics graphics) {
        if (client == null || client.player == null || !active || targetStack.isEmpty()) return;

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

        RenderUtil.drawPulsingItem(graphics, targetStack, itemX, itemY, pulse);

        String name = targetStack.getHoverName().getString();
        graphics.drawString(client.font, name,
            panelX + panelW / 2 - client.font.width(name) / 2, itemY + 40, 0xFFFFFF);

        if (startTime != null) {
            Duration elapsed = Duration.between(startTime, Instant.now());
            String time = String.format("%02d:%02d.%02d",
                elapsed.toMinutes(), elapsed.getSeconds() % 60, elapsed.toMillisPart() / 10);
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

    private static void complete(Minecraft client) {
        if (completed) return;
        completed = true;
        active = false;

        Duration elapsed = Duration.between(startTime, Instant.now());
        playerName = client.player.getName().getString();

        writeResult(elapsed);

        client.execute(() -> client.setScreen(new WinScreen(elapsed, targetStack)));

        LOGGER.info("Challenge completed by {} in {}", playerName, elapsed);
    }

    private static void writeResult(Duration elapsed) {
        try {
            File configDir = new File(Platform.getConfigFolder().toFile(), "challenge");
            configDir.mkdirs();
            File resultFile = new File(configDir, "challenge_result.json");

            JsonObject obj = new JsonObject();
            obj.addProperty("item", BuiltInRegistries.ITEM.getKey(targetStack.getItem()).toString());
            obj.addProperty("player", playerName);
            obj.addProperty("time_ticks", elapsed.toMillis() / 50);
            obj.addProperty("time_ms", elapsed.toMillis());

            try (FileWriter fw = new FileWriter(resultFile)) {
                GSON.toJson(obj, fw);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to write result", e);
        }
    }

    private static void loadConfig() {
        pickRandomTarget();
        startTime = Instant.now();
        if (client.level != null) {
            deadlineTicks = client.level.getGameTime() + CHALLENGE_TICKS;
        } else {
            deadlineTicks = CHALLENGE_TICKS;
        }
        active = true;
        completed = false;
        challengeType = ChallengeType.ITEM;
    }
}
