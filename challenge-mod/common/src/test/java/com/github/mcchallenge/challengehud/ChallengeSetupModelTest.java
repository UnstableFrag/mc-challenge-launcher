package com.github.mcchallenge.challengehud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.github.mcchallenge.challengehud.ChallengeSetupModel.Action;
import com.github.mcchallenge.challengehud.ChallengeSetupModel.ConfirmAction;

/**
 * Unit tests for the era-independent setup-menu logic.
 *
 * <p>This is the regression net for the failure that motivated the extraction: v0.5.0 shipped
 * 46 jars in which the Roll button stayed latched on "Rolling..." forever, because the fix that
 * cleared the flag had been hand-applied to 1 of the 8 per-era screen copies. CI can only assert
 * that each jar still calls {@code finishRolling()} from {@code tick()}; these tests assert the
 * behaviour itself, once, on the single copy all 8 eras now share.
 *
 * <p>Deterministic by construction: the model never reads a clock. Every time-dependent call
 * takes {@code nowMs} explicitly, so the deadline can be probed exactly — which is the whole
 * point of {@code RollDeadline.expiresExactlyAtTheDeadline()}.
 */
class ChallengeSetupModelTest {

    // ---- geometry the view supplies (real 854x480 panel coordinates) -------------

    /** Seal button, step 2 only. */
    private static final int[] SEAL = { 347, 264, 160, 20 };
    /** Roll button. */
    private static final int[] ROLL = { 333, 288, 90, 20 };
    /** Back button. */
    private static final int[] BACK = { 431, 288, 90, 20 };
    /** Confirm dialog OK ("Lock"). */
    private static final int[] LOCK = { 317, 266, 100, 20 };
    /** Confirm dialog Cancel. */
    private static final int[] CANCEL = { 437, 266, 100, 20 };

    /** One pixel inside the rectangle. */
    private static double inX(int[] r) { return r[0] + 1; }
    private static double inY(int[] r) { return r[1] + 1; }

    private static ChallengeSetupModel model(boolean sealed, boolean fullMode) {
        ChallengeSetupModel m = new ChallengeSetupModel();
        m.init(sealed, fullMode);
        return m;
    }

    /** Routes one click exactly as the era screens do. */
    private static Action click(ChallengeSetupModel m, double x, double y, long nowMs) {
        return m.onClick(x, y, nowMs, SEAL, ROLL, BACK, LOCK, CANCEL);
    }

    private static Action clickIn(ChallengeSetupModel m, int[] rect, long nowMs) {
        return click(m, inX(rect), inY(rect), nowMs);
    }

    /** Clicks Roll, runs the animation out to its deadline and completes it: now in step 2. */
    private static void rollToStepTwo(ChallengeSetupModel m, long nowMs) {
        assertEquals(Action.NONE, clickIn(m, ROLL, nowMs), "Roll starts no external effect");
        assertTrue(m.rolling());
        assertTrue(m.tick(nowMs + ChallengeSetupModel.ROLL_DELAY_MS), "deadline must expire the roll");
        m.finishRolling();
        assertEquals(ChallengeSetupModel.STEP_GOAL, m.step());
        assertFalse(m.rolling());
    }

    // ---- init / step -----------------------------------------------------------

    @Test
    @DisplayName("init() opens step 1 when nothing is sealed")
    void initOpensStepOneWhenUnsealed() {
        ChallengeSetupModel m = model(false, true);
        assertEquals(ChallengeSetupModel.STEP_REPORT, m.step());
        assertFalse(m.rolling());
        assertFalse(m.dialogOpen());
    }

    @Test
    @DisplayName("init() opens step 2 when a seal is already committed")
    void initOpensStepTwoWhenSealed() {
        assertEquals(ChallengeSetupModel.STEP_GOAL, model(true, true).step());
    }

    @Test
    @DisplayName("init() resets a roll and a dialog left over from a previous visit")
    void initResetsPreviousVisitState() {
        ChallengeSetupModel m = model(false, true);
        rollToStepTwo(m, 1_000L);
        assertEquals(Action.NONE, clickIn(m, SEAL, 2_000L));
        assertTrue(m.dialogOpen());

        m.init(false, true);
        assertEquals(ChallengeSetupModel.STEP_REPORT, m.step());
        assertFalse(m.rolling(), "a reopened menu must never open mid-roll");
        assertFalse(m.dialogOpen(), "a reopened menu must never open with a dialog up");
    }

