package com.github.mcchallenge.challengehud;

/**
 * Era-independent logic of the challenge setup menu (roll a target, seal it).
 *
 * <p>This is the single copy of that logic. It used to live — eight times over — inside
 * {@code common/src/<era>/java/.../ChallengeSetupScreen.java}, one copy per Minecraft API era,
 * and a hand-applied bug fix reached only some of them: v0.5.0 shipped with the roll animation
 * never completing in 7 of the 8 jars (the Roll button stayed latched on "Rolling..."), because
 * the fix had been made in a single copy. CI now asserts that every produced jar's
 * {@code ChallengeSetupScreen} declares {@code tick()} and calls {@code finishRolling()}, which
 * catches the symptom; this class removes the cause, and
 * {@code ChallengeSetupScreenTest}-style unit tests on the model catch the class of bug directly.
 *
 * <p>Hard constraints, deliberately kept so this compiles once as plain Java 8
 * ({@code options.release 8} for MC 1.16.5):
 * <ul>
 *   <li>ZERO Minecraft / Mojang / Architectury imports. Nothing here knows what a
 *       {@code Screen}, a {@code Font} or an {@code ItemStack} is.</li>
 *   <li>No files, no logging, no clock: the caller passes {@code nowMs} (plain
 *       {@code long} millis) so the roll deadline is deterministic and testable, and this class
 *       never touches {@code ChallengeHud}'s state machine — it only <em>returns</em> the effect
 *       the view should apply.</li>
 *   <li>No geometry. Panel sizes, button rectangles and the dialog box differ per era and are
 *       supplied by the view on every call (they are functions of the window size and the
 *       current step), so hit-testing takes the rectangles as arguments instead of computing
 *       them. The model knows nothing about pixels beyond "is this point inside this rectangle".</li>
 *   <li>No {@code java.time}, no lambdas, no {@code var}, no {@code List.of}.</li>
 * </ul>
 *
 * <p>The challenge mode is a <em>read-only</em> input here. The launcher owns it: it writes
 * {@code config/challenge/mode.json} before launch and the mod only reads it, so the model is
 * told the mode on every interaction and never writes one back.
 */
public final class ChallengeSetupModel {

    /** Step 1: no target yet — report the mode and roll first. */
    public static final int STEP_REPORT = 1;
    /** Step 2: a target is rolled — seal it, re-roll it, or record a PB. */
    public static final int STEP_GOAL = 2;

    /** How long the Roll button latches on "Rolling..." before the target is drawn. */
    public static final long ROLL_DELAY_MS = 800L;

    /**
     * The effect a click (or a finished roll) asks the view to perform on
     * {@code ChallengeHud}. The model never performs it itself — it does not touch the state
     * machine, does not roll items and does not write files.
     */
    public enum Action {
        /** Nothing to do: the click missed every control, or the control was a no-op. */
        NONE,
        /** Roll a brand-new target: {@code ChallengeHud.rollTarget()}. */
        ROLL,
        /** Re-roll an already-sealed target: {@code ChallengeHud.rerollSealedFromMenu()}. */
        REROLL_SEALED,
        /** Seal the current target: {@code ChallengeHud.sealFromMenu()}. */
        SEAL,
        /** Close the setup screen. */
        CLOSE_SCREEN
    }

    /** Which confirmation (if any) the modal dialog is currently asking for. */
    public enum ConfirmAction {
        /** No dialog open. */
        NONE,
        /** "Seal this target?" — shown in FULL mode before the first seal. */
        SEAL,
        /** "Throw away the sealed target?" — FULL mode re-roll; carries no item name (B3). */
        RESEAL
    }

    // ---- state -----------------------------------------------------------------

    /** Read-only mirror of {@code ChallengeHud.state == SEALED}, refreshed by the view. */
    private boolean sealed;
    /** Read-only mirror of {@code ChallengeHud.mode == FULL}, refreshed by the view. */
    private boolean fullMode;
    /** {@link #STEP_REPORT} or {@link #STEP_GOAL}. */
    private int step = STEP_REPORT;
    /** True while the roll animation is latched; see {@link #tick(long)}. */
    private boolean rolling;
    /**
     * One-shot token: {@link #tick(long)} arms it when the deadline passes and
     * {@link #finishRolling()} consumes it. Split out from {@link #rolling} so the button's latch
     * can be released immediately (it must not stick, see {@link #tick}) while the completion
     * itself still happens exactly once.
     */
    private boolean rollExpired;
    /** Wall-clock deadline of the current roll, ms since epoch. */
    private long rollingUntilMs;
    /** Non-null while the confirm dialog is open (FULL mode only). */
    private ConfirmAction confirmAction = ConfirmAction.NONE;

