package com.github.mcchallenge.challengehud;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
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

/**
 * Core challenge logic. Era-agnostic (no rendering, no architectury, no 1.20-only APIs)
 * so it compiles at Java 8 for MC 1.16.5. All version-specific work happens in the
 * per-era {@link EraBridge} class.
 */
public class ChallengeHud {
    public static final String MOD_ID = "challengehud";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final long CHALLENGE_TICKS = 6000; // 5 min (20 ticks/sec * 300 sec)
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Random RNG = new Random();

    // Package-private static state read by the per-era EraBridge (HUD rendering).
    static Minecraft client = Minecraft.getInstance();
    static ItemStack targetStack = ItemStack.EMPTY;
    static long deadlineTicks = 0;
    static boolean active = false;
    static boolean completed = false;
    static Instant startTime;
    static String playerName = "";
    static ChallengeType challengeType = ChallengeType.ITEM;

    enum ChallengeType { ITEM, MOB_KILL, EXPLORE }

    /** Called from the platform entrypoints (fabric/forge/neoforge) on client setup. */
    public static void init() {
        EraBridge.registerEvents();
        loadConfig();
        LOGGER.info("Challenge HUD loaded");
    }

    private static void pickRandomTarget() {
        List<Item> candidates = new ArrayList<>();
        for (Item item : EraBridge.items()) {
            if (item == Items.AIR) continue;
            String path = EraBridge.itemPath(item);
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
            LOGGER.info("Random target: {} ({})", EraBridge.itemId(picked),
                new ItemStack(picked).getHoverName().getString());
        }
    }

    static void tick(Minecraft client) {
        if (client.player == null || !active || completed) return;

        if (deadlineTicks > 0 && client.level.getGameTime() >= deadlineTicks) {
            timeout(client);
            return;
        }

        switch (challengeType) {
            case ITEM:
                if (EraBridge.countItem(client, targetStack.getItem()) > 0) {
                    complete(client);
                }
                break;
            case MOB_KILL:
            case EXPLORE:
                break;
        }
    }

    private static void timeout(Minecraft client) {
        if (completed) return;
        completed = true;
        active = false;
        client.execute(() -> client.setScreen(new TimeoutScreen()));
        LOGGER.info("Challenge timed out!");
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
            File configDir = new File(EraBridge.configDir().toFile(), "challenge");
            configDir.mkdirs();
            File resultFile = new File(configDir, "challenge_result.json");

            JsonObject obj = new JsonObject();
            obj.addProperty("item", EraBridge.itemId(targetStack.getItem()));
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