    @Test
    @DisplayName("init() never rolls on open (rule D), not even when it opens on step 2")
    void initNeverRollsOnOpen() {
        ChallengeSetupModel m = model(true, false);
        assertEquals(ChallengeSetupModel.STEP_GOAL, m.step());
        assertFalse(m.rolling());
        assertFalse(m.tick(9_999_999L), "nothing to expire: the menu did not start a roll");
        assertEquals(ChallengeSetupModel.STEP_GOAL, m.step());
    }

    // ---- the shipped bug: the roll animation latching forever ------------------

    @Nested
    class RollDeadline {

        private static final long T0 = 1_700_000_000_000L;

        @Test
        @DisplayName("tick() before the deadline keeps rolling")
        void keepsRollingBeforeTheDeadline() {
            ChallengeSetupModel m = model(false, true);
            m.startRolling(T0);
            assertTrue(m.rolling());

            assertFalse(m.tick(T0), "tick at the start must not expire the roll");
            assertFalse(m.tick(T0 + ChallengeSetupModel.ROLL_DELAY_MS - 1),
                "one millisecond before the deadline the roll must still be running");
            assertTrue(m.rolling(), "the Roll button must stay latched until the deadline");
        }

        @Test
        @DisplayName("tick() expires exactly at the deadline: deadline-1 rolls, deadline does not")
        void expiresExactlyAtTheDeadline() {
            ChallengeSetupModel m = model(false, true);
            m.startRolling(T0);
            long deadline = T0 + ChallengeSetupModel.ROLL_DELAY_MS;

            assertFalse(m.tick(deadline - 1), "deadline-1 must still roll");
            assertTrue(m.rolling());

            assertTrue(m.tick(deadline), "the deadline itself must expire the roll");
            assertFalse(m.rolling(), "this is the shipped bug: the flag stayed set forever");
        }

        @Test
        @DisplayName("the rolling flag cannot latch, however many ticks pass past the deadline")
        void cannotLatchPastTheDeadline() {
            ChallengeSetupModel m = model(false, true);
            m.startRolling(T0);

            assertTrue(m.tick(T0 + ChallengeSetupModel.ROLL_DELAY_MS), "the first tick past the deadline expires it");
            for (int i = 0; i < 10_000; i++) {
                assertFalse(m.tick(T0 + ChallengeSetupModel.ROLL_DELAY_MS + 1L + i),
                    "tick " + i + " past the deadline must not report another expiry");
            }
            assertFalse(m.rolling(), "a latched flag would leave Roll unclickable forever");

            clickIn(m, ROLL, T0 + 9_999_999L);
            assertTrue(m.rolling(), "and the button is genuinely live again");
        }

        @Test
        @DisplayName("ticking an idle model is inert")
        void idleTickIsInert() {
            ChallengeSetupModel m = model(true, true);
            for (long now = 0; now < 5; now++) {
                assertFalse(m.tick(now));
            }
            assertFalse(m.rolling());
            assertEquals(ChallengeSetupModel.STEP_GOAL, m.step());
        }

        @Test
        @DisplayName("finishRolling() advances to step 2 and reports a fresh roll")
        void finishRollingRollsANewTarget() {
            ChallengeSetupModel m = model(false, true);
            m.startRolling(T0);
            assertTrue(m.tick(T0 + ChallengeSetupModel.ROLL_DELAY_MS));
            assertEquals(Action.ROLL, m.finishRolling());
            assertEquals(ChallengeSetupModel.STEP_GOAL, m.step());
            assertFalse(m.rolling());
        }

        @Test
        @DisplayName("finishRolling() re-rolls instead of rolling when a seal is committed")
        void finishRollingRerollsASealedTarget() {
            ChallengeSetupModel m = model(true, true);
            m.startRolling(T0);
            assertTrue(m.tick(T0 + ChallengeSetupModel.ROLL_DELAY_MS));
            assertEquals(Action.REROLL_SEALED, m.finishRolling());
            assertEquals(ChallengeSetupModel.STEP_GOAL, m.step());
        }