    // ---- lifecycle -------------------------------------------------------------

    /**
     * Screen opened. Resets everything and picks the opening step.
     *
     * <p>Rule D: the menu never rolls on open. Step {@link #STEP_GOAL} is only entered
     * directly when a seal is already committed (the screen is opened from the "Sealed" title
     * button); otherwise the player has to ask for a roll first.
     *
     * @param sealed   whether a challenge is already committed on disk
     * @param fullMode the launcher-reported mode (FULL = speedrun, confirmation dialogs on)
     */
    public void init(boolean sealed, boolean fullMode) {
        this.sealed = sealed;
        this.fullMode = fullMode;
        this.step = sealed ? STEP_GOAL : STEP_REPORT;
        this.rolling = false;
        this.rollExpired = false;
        this.rollingUntilMs = 0L;
        this.confirmAction = ConfirmAction.NONE;
    }

    /**
     * Refreshes the two launcher-owned inputs from {@code ChallengeHud}.
     *
     * <p>Called by the view before it uses the model, because both can change while the menu is
     * open (CASUAL seals in place without closing the screen; the core restores a sealed target
     * from disk). Caching them at {@link #init} time instead would let the menu act on a stale
     * seal/mode for as long as it stays open.
     */
    public void syncContext(boolean sealed, boolean fullMode) {
        this.sealed = sealed;
        this.fullMode = fullMode;
    }

    // ---- read-only state for the view ------------------------------------------

    /** {@link #STEP_REPORT} or {@link #STEP_GOAL} — drives the panel size and the item frame. */
    public int step() {
        return step;
    }

    /** True while the roll animation is latched (the Roll button reads "Rolling..."). */
    public boolean rolling() {
        return rolling;
    }

    /** True while the modal confirm dialog is open; the view draws it on top. */
    public boolean dialogOpen() {
        return confirmAction != ConfirmAction.NONE;
    }

    /** True when the open dialog is the re-seal one, whose body must not name the item (B3). */
    public boolean isResealDialog() {
        return confirmAction == ConfirmAction.RESEAL;
    }

    // ---- roll animation --------------------------------------------------------

    /** Latches the Roll button for {@link #ROLL_DELAY_MS} from {@code nowMs}. */
    public void startRolling(long nowMs) {
        rolling = true;
        rollExpired = false;
        rollingUntilMs = nowMs + ROLL_DELAY_MS;
    }

    /**
     * Advances the roll animation by one client tick.
     *
     * <p>Returns {@code true} when the deadline has passed and the view must call
     * {@link #finishRolling()} to roll the item — normally exactly once per started roll.
     *
     * <p>The button's latch ({@link #rolling}) is released <em>here</em>, before the caller gets a
     * chance to act, so it can never stick: no matter how many ticks go by, and no matter whether
     * the caller acts on the return value, a later {@link #tick} is not rolling. The completion
     * itself is kept separate, in {@link #finishRolling()}, so it still happens exactly once.
     *
     * <p>Boundary: the roll survives {@code nowMs == rollingUntilMs - 1} and expires at
     * {@code nowMs == rollingUntilMs} (matching {@code now >= deadline}).
     *
     * @return true when the deadline just passed and the roll must be completed
     */
    public boolean tick(long nowMs) {
        if (!rolling || nowMs < rollingUntilMs) return false;
        rolling = false;
        rollExpired = true;
        return true;
    }

    /**
     * Completes a finished roll: advances to step 2 and reports which kind of roll it was — a
     * re-roll of an existing commitment, or a brand-new target.
     *
     * <p>Only meaningful right after {@link #tick(long)} reported the deadline; it consumes that
     * one-shot token, so a redundant or duplicated call is a no-op ({@link Action#NONE}) and can
     * never roll twice. That matters because the view reaches this through a single named method
     * that CI asserts is called from {@code tick()}.
     *
     * @return {@link Action#REROLL_SEALED} if a target was already sealed, else {@link Action#ROLL};
     *         {@link Action#NONE} if there was no roll to complete
     */
    public Action finishRolling() {
        if (!rollExpired) return Action.NONE;  // nothing to complete: touch nothing at all
        rollExpired = false;
        rolling = false;
        rollingUntilMs = 0L;
        step = STEP_GOAL;
        return sealed ? Action.REROLL_SEALED : Action.ROLL;
    }

