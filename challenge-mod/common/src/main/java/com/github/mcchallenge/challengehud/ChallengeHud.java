package com.github.mcchallenge.challengehud;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Core challenge logic. Era-agnostic (no rendering, no architectury, no 1.20-only APIs)
 * so it compiles at Java 8 for MC 1.16.5. All version-specific work happens in the
 * per-era {@link EraBridge} class.
 *
 * <p>State machine:
 * <ul>
 *   <li>{@code IDLE}   — nothing sealed, nothing running. Honest start only via the
 *       title screen setup screen (seal), dishonest start only via the H keybind (in-world).</li>
 *   <li>{@code SEALED} — target rolled and committed to disk BEFORE entering the world;
 *       survives restarts (persisted to {@code config/challenge/challenge_state.json}).</li>
 *   <li>{@code ACTIVE} — promoted from SEALED on world entry (or started dishonestly);
 *       the timer starts here. In FULL mode the run is capped at a 5-minute RTA deadline
 *       ({@link #TIMEOUT_MS}).</li>
 *   <li>{@code DONE}   — won or died; result written to disk. The next world entry
 *       resets to IDLE.</li>
 * </ul>
 *
 * <p>Challenge modes ({@code config/challenge/mode.json}, written by the launcher):
 * <ul>
 *   <li>{@code FULL} — hardcore speedrun: hardcore world required, death ends the run,
 *       PB/attempts recorded (only for fresh worlds).</li>
 *   <li>{@code CASUAL} — relaxed: no hardcore required, death does not fail the run,
 *       no records, results are still written so the launcher knows the round is done.</li>
 * </ul>
 */
public class ChallengeHud {
    public static final String MOD_ID = "challengehud";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Random RNG = new Random();

    /** Items excluded from the target pool (matched by registry path substring). */
    private static final String[] POOL_BLACKLIST = {
        "spawn_egg", "command_block", "debug", "structure_block", "structure_void",
        "jigsaw", "bedrock", "spawner", "end_portal", "end_gateway", "dragon_egg",
        "infested", "reinforced_deepslate", "trial_spawner", "vault", "budding_amethyst",
        "farmland", "dirt_path", "piston_head", "moving_piston", "nether_portal",
        "knowledge_book", "debug_stick", "command_block_minecart",
        "suspicious_sand", "suspicious_gravel"
    };

    /** Technical blocks whose path is a single token (substring match would be too broad). */
    private static final String[] POOL_BLACKLIST_EXACT = {
        "barrier", "light", "fire"
    };

    /**
     * Deadline for a FULL-mode speedrun: 5 minutes, in wall-clock milliseconds.
     * Checked against {@link #rtaMillis()} (RTA), NOT {@code igtMs} — IGT freezes while the
     * game is paused, but a speedrun clock that stops when you alt-tab is wrong, and the
     * PB/record metric is already RTA-based. CASUAL is untimed practice and is exempt.
     */
    private static final long TIMEOUT_MS = 300_000L;

    // Package-private static state read by the per-era EraBridge (HUD rendering)
    // and the per-era ChallengeSetupScreen (menu).
    static Minecraft client = Minecraft.getInstance();
    static ItemStack targetStack = ItemStack.EMPTY;
    /** RTA wall-clock start, ms since epoch (keeps running through pauses). */
    static long rtaStartMs = 0;
    /** IGT accumulated, ms (50 ms per client tick; frozen while paused). */
    static long igtMs = 0;
    static String playerName = "";
    static ChallengeType challengeType = ChallengeType.ITEM;

    /** Current state-machine state (see class javadoc). */
    static State state = State.IDLE;
    static long sealedAt = 0;
    static long startedAt = 0;
    static String resultItem = null;
    static Long resultMs = null;

    /** Outcome of the finished challenge: "win" | "death" | "timeout" (null while running). */
    private static String resultOutcome = null;

    /**
     * Set when a SEALED challenge cannot promote to ACTIVE because the current world
     * is not hardcore (FULL mode only). Read by the per-era EraBridge to draw the HUD warning.
     */
    static boolean hardcoreBlocked = false;

    /** Challenge mode from {@code mode.json}: {@code "full"} (speedrun) or {@code "casual"}. */
    static ChallengeMode mode = ChallengeMode.FULL;

    /** {@code mode.json} {@code "fresh"}: launcher created a NEW instance for this run. */
    static boolean freshWorld = false;

    /**
     * Perpetual mode: {@code true} when the launcher wrote {@code {"perpetual":true}}
     * into {@code config/challenge/mode.json}. A finished run auto-seals and instantly
     * promotes a fresh target on the next world entry instead of returning to IDLE.
     */
    static boolean perpetual = false;

    /** Records loaded from {@code config/challenge/records.json} (FULL + fresh only). */
    static long bestMs = -1L;
    static int attempts = 0;
    static int wins = 0;

    /** Transient toast shown right after a challenge starts (drawn by the per-era HUD). */
    static String toastText = null;
    static long toastUntilMs = 0;

    enum State { IDLE, SEALED, ACTIVE, DONE }
    enum ChallengeType { ITEM }
    enum ChallengeMode { FULL, CASUAL }

    /** Called from the platform entrypoints (fabric/forge/neoforge) on client setup. */
    public static void init() {
        state = State.IDLE;
        EraBridge.registerEvents();
        loadMode();
        loadRecords();
        loadState();
        LOGGER.info("Challenge HUD loaded (state={}, mode={}, fresh={}, perpetual={})",
            state, mode, freshWorld, perpetual);
    }

    /** Queue a brief HUD toast (3.5 s). */
    static void showToast(String text) {
        toastText = text;
        toastUntilMs = System.currentTimeMillis() + 3500;
    }

    /**
     * RTA elapsed: wall-clock milliseconds since the run started (keeps running
     * through pauses). Returns 0 when no run is in progress.
     */
    static long rtaMillis() {
        if (rtaStartMs <= 0) return 0L;
        return System.currentTimeMillis() - rtaStartMs;
    }

    /**
     * Reads {@code config/challenge/mode.json} (written by the launcher before launch):
     * {@code {"mode":"full"|"casual","fresh":bool,"perpetual":bool}}.
     *
     * <p>The mode is owned exclusively by the launcher: the mod only READS it and never
     * writes it back (the setup screen displays the mode but cannot switch it).
     */
    private static void loadMode() {
        mode = ChallengeMode.FULL;
        freshWorld = false;
        perpetual = false;
        try {
            File modeFile = new File(new File(EraBridge.configDir().toFile(), "challenge"), "mode.json");
            if (modeFile.isFile()) {
                try (FileReader fr = new FileReader(modeFile)) {
                    JsonObject obj = GSON.fromJson(fr, JsonObject.class);
                    if (obj != null) {
                        if (obj.has("mode")) {
                            String m = obj.get("mode").getAsString();
                            mode = "casual".equalsIgnoreCase(m)
                                ? ChallengeMode.CASUAL : ChallengeMode.FULL;
                        }
                        if (obj.has("fresh")) {
                            freshWorld = obj.get("fresh").getAsBoolean();
                        }
                        if (obj.has("perpetual")) {
                            perpetual = obj.get("perpetual").getAsBoolean();
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to read challenge mode", e);
        }
    }

    /**
     * Honest mode: seal a random target BEFORE entering the world. Called from the
     * challenge setup screen (after the confirm dialog in FULL mode, directly in CASUAL).
     * Re-sealing while SEALED is only possible through {@link #rerollSealedFromMenu()}
     * (which the menu guards with a confirmation dialog in FULL mode).
     */
    public static void sealFromMenu() {
        if (state == State.ACTIVE) {
            LOGGER.warn("Cannot re-seal while a challenge is active");
            return;
        }
        if (targetStack.isEmpty()) {
            rollTarget();
        }
        state = State.SEALED;
        sealedAt = System.currentTimeMillis();
        startedAt = 0;
        rtaStartMs = 0;
        igtMs = 0;
        resultItem = null;
        resultMs = null;
        resultOutcome = null;
        hardcoreBlocked = false;
        writeState();
        LOGGER.info("Challenge sealed before world entry: {}", EraBridge.itemId(targetStack.getItem()));
    }

    /**
     * Re-roll the target while already SEALED and persist the new commitment.
     * CASUAL mode calls this directly (re-roll is always free there); the setup
     * screen shows a confirmation dialog first in FULL mode.
     */
    public static void rerollSealedFromMenu() {
        if (state != State.SEALED) {
            LOGGER.info("Re-roll ignored: not sealed (state={})", state);
            return;
        }
        rollTarget();
        writeState();
        LOGGER.info("Re-rolled sealed target: {}", EraBridge.itemId(targetStack.getItem()));
    }

    /**
     * Dishonest mode: global H keybind (in-world only). Starts a challenge on the fly,
     * but ONLY when nothing was sealed and no challenge is running. If a sealed/active
     * challenge exists the press is ignored (logged) so the honest commitment holds.
     * In FULL mode the keybind requires a hardcore world; CASUAL does not.
     */
    public static void tryStartDishonest() {
        if (state == State.IDLE && client.level != null) {
            if (mode == ChallengeMode.FULL && !client.level.getLevelData().isHardcore()) {
                hardcoreBlocked = true;
                LOGGER.warn("Keybind refused: challenge requires a hardcore world");
                return;
            }
            rollTarget();
            state = State.ACTIVE;
            sealedAt = 0;
            startedAt = System.currentTimeMillis();
            rtaStartMs = System.currentTimeMillis();
            igtMs = 0;
            resultItem = null;
            resultMs = null;
            resultOutcome = null;
            writeState();
            showToast(EraBridge.translate("challengehud.toast.started",
                targetStack.getHoverName().getString()));
            LOGGER.info("Challenge started via keybind (dishonest): {}",
                EraBridge.itemId(targetStack.getItem()));
        } else {
            LOGGER.info("Keybind ignored: state={}, inWorld={}", state, client.level != null);
        }
    }

    /** Rolls a random obtainable target item. Called by the menu and the state machine. */
    static void rollTarget() {
        List<Item> candidates = new ArrayList<>();
        for (Item item : EraBridge.items()) {
            if (item == null || item == Items.AIR) continue;
            String path = EraBridge.itemPath(item);
            if (path == null || path.isEmpty()) continue;
            if (isPoolExcluded(path)) continue;
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

    private static boolean isPoolExcluded(String path) {
        for (String token : POOL_BLACKLIST) {
            if (path.contains(token)) return true;
        }
        for (String token : POOL_BLACKLIST_EXACT) {
            if (path.equals(token)) return true;
        }
        return false;
    }

    /**
     * Per-client-tick state machine driver:
     * <ul>
     *   <li>DONE -> IDLE on the next world entry (fresh run); in perpetual mode
     *       DONE -> SEALED -> ACTIVE instead (auto-started next challenge).</li>
     *   <li>SEALED -> ACTIVE on world entry; in FULL mode only in a hardcore world
     *       (otherwise the challenge stays SEALED and the HUD shows the hardcore
     *       warning), in CASUAL mode always.</li>
     *   <li>ACTIVE: in FULL mode death = loss and the 5-minute RTA deadline = loss; in
     *       CASUAL neither applies (death is ignored and the run is untimed practice, so
     *       the challenge continues after respawn with no cutoff).</li>
     * </ul>
     */
    static void tick(Minecraft client) {
        if (client.player == null) {
            // Leaving a world cancels an ACTIVE challenge (so the H keybind works
            // again); SEALED (honest commitment made on the menu) and DONE (result
            // pending) survive.
            if (state == State.ACTIVE) {
                state = State.IDLE;
                startedAt = 0;
                rtaStartMs = 0;
                igtMs = 0;
                targetStack = ItemStack.EMPTY;
                resultItem = null;
                resultMs = null;
                resultOutcome = null;
                writeState();
                LOGGER.info("Challenge cancelled: left world");
            }
            if (hardcoreBlocked) {
                hardcoreBlocked = false;
                LOGGER.info("World left; hardcore block cleared");
            }
            return;
        }

        if (state == State.DONE && client.level != null) {
            resultItem = null;
            resultMs = null;
            resultOutcome = null;
            if (perpetual) {
                // Perpetual mode: a finished run auto-seals the next target on world
                // entry; promotion to ACTIVE happens right below (hardcore check in
                // FULL mode only).
                rollTarget();
                state = State.SEALED;
                sealedAt = System.currentTimeMillis();
                startedAt = 0;
                rtaStartMs = 0;
                igtMs = 0;
                LOGGER.info("Perpetual mode: auto-sealed next challenge: {}",
                    EraBridge.itemId(targetStack.getItem()));
            } else {
                state = State.IDLE;
                targetStack = ItemStack.EMPTY;
                writeState();
                LOGGER.info("Previous challenge finished; fresh run");
                return;
            }
        }

        if (state == State.SEALED && client.level != null) {
            if (mode == ChallengeMode.FULL && !client.level.getLevelData().isHardcore()) {
                hardcoreBlocked = true;
                writeState();
                LOGGER.warn("Hardcore world required — challenge stays SEALED");
                return;
            }
            hardcoreBlocked = false;
            state = State.ACTIVE;
            startedAt = System.currentTimeMillis();
            rtaStartMs = System.currentTimeMillis();
            igtMs = 0;
            writeState();
            showToast(EraBridge.translate("challengehud.toast.started",
                targetStack.getHoverName().getString()));
            LOGGER.info("Challenge activated on world entry: {}",
                EraBridge.itemId(targetStack.getItem()));
        }

        if (state != State.ACTIVE) return;

        // Death = loss in FULL mode only; CASUAL continues after respawn.
        if (mode == ChallengeMode.FULL && client.player.deathTime > 0) {
            fail(client);
            return;
        }

        // 5-minute deadline (FULL mode only). Checked BEFORE the win test so the cutoff is
        // hard: at RTA >= 5:00 the run can no longer be won. `rtaStartMs > 0` means the
        // clock is really running (rtaMillis() returns 0 otherwise), so a freshly promoted
        // or restored ACTIVE run can never trip the deadline on the spot.
        if (mode == ChallengeMode.FULL && rtaStartMs > 0 && rtaMillis() >= TIMEOUT_MS) {
            timeout(client);
            return;
        }

        // IGT: 50 ms per client tick, frozen while the game is paused.
        if (!client.isPaused()) {
            igtMs += 50;
        }

        switch (challengeType) {
            case ITEM:
                if (EraBridge.countItem(client, targetStack.getItem()) > 0) {
                    complete(client);
                }
                break;
        }
    }

    /**
     * Death = loss (FULL mode): the challenge ends immediately when the player dies.
     * The launcher reads the {@code "result":"death"} marker from the state file.
     */
    private static void fail(Minecraft client) {
        if (state != State.ACTIVE) return;
        state = State.DONE;
        resultItem = EraBridge.itemId(targetStack.getItem());
        resultMs = null;
        resultOutcome = "death";
        writeState();
        if (freshWorld) {
            attempts++;
            saveRecords();
        }
        client.execute(() -> client.setScreen(new FailScreen()));
        LOGGER.info("Challenge failed — player died!");
    }

    /**
     * Deadline = loss (FULL mode only): the challenge ends the moment RTA reaches
     * {@link #TIMEOUT_MS}. Mirrors {@link #fail(Minecraft)} step for step — same ACTIVE
     * guard (so it fires exactly once), same {@code DONE} transition, same persistence —
     * but records {@code "result":"timeout"} and opens the era {@link TimeoutScreen}
     * instead of the death screen. No new state fields: {@code result_ms} stays null, as
     * on death, and records.json only gains an attempt, never a PB or a win.
     */
    private static void timeout(Minecraft client) {
        if (state != State.ACTIVE) return;
        state = State.DONE;
        resultItem = EraBridge.itemId(targetStack.getItem());
        resultMs = null;
        resultOutcome = "timeout";
        writeState();
        if (freshWorld) {
            attempts++;
            saveRecords();
        }
        client.execute(() -> client.setScreen(new TimeoutScreen()));
        LOGGER.info("Challenge failed — 5:00 deadline reached (attempts={})", attempts);
    }

    private static void complete(Minecraft client) {
        if (state != State.ACTIVE) return;
        state = State.DONE;

        long rtaMs = rtaMillis();
        if (rtaMs <= 0) {
            rtaMs = System.currentTimeMillis() - startedAt;
        }
        playerName = client.player.getName().getString();
        resultItem = EraBridge.itemId(targetStack.getItem());
        resultMs = rtaMs;
        resultOutcome = "win";
        writeState();

        // Records only count for legit FULL runs on freshly created worlds;
        // practice runs (existing world) and CASUAL never touch records.json.
        boolean newpb = false;
        if (mode == ChallengeMode.FULL && freshWorld) {
            attempts++;
            wins++;
            if (bestMs < 0 || rtaMs < bestMs) {
                bestMs = rtaMs;
                newpb = true;
            }
            saveRecords();
        }

        // Java 8 lambda capture: copies must be effectively final.
        final long resultRtaMs = rtaMs;
        final boolean resultNewpb = newpb;
        client.execute(() -> client.setScreen(new WinScreen(resultRtaMs, resultNewpb, targetStack)));

        LOGGER.info("Challenge completed by {} in {} (records: bestMs={}, attempts={})",
            playerName, formatTime(rtaMs), bestMs, attempts);
    }

    private static File stateFile() {
        return new File(new File(EraBridge.configDir().toFile(), "challenge"), "challenge_state.json");
    }

    /** Persists the current machine state to config/challenge/challenge_state.json. */
    private static void writeState() {
        try {
            JsonObject obj = new JsonObject();
            obj.addProperty("state", state.name());
            obj.addProperty("target_item",
                targetStack.isEmpty() ? null : EraBridge.itemId(targetStack.getItem()));
            obj.addProperty("sealed_at", sealedAt);
            obj.addProperty("started_at", startedAt);
            obj.addProperty("result_item", resultItem);
            obj.addProperty("result_ms", resultMs);
            obj.addProperty("result", resultOutcome);
            File f = stateFile();
            File parent = f.getParentFile();
            if (parent != null) parent.mkdirs();
            try (FileWriter fw = new FileWriter(f)) {
                GSON.toJson(obj, fw);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to write challenge state", e);
        }
    }

    /**
     * Restores a previously sealed (or finished) challenge so the honest commitment
     * survives a restart. Unreadable/missing files simply keep IDLE.
     */
    private static void loadState() {
        File f = stateFile();
        if (!f.isFile()) return;
        try (FileReader fr = new FileReader(f)) {
            JsonObject obj = GSON.fromJson(fr, JsonObject.class);
            if (obj == null) return;
            String st = obj.has("state") ? obj.get("state").getAsString() : null;
            if ("SEALED".equals(st) && obj.has("target_item")
                && !obj.get("target_item").isJsonNull()) {
                String id = obj.get("target_item").getAsString();
                Item item = EraBridge.itemById(id);
                if (item != null && item != Items.AIR) {
                    targetStack = new ItemStack(item);
                    state = State.SEALED;
                    sealedAt = obj.has("sealed_at") ? obj.get("sealed_at").getAsLong() : 0;
                    resultItem = null;
                    resultMs = null;
                    resultOutcome = null;
                    hardcoreBlocked = false;
                    LOGGER.info("Restored sealed challenge: {}", id);
                } else {
                    LOGGER.warn("Sealed target '{}' no longer exists; starting fresh", id);
                }
            } else if ("DONE".equals(st)) {
                state = State.DONE;
                resultItem = obj.has("result_item") && !obj.get("result_item").isJsonNull()
                    ? obj.get("result_item").getAsString() : null;
                resultMs = obj.has("result_ms") && !obj.get("result_ms").isJsonNull()
                    ? obj.get("result_ms").getAsLong() : null;
                resultOutcome = obj.has("result") && !obj.get("result").isJsonNull()
                    ? obj.get("result").getAsString() : null;
                LOGGER.info("Restored finished challenge (result: {})", resultItem);
            } else if ("ACTIVE".equals(st)) {
                // A wall-clock timer cannot be reliably restored across restarts;
                // reset to a fresh state rather than running a broken timer.
                state = State.IDLE;
                targetStack = ItemStack.EMPTY;
                resultItem = null;
                resultMs = null;
                resultOutcome = null;
                LOGGER.info("Stale ACTIVE challenge reset to IDLE on load");
            }
        } catch (Exception e) {
            LOGGER.error("Failed to read challenge state", e);
            state = State.IDLE;
        }
    }

    private static File recordsFile() {
        return new File(new File(EraBridge.configDir().toFile(), "challenge"), "records.json");
    }

    /**
     * Loads {@code config/challenge/records.json}:
     * {@code {"attempts":N,"best_ms":<long|null>,"wins":N,"last_item":"ns:id"}}.
     */
    static void loadRecords() {
        bestMs = -1L;
        attempts = 0;
        wins = 0;
        File f = recordsFile();
        if (!f.isFile()) return;
        try (FileReader fr = new FileReader(f)) {
            JsonObject obj = GSON.fromJson(fr, JsonObject.class);
            if (obj == null) return;
            if (obj.has("best_ms") && !obj.get("best_ms").isJsonNull()) {
                bestMs = obj.get("best_ms").getAsLong();
            }
            if (obj.has("attempts")) {
                attempts = obj.get("attempts").getAsInt();
            }
            if (obj.has("wins")) {
                wins = obj.get("wins").getAsInt();
            }
            LOGGER.info("Loaded records: bestMs={}, attempts={}, wins={}", bestMs, attempts, wins);
        } catch (Exception e) {
            LOGGER.error("Failed to read challenge records", e);
        }
    }

    private static void saveRecords() {
        try {
            JsonObject obj = new JsonObject();
            obj.addProperty("attempts", attempts);
            obj.add("best_ms", bestMs < 0 ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(bestMs));
            obj.addProperty("wins", wins);
            obj.addProperty("last_item",
                targetStack.isEmpty() ? null : EraBridge.itemId(targetStack.getItem()));
            File f = recordsFile();
            File parent = f.getParentFile();
            if (parent != null) parent.mkdirs();
            try (FileWriter fw = new FileWriter(f)) {
                GSON.toJson(obj, fw);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to write challenge records", e);
        }
    }

    /** Formats milliseconds as {@code mm:ss.cs}. */
    public static String formatTime(long ms) {
        long totalSec = ms / 1000;
        return String.format("%02d:%02d.%02d", totalSec / 60, totalSec % 60, (ms % 1000) / 10);
    }

    /** Formats milliseconds as {@code m:ss} / {@code h:mm:ss} for the HUD timer rows. */
    public static String formatHms(long ms) {
        long totalSec = ms / 1000;
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        return h > 0
            ? String.format("%d:%02d:%02d", h, m, s)
            : String.format("%d:%02d", m, s);
    }
}