        @Test
        @DisplayName("finishRolling() without an expired roll is a no-op, so it cannot roll twice")
        void finishRollingIsOneShot() {
            ChallengeSetupModel m = model(false, true);
            assertEquals(Action.NONE, m.finishRolling(), "nothing to complete yet");

            m.startRolling(T0);
            assertTrue(m.tick(T0 + ChallengeSetupModel.ROLL_DELAY_MS));
            assertEquals(Action.ROLL, m.finishRolling());
            assertEquals(Action.NONE, m.finishRolling(), "a second call must not roll again");
            assertEquals(ChallengeSetupModel.STEP_GOAL, m.step());
        }

        @Test
        @DisplayName("an unconsumed expiry does not leak into the next roll")
        void expiryTokenIsResetByTheNextRoll() {
            ChallengeSetupModel m = model(false, true);
            m.startRolling(T0);
            assertTrue(m.tick(T0 + ChallengeSetupModel.ROLL_DELAY_MS));

            m.startRolling(T0 + 10_000L);
            assertEquals(Action.NONE, m.finishRolling(), "the new roll has not expired yet");
            assertTrue(m.tick(T0 + 10_000L + ChallengeSetupModel.ROLL_DELAY_MS));
            assertEquals(Action.ROLL, m.finishRolling());
        }
    }

    // ---- confirm dialog --------------------------------------------------------

    @Nested
    class ConfirmDialog {

        @Test
        @DisplayName("FULL mode asks before the first seal, CASUAL seals straight away")
        void sealConfirmationDependsOnMode() {
            ChallengeSetupModel full = model(false, true);
            rollToStepTwo(full, 1_000L);
            assertEquals(Action.NONE, clickIn(full, SEAL, 2_000L), "FULL must confirm first");
            assertTrue(full.dialogOpen());
            assertFalse(full.isResealDialog(), "the first seal is the SEAL dialog, not RESEAL");
            assertEquals(Action.SEAL, clickIn(full, LOCK, 2_000L), "Lock commits the seal");
            assertFalse(full.dialogOpen());

            ChallengeSetupModel casual = model(false, false);
            rollToStepTwo(casual, 1_000L);
            assertEquals(Action.SEAL, clickIn(casual, SEAL, 2_000L), "CASUAL never confirms");
            assertFalse(casual.dialogOpen());
        }

        @Test
        @DisplayName("the re-roll dialog carries no item name (B3); CASUAL re-rolls freely")
        void resealConfirmationDependsOnMode() {
            ChallengeSetupModel full = model(true, true);
            assertEquals(Action.NONE, clickIn(full, ROLL, 1_000L));
            assertTrue(full.dialogOpen());
            assertTrue(full.isResealDialog());
            assertEquals(Action.REROLL_SEALED, clickIn(full, LOCK, 1_000L));
            assertFalse(full.dialogOpen());

            ChallengeSetupModel casual = model(true, false);
            assertEquals(Action.NONE, clickIn(casual, ROLL, 1_000L));
            assertFalse(casual.dialogOpen(), "CASUAL re-rolls without asking");
            assertTrue(casual.rolling());
        }

        @Test
        @DisplayName("Cancel closes the dialog without acting")
        void cancelClosesWithoutActing() {
            ChallengeSetupModel m = model(false, true);
            rollToStepTwo(m, 1_000L);
            assertEquals(Action.NONE, clickIn(m, SEAL, 2_000L));
            assertTrue(m.dialogOpen());

            assertEquals(Action.NONE, clickIn(m, CANCEL, 2_000L));
            assertFalse(m.dialogOpen());
            assertFalse(m.rolling(), "cancelling must not have started a roll");
            assertEquals(ChallengeSetupModel.STEP_GOAL, m.step());
        }

