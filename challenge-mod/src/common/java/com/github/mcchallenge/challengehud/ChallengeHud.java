package com.github.mcchallenge.challengehud;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.architectury.event.events.client.ClientTickEvent;
import dev.architectury.event.events.client.HudRenderCallback;
import dev.architectury.platform.Platform;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import com.google.gson.*;

public class ChallengeHud implements ClientModInitializer {
    public static final String MOD_ID = "challengehud";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static ItemStack targetStack = ItemStack.EMPTY;
    private static long deadlineTicks = 0;
    private static boolean active = false;
    private static boolean completed = false;
    private static Instant startTime;
    private static String playerName = "";
    private static ChallengeType challengeType = ChallengeType.ITEM;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static MinecraftClient client = MinecraftClient.getInstance();

    enum ChallengeType { ITEM, MOB_KILL, EXPLORE }

    @Override
    public void onInitializeClient() {
        ClientTickEvent.END_CLIENT_TICK.register(this::tick);
        HudRenderCallback.EVENT.register(this::render);
        loadConfig();
        LOGGER.info("Challenge HUD loaded");
    }

    private void tick(MinecraftClient client) {
        if (client.player == null || !active || completed) return;

        if (deadlineTicks > 0 && client.world.getTime() >= deadlineTicks) {
            timeout(client);
            return;
        }

        switch (challengeType) {
            case ITEM -> {
                if (client.player.getInventory().containsAny(stack -> ItemStack.areItemsEqual(stack, targetStack))) {
                    complete(client);
                }
            }
            case MOB_KILL -> {
                // handled via tag matching in config
            }
            case EXPLORE -> {
            }
        }
    }

    private void timeout(MinecraftClient client) {
        if (completed) return;
        completed = true;
        active = false;
        client.execute(() -> client.setScreen(new TimeoutScreen()));
        LOGGER.info("Challenge timed out!");
    }

    private void render(DrawContext ctx, float tickDelta) {
        if (client == null || client.player == null || !active || targetStack.isEmpty()) return;

        int width = client.getWindow().getScaledWidth();
        int height = client.getWindow().getScaledHeight();

        int panelX = width - 240;
        int panelY = 40;
        int panelW = 220;
        int panelH = 180;

        ctx.fillGradient(panelX, panelY, panelX + panelW, panelY + panelH,
            0x99000000, 0xCC111111);
        ctx.drawBorder(panelX, panelY, panelW, panelH, 0xFF89B4FA);

        float pulse = (float) (Math.sin(client.world.getTime() * 0.1) * 0.1 + 1.0);
        int itemX = panelX + panelW / 2 - 16;
        int itemY = panelY + 20;

        ctx.getMatrices().push();
        ctx.getMatrices().translate(itemX + 16, itemY + 16, 0);
        ctx.getMatrices().scale(pulse, pulse, 1);
        ctx.getMatrices().translate(-16, -16, 0);
        ctx.drawItem(targetStack, 0, 0);
        ctx.getMatrices().pop();

        String name = targetStack.getName().getString();
        ctx.drawTextWithShadow(client.textRenderer, name,
            panelX + panelW / 2 - client.textRenderer.getWidth(name) / 2, itemY + 40, 0xFFFFFF);

        if (startTime != null) {
            Duration elapsed = Duration.between(startTime, Instant.now());
            String time = String.format("%02d:%02d.%02d",
                elapsed.toMinutes(), elapsed.getSeconds() % 60, elapsed.toMillisPart() / 10);
            ctx.drawTextWithShadow(client.textRenderer, "⏱ " + time,
                panelX + panelW / 2 - client.textRenderer.getWidth("⏱ " + time) / 2, itemY + 60, 0x89B4FA);

            drawProgressRing(ctx, panelX + panelW / 2, itemY + 90, 30,
                (elapsed.getSeconds() % 60) / 60f, 0x89B4FA);
        }

        String hint = switch (challengeType) {
            case ITEM -> "Get this item to win!";
            case MOB_KILL -> "Kill this mob to win!";
            case EXPLORE -> "Explore to win!";
        };
        ctx.drawTextWithShadow(client.textRenderer, hint,
            panelX + panelW / 2 - client.textRenderer.getWidth(hint) / 2,
            panelY + panelH - 25, 0xA6ADC8);
    }

    private void drawProgressRing(DrawContext ctx, int cx, int cy, int radius, float progress, int color) {
        int segments = 60;
        for (int i = 0; i < segments * progress; i++) {
            double angle = Math.toRadians(i * 360f / segments - 90);
            int x1 = cx + (int) (Math.cos(angle) * (radius - 2));
            int y1 = cy + (int) (Math.sin(angle) * (radius - 2));
            int x2 = cx + (int) (Math.cos(angle) * radius);
            int y2 = cy + (int) (Math.sin(angle) * radius);
            ctx.fill(x1, y1, x2, y2, color);
        }
    }

    private void complete(MinecraftClient client) {
        if (completed) return;
        completed = true;
        active = false;

        Duration elapsed = Duration.between(startTime, Instant.now());
        playerName = client.player.getName().getString();

        writeResult(elapsed);

        client.execute(() -> client.setScreen(new WinScreen(elapsed, targetStack)));

        LOGGER.info("Challenge completed by {} in {}", playerName, elapsed);
    }