    // ---- input -----------------------------------------------------------------

    /**
     * Routes one click at GUI-scaled coordinates to an action.
     *
     * <p>The rectangles come from the view because they depend on the window size, the current
     * step and the dialog box — all per-era/per-window values the model deliberately does not
     * know. Order matters and matches the original menu: the modal dialog swallows every click,
     * a latched roll swallows every click, and only then are the panel buttons tested (Seal is
     * tested before Roll in step 2, because Seal's rectangle sits above it).
     *
     * @param sealRect   Seal button (step 2 only)
     * @param rollRect   Roll button
     * @param backRect   Back button
     * @param lockRect   dialog OK (Lock) button
     * @param cancelRect dialog Cancel button
     * @return the effect the view must apply; {@link Action#NONE} for a miss or a swallowed click
     */
    public Action onClick(double mouseX, double mouseY, long nowMs,
            int[] sealRect, int[] rollRect, int[] backRect,
            int[] lockRect, int[] cancelRect) {
        if (confirmAction != ConfirmAction.NONE) {
            if (inRect(mouseX, mouseY, lockRect)) {
                ConfirmAction accepted = confirmAction;
                confirmAction = ConfirmAction.NONE;
                if (accepted == ConfirmAction.RESEAL) return Action.REROLL_SEALED;
                if (accepted == ConfirmAction.SEAL) return Action.SEAL;
                return Action.NONE;
            }
            if (inRect(mouseX, mouseY, cancelRect)) {
                confirmAction = ConfirmAction.NONE;
            }
            return Action.NONE;
        }
        if (rolling) return Action.NONE;
        if (step == STEP_REPORT) {
            if (inRect(mouseX, mouseY, rollRect)) return clickRoll(nowMs);
            if (inRect(mouseX, mouseY, backRect)) return Action.CLOSE_SCREEN;
        } else {
            if (inRect(mouseX, mouseY, sealRect)) return clickSeal();
            if (inRect(mouseX, mouseY, rollRect)) return clickRoll(nowMs);
            if (inRect(mouseX, mouseY, backRect)) return Action.CLOSE_SCREEN;
        }
        return Action.NONE;
    }

    /**
     * Rule B2: ESC closes the confirm dialog first and only then the screen, so a stray ESC
     * cannot dismiss a confirmation the player did not read. Closing the dialog does not move
     * the step and does not cancel a roll — only the dialog's own Cancel does the latter, which
     * is the same thing.
     *
     * @return {@link Action#CLOSE_SCREEN}, or {@link Action#NONE} if a dialog was open
     */
    public Action onEscape() {
        if (confirmAction != ConfirmAction.NONE) {
            confirmAction = ConfirmAction.NONE;
            return Action.NONE;
        }
        return Action.CLOSE_SCREEN;
    }

    // ---- button semantics ------------------------------------------------------

    /**
     * Roll button. Starting the animation is internal, so this reports no external effect.
     *
     * <p>Re-rolling an existing commitment needs confirmation in FULL mode only — CASUAL is
     * free practice and re-rolls immediately.
     */
    private Action clickRoll(long nowMs) {
        if (rolling) return Action.NONE;
        if (sealed && fullMode) {
            // B3: confirm first (dialog without item name); CASUAL re-rolls freely.
            confirmAction = ConfirmAction.RESEAL;
            return Action.NONE;
        }
        startRolling(nowMs);
        return Action.NONE;
    }

    /** Seal button. No-op while sealed (the label then reads "Sealed — enter a world"). */
    private Action clickSeal() {
        if (rolling) return Action.NONE;
        if (sealed) return Action.NONE;
        if (fullMode) {
            confirmAction = ConfirmAction.SEAL;
            return Action.NONE;
        }
        // CASUAL: seal directly, no confirmation dialog.
        return Action.SEAL;
    }

    private static boolean inRect(double x, double y, int[] r) {
        return x >= r[0] && x < r[0] + r[2] && y >= r[1] && y < r[1] + r[3];
    }
}