        @Test
        @DisplayName("a dialog swallows every click, including the panel buttons behind it")
        void dialogSwallowsOtherClicks() {
            ChallengeSetupModel m = model(false, true);
            rollToStepTwo(m, 1_000L);
            assertEquals(Action.NONE, clickIn(m, SEAL, 2_000L));
            assertTrue(m.dialogOpen());

            assertEquals(Action.NONE, clickIn(m, ROLL, 2_000L), "Roll is behind the dialog");
            assertEquals(Action.NONE, clickIn(m, BACK, 2_000L), "Back is behind the dialog");
            assertEquals(Action.NONE, clickIn(m, SEAL, 2_000L), "Seal is behind the dialog");
            assertEquals(Action.NONE, click(m, 5, 5, 2_000L), "so is empty space");
            assertTrue(m.dialogOpen(), "the dialog must survive a mis-click");
        }

        @Test
        @DisplayName("ESC closes the dialog without leaving the step or closing the screen")
        void escapeClosesDialogWithoutLeavingTheStep() {
            ChallengeSetupModel m = model(false, true);
            rollToStepTwo(m, 1_000L);
            assertEquals(Action.NONE, clickIn(m, SEAL, 2_000L));
            assertTrue(m.dialogOpen());

            assertEquals(Action.NONE, m.onEscape(), "ESC must not close the screen while a dialog is up");
            assertFalse(m.dialogOpen());
            assertEquals(ChallengeSetupModel.STEP_GOAL, m.step(), "ESC must not move the step");
        }

        @Test
        @DisplayName("ESC with no dialog closes the screen (rule B2, second half)")
        void escapeWithoutDialogClosesScreen() {
            assertEquals(Action.CLOSE_SCREEN, model(false, true).onEscape());
        }

        @Test
        @DisplayName("ESC is layered: first the dialog, then the screen")
        void escapeIsLayered() {
            ChallengeSetupModel m = model(true, true);
            assertEquals(Action.NONE, clickIn(m, ROLL, 1_000L));
            assertEquals(Action.NONE, m.onEscape());
            assertEquals(Action.CLOSE_SCREEN, m.onEscape());
        }
    }

    // ---- click routing ---------------------------------------------------------

    @Nested
    class ClickRouting {

        @Test
        @DisplayName("step 1: Roll latches the animation, Back closes the screen")
        void stepOneRoutes() {
            ChallengeSetupModel m = model(false, true);
            assertEquals(Action.NONE, clickIn(m, ROLL, 1_000L));
            assertTrue(m.rolling());

            rollToStepTwo(m, 2_000L);
            assertEquals(Action.CLOSE_SCREEN, clickIn(m, BACK, 3_000L));
        }

        @Test
        @DisplayName("step 2: Seal is hit-tested before Roll, and is a no-op once sealed")
        void stepTwoRoutes() {
            ChallengeSetupModel m = model(false, false);
            rollToStepTwo(m, 1_000L);

            assertEquals(Action.SEAL, clickIn(m, SEAL, 2_000L), "CASUAL seals directly");
            m.syncContext(true, false);
            assertEquals(Action.NONE, clickIn(m, SEAL, 3_000L), "Seal is a no-op once sealed");

            clickIn(m, ROLL, 3_000L);
            assertTrue(m.rolling(), "Roll still re-rolls while sealed (in CASUAL)");
        }

        @Test
        @DisplayName("a point outside every rectangle does nothing")
        void clickOutsideEveryRectDoesNothing() {
            ChallengeSetupModel m = model(false, true);
            assertEquals(Action.NONE, click(m, 5, 5, 1_000L));
            assertEquals(Action.NONE, click(m, 800, 460, 1_000L));
            assertEquals(Action.NONE, click(m, 0, 0, 1_000L));
            assertFalse(m.rolling());
            assertFalse(m.dialogOpen());
            assertEquals(ChallengeSetupModel.STEP_REPORT, m.step());
        }

        @Test
        @DisplayName("rectangles include their origin and exclude their far edge")
        void rectangleEdges() {
            ChallengeSetupModel m = model(false, true);
            assertEquals(Action.NONE, click(m, ROLL[0] + ROLL[2], inY(ROLL), 1_000L), "right edge is out");
            assertFalse(m.rolling());
            assertEquals(Action.NONE, click(m, inX(ROLL), ROLL[1] + ROLL[3], 1_000L), "bottom edge is out");
            assertFalse(m.rolling());

            click(m, ROLL[0], ROLL[1], 1_000L);
            assertTrue(m.rolling(), "the origin is in");
        }