    private void writeResult(Duration elapsed) {
        try {
            File configDir = new File(Platform.getConfigFolder().toFile(), "challenge");
            configDir.mkdirs();
            File resultFile = new File(configDir, "challenge_result.json");

            JsonObject obj = new JsonObject();
            obj.addProperty("item", Registries.ITEM.getId(targetStack.getItem()).toString());
            obj.addProperty("player", playerName);
            obj.addProperty("time_ticks", elapsed.toMillis() / 50);
            obj.addProperty("time_ms", elapsed.toMillis());
            obj.addProperty("type", challengeType.name());

            try (FileWriter fw = new FileWriter(resultFile)) {
                GSON.toJson(obj, fw);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to write result", e);
        }
    }

    public static void setTarget(ItemStack stack, long deadline) {
        targetStack = stack.copy();
        deadlineTicks = deadline;
        active = true;
        completed = false;
        startTime = Instant.now();
        challengeType = ChallengeType.ITEM;
    }

    public static void setTarget(String targetId, long deadline) {
        deadlineTicks = deadline;
        active = true;
        completed = false;
        startTime = Instant.now();
        challengeType = ChallengeType.ITEM;

        Identifier id = new Identifier(targetId);
        var item = Registries.ITEM.get(id);
        targetStack = item != null ? new ItemStack(item) : ItemStack.EMPTY;
        if (targetStack.isEmpty()) {
            LOGGER.warn("Item {} not found in registry, HUD will show empty", targetId);
        } else {
            LOGGER.info("Challenge target set to {}", targetStack.getName().getString());
        }
    }

    private void loadConfig() {
        try {
            File configFile = new File(Platform.getConfigFolder().toFile(), "challenge/challenge.json");
            if (configFile.exists()) {
                String json = Files.readString(configFile.toPath());
                JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
                String target = obj.get("target").getAsString();
                long deadline = obj.has("deadline") ? obj.get("deadline").getAsLong() : 0;
                String typeStr = obj.has("type") ? obj.get("type").getAsString() : "ITEM";
                try {
                    challengeType = ChallengeType.valueOf(typeStr.toUpperCase());
                } catch (IllegalArgumentException e) {
                    LOGGER.warn("Unknown challenge type '{}', defaulting to ITEM", typeStr);
                    challengeType = ChallengeType.ITEM;
                }
                setTarget(target, deadline);
            }
        } catch (Exception e) {
            LOGGER.warn("No challenge config found, waiting for launcher");
        }
    }

    private static class WinScreen extends Screen {
        private final Duration time;
        private final ItemStack stack;
        private int ticks = 0;

        WinScreen(Duration time, ItemStack stack) {
            super(Text.literal("Challenge Complete!"));
            this.time = time;
            this.stack = stack;
        }

        @Override
        public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
            renderBackground(ctx, mouseX, mouseY, delta);
            int cx = width / 2, cy = height / 2;

            for (int i = 0; i < 30; i++) {
                float angle = (float) (Math.random() * Math.PI * 2);
                float dist = 100 + ticks * 0.5f + (float) (Math.random() * 200);
                int px = cx + (int) (Math.cos(angle) * dist);
                int py = cy + (int) (Math.sin(angle) * dist);
                int color = Color.HSBtoRGB((ticks * 0.01f + i * 0.03f) % 1f, 1f, 1f) | 0xFF000000;
                ctx.fill(px, py, px + 4, py + 4, color);
            }

            String title = "🏁  CHALLENGE COMPLETE!";
            ctx.drawTextWithShadow(textRenderer, title, cx - textRenderer.getWidth(title) / 2, cy - 60, 0x00FF00);

            String itemName = stack.getName().getString();
            ctx.drawTextWithShadow(textRenderer, "Item: " + itemName, cx - textRenderer.getWidth("Item: " + itemName) / 2, cy - 20, 0xFFFFFF);

            String timeStr = String.format("Time: %02d:%02d.%02d",
                time.toMinutes(), time.getSeconds() % 60, time.toMillisPart() / 10);
            ctx.drawTextWithShadow(textRenderer, timeStr, cx - textRenderer.getWidth(timeStr) / 2, cy + 10, 0x89B4FA);

            String hint = "Closing in " + (5 - ticks / 20) + "s...";
            ctx.drawTextWithShadow(textRenderer, hint, cx - textRenderer.getWidth(hint) / 2, cy + 50, 0xA6ADC8);
        }

        @Override
        public boolean shouldCloseOnEsc() { return false; }

        @Override
        public void tick() {
            ticks++;
            if (ticks > 100) close();
        }
    }

    private static class TimeoutScreen extends Screen {
        private int ticks = 0;

        TimeoutScreen() {
            super(Text.literal("Challenge Failed!"));
        }

        @Override
        public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
            renderBackground(ctx, mouseX, mouseY, delta);
            int cx = width / 2, cy = height / 2;

            String title = "⏰  CHALLENGE FAILED!";
            ctx.drawTextWithShadow(textRenderer, title, cx - textRenderer.getWidth(title) / 2, cy - 30, 0xFF0000);

            String hint = "Time's up! Closing in " + (5 - ticks / 20) + "s...";
            ctx.drawTextWithShadow(textRenderer, hint, cx - textRenderer.getWidth(hint) / 2, cy + 10, 0xA6ADC8);
        }

        @Override
        public boolean shouldCloseOnEsc() { return false; }

        @Override
        public void tick() {
            ticks++;
            if (ticks > 100) close();
        }
    }
}