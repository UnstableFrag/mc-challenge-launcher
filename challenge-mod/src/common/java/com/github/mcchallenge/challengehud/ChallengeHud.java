package com.github.mcchallenge.challengehud;

import dev.architectury.event.events.client.ClientTickEvent;
import dev.architectury.event.events.client.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.io.File;
import java.io.FileWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
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
    private static final long CHALLENGE_TICKS = 6000; // 5 min (20 ticks/sec * 300 sec)

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static MinecraftClient client = MinecraftClient.getInstance();
    private static final Random RNG = new Random();

    enum ChallengeType { ITEM, MOB_KILL, EXPLORE }

    @Override
    public void onInitializeClient() {
        ClientTickEvent.END_CLIENT_TICK.register(this::tick);
        HudRenderCallback.EVENT.register(this::render);
        loadConfig();
        LOGGER.info("Challenge HUD loaded");
    }

    private void pickRandomTarget() {
        List<Item> candidates = new ArrayList<>();
        for (Item item : Registries.ITEM) {
            if (item == Items.AIR) continue;
            Identifier id = Registries.ITEM.getId(item);
            String path = id.getPath();
            if (path.contains("spawn_egg")) continue;
            if (path.contains("command_block")) continue;
            if (path.contains("barrier")) continue;
            if (path.contains("debug")) continue;
            if (path.contains("light") && path.equals("light")) continue;
            if (path.startsWith("knowledge_book")) continue;
            candidates.add(item);
        }

        if (candidates.isEmpty()) {
            targetStack = new ItemStack(Items.DIAMOND);
            LOGGER.warn("No items found, defaulting to diamond");
        } else {
            Item picked = candidates.get(RNG.nextInt(candidates.size()));
            targetStack = new ItemStack(picked);
            LOGGER.info("Random target: {} ({})", Registries.ITEM.getId(picked), picked.getName().getString());
        }
    }

    private void tick(MinecraftClient client) {
        if (client.player == null || !active || completed) return;

        if (deadlineTicks > 0 && client.world.getTime() >= deadlineTicks) {
            timeout(client);
            return;
        }

        switch (challengeType) {
            case ITEM -> {
                if (client.player.getInventory().containsAny(stack -> stack.isOf(targetStack.getItem()))) {
                    complete(client);
                }
            }
            case MOB_KILL -> {}
            case EXPLORE -> {}
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

        ctx.drawTextWithShadow(client.textRenderer, "Get this item to win!",
            panelX + panelW / 2 - client.textRenderer.getWidth("Get this item to win!") / 2,
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

            try (FileWriter fw = new FileWriter(resultFile)) {
                GSON.toJson(obj, fw);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to write result", e);
        }
    }

    private void loadConfig() {
        pickRandomTarget();
        startTime = Instant.now();
        if (client.world != null) {
            deadlineTicks = client.world.getTime() + CHALLENGE_TICKS;
        } else {
            deadlineTicks = CHALLENGE_TICKS;
        }
        active = true;
        completed = false;
        challengeType = ChallengeType.ITEM;
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
            if (ticks > 100) {
                close();
                client.execute(() -> client.stop());
            }
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
            if (ticks > 100) {
                close();
                client.execute(() -> client.stop());
            }
        }
    }
}