        @Test
        @DisplayName("a latched roll swallows every click")
        void rollSwallowsClicks() {
            ChallengeSetupModel m = model(false, true);
            clickIn(m, ROLL, 1_000L);
            assertEquals(Action.NONE, clickIn(m, BACK, 1_000L), "Back must not close mid-roll");
            assertEquals(Action.NONE, clickIn(m, ROLL, 1_000L), "Roll must not re-latch mid-roll");
        }

        @Test
        @DisplayName("a roll cannot be restarted while one is running (the deadline is set once)")
        void rollIsNotRestartable() {
            ChallengeSetupModel m = model(false, true);
            m.startRolling(1_000L);
            assertFalse(m.tick(1_000L));

            clickIn(m, ROLL, 1_100L);
            assertFalse(m.tick(1_000L + ChallengeSetupModel.ROLL_DELAY_MS - 1),
                "the re-click must not have pushed the deadline out");
            assertTrue(m.tick(1_000L + ChallengeSetupModel.ROLL_DELAY_MS));
        }

        @Test
        @DisplayName("syncContext picks up a seal committed in place, so the menu never acts stale")
        void syncContextAvoidsStaleDecisions() {
            ChallengeSetupModel m = model(false, false);
            rollToStepTwo(m, 1_000L);
            assertEquals(Action.SEAL, clickIn(m, SEAL, 2_000L), "CASUAL sealed in place");
            assertFalse(m.dialogOpen());

            // The menu stays open. Without a refresh the model would still think "unsealed"
            // and hand out a second SEAL.
            m.syncContext(true, false);
            assertEquals(Action.NONE, clickIn(m, SEAL, 3_000L), "Seal must be a no-op once sealed");
        }
    }

    // ---- the point of the extraction: the shared class must stay game-free ------

    @Test
    @DisplayName("the model references no Minecraft / Mojang / Architectury type")
    void modelIsFreeOfGameTypes() {
        String pool = classFileConstantPool();
        for (String forbidden : new String[] {
                "net/minecraft", "net.minecraft", "com/mojang", "com.mojang",
                "architectury", "com/google/gson", "org/slf4j", "java/time" }) {
            assertFalse(pool.contains(forbidden),
                "ChallengeSetupModel must not reference " + forbidden
                    + " — that is what would stop it compiling as the shared Java 8 core");
        }
    }

    @Test
    @DisplayName("the model is plain Java 8 bytecode (no invokedynamic)")
    void modelIsJava8Bytecode() {
        assertFalse(classFileConstantPool().contains("invokedynamic"),
            "no lambdas / indy string concat in the shared core (source level 8)");
    }

    @Test
    @DisplayName("ConfirmAction stays NONE / SEAL / RESEAL (the dialog body keys off RESEAL)")
    void confirmActionValues() {
        assertEquals(ConfirmAction.NONE, ConfirmAction.valueOf("NONE"));
        assertEquals(ConfirmAction.SEAL, ConfirmAction.valueOf("SEAL"));
        assertEquals(ConfirmAction.RESEAL, ConfirmAction.valueOf("RESEAL"));
        assertEquals(3, ConfirmAction.values().length);
    }

    /**
     * Reads the model's own .class bytes and returns them as text. Constant-pool UTF8 entries are
     * stored verbatim, so a substring scan over the raw bytes is enough to catch a stray game
     * import — no bytecode parser needed.
     */
    private static String classFileConstantPool() {
        String resource = "com/github/mcchallenge/challengehud/ChallengeSetupModel.class";
        InputStream in = ChallengeSetupModel.class.getClassLoader().getResourceAsStream(resource);
        assertNotNull(in, resource + " must be on the test classpath");
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), Charset.forName("ISO-8859-1"));
        } catch (Exception e) {
            throw new AssertionError("could not read " + resource, e);
        } finally {
            try { in.close(); } catch (Exception ignored) { /* nothing to do */ }
        }
    }
}
