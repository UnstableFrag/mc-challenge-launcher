package com.github.mcchallenge.challengehud;

import com.mojang.blaze3d.platform.InputConstants;
import java.lang.reflect.Method;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * Interactive challenge setup menu (MC 1.21.6+). Custom-drawn UI (no vanilla widgets):
 * two steps — roll a target, then seal it. ESC closes the confirm dialog first (B2),
 * never rolls on open (D), and the RESEAL dialog carries no item name (B3).
 *
 * <p>Input handling: 1.21.9 replaced {@code mouseClicked(double,double,int)} with an
 * event-object parameter ({@code MouseButtonEvent}) whose class does not exist on
 * 1.21.6..1.21.8, so no single override signature compiles on the whole era. The keyboard
 * callback breaks over the same range ({@code keyPressed(int,int,int)} →
 * {@code keyPressed(KeyEvent)}), so overriding it is no help either. All mouse and keyboard
 * input is therefore polled in {@link #tick()} from the raw input state. The mouse surface
 * ({@code MouseHandler.isLeftPressed}, {@code getScaledXPos/getScaledYPos}) is stable across
 * 1.21.6..1.21.11 and is called directly; the keyboard surface
 * ({@code InputConstants.isKeyDown}) is <em>not</em> — its signature flips mid-era, so it
 * goes through the reflective lookup in {@link #isKeyDown(int)}.
 *
 * <p>Layout (all eras share the same coordinates):
 * <pre>
 *   step 1 panel: width min(280, w-24), height min(180, h-24)
 *   step 2 panel: width min(320, w-24), height min(220, h-24)
 *   panelY = max(10, (h - panelH)/2 - 16)
 *     +14  title "MC Challenge"
 *     +26  mode badge (FULL accent / CASUAL text)
 *     +38  mode rules line
 *     +50  category (FULL only: fresh legit / practice warning)
 *     +62  48x48 item frame (step 2 only, item rendered at native 16x16, centered)
 *     +116 item name (step 2; registry id fallback in text color)
 *     +128 PB (step 2, FULL + fresh only)
 *     h-48 Seal button (160x20, centered, step 2 only)
 *     h-24 Roll (90) + Back (90)
 *   confirm dialog: 330x112 centered overlay, [Lock] [Cancel]
 * </pre>
 */
public class ChallengeSetupScreen extends Screen {
    private static final int COLOR_TEXT = 0xFFA6ADC8;
    private static final int COLOR_ACCENT = 0xFF89B4FA;
    private static final int COLOR_NAME = 0xFFFFFFFF;
    private static final int COLOR_WARNING = 0xFFFF5555;

    private static final long ROLL_DELAY_MS = 800;

    /** 1 = no target yet (roll first), 2 = target rolled (seal / re-roll). */
    private int step = 1;

    /** Non-null while the confirm dialog is open (FULL mode). */
    private ConfirmAction confirmAction = ConfirmAction.NONE;
    private boolean rolling = false;
    private long rollingUntilMs = 0;

    /** Previous-frame input state for rising-edge detection in {@link #tick()}. */
    private boolean prevLeftDown = false;
    private boolean prevEscDown = false;

    /** Cached {@code InputConstants.isKeyDown} lookup; see {@link #isKeyDown(int)}. */
    private static Method keyDownMethod;
    /** True when {@link #keyDownMethod} takes the {@code Window}, false when it takes a GLFW handle. */
    private static boolean keyDownTakesWindow;
    /** Cached no-arg {@code long} handle accessor on the window; see {@link #resolveHandle(Class)}. */
    private static Method handleMethod;
    private static boolean keyDownResolved = false;

    enum ConfirmAction { NONE, SEAL, RESEAL }

    public ChallengeSetupScreen() {
        super(EraBridge.text("MC Challenge"));
    }

    @Override
    protected void init() {
        // D: never roll on open. Jump straight to step 2 when a seal is already
        // committed (opened from the "Sealed" title button).
        step = ChallengeHud.state == ChallengeHud.State.SEALED ? 2 : 1;
        rolling = false;
        confirmAction = ConfirmAction.NONE;
        prevLeftDown = false;
        prevEscDown = false;
        ChallengeHud.loadRecords();
    }

    // ---- layout helpers -------------------------------------------------------

    private int panelW() {
        return Math.min(step == 1 ? 280 : 320, width - 24);
    }

    private int panelH() {
        return Math.min(step == 1 ? 180 : 220, height - 24);
    }

    private int panelX() {
        return width / 2 - panelW() / 2;
    }

    private int panelY() {
        return Math.max(10, (height - panelH()) / 2 - 16);
    }

    private int[] sealRect() {
        return new int[] { width / 2 - 80, panelY() + panelH() - 48, 160, 20 };
    }

    private int[] rollRect() {
        return new int[] { width / 2 - 94, panelY() + panelH() - 24, 90, 20 };
    }

    private int[] backRect() {
        return new int[] { width / 2 + 4, panelY() + panelH() - 24, 90, 20 };
    }

    private int[] lockRect() {
        return new int[] { width / 2 - 110, height / 2 + 26, 100, 20 };
    }

    private int[] cancelRect() {
        return new int[] { width / 2 + 10, height / 2 + 26, 100, 20 };
    }

    // ---- actions ---------------------------------------------------------------

    private void clickRoll() {
        if (rolling) return;
        if (ChallengeHud.state == ChallengeHud.State.SEALED) {
            if (ChallengeHud.mode == ChallengeHud.ChallengeMode.FULL) {
                // B3: confirm first (dialog without item name); CASUAL re-rolls freely.
                confirmAction = ConfirmAction.RESEAL;
            } else {
                startRolling();
            }
            return;
        }
        startRolling();
    }

    private void clickSeal() {
        if (rolling) return;
        if (ChallengeHud.state == ChallengeHud.State.SEALED) {
            return; // button shows "Sealed — enter a world"; no-op
        }
        if (ChallengeHud.mode == ChallengeHud.ChallengeMode.FULL) {
            confirmAction = ConfirmAction.SEAL;
            return;
        }
        // CASUAL: seal directly, no confirmation dialog.
        ChallengeHud.sealFromMenu();
    }

    private void startRolling() {
        rolling = true;
        rollingUntilMs = System.currentTimeMillis() + ROLL_DELAY_MS;
    }

    private void finishRolling() {
        rolling = false;
        if (ChallengeHud.state == ChallengeHud.State.SEALED) {
            ChallengeHud.rerollSealedFromMenu();
        } else {
            ChallengeHud.rollTarget();
        }
        step = 2;
    }

    private void doConfirm() {
        if (confirmAction == ConfirmAction.SEAL) {
            ChallengeHud.sealFromMenu();
        } else if (confirmAction == ConfirmAction.RESEAL) {
            ChallengeHud.rerollSealedFromMenu();
        }
        confirmAction = ConfirmAction.NONE;
    }

    // ---- input (tick polling) ----------------------------------------------------

    @Override
    public void tick() {
        if (rolling && System.currentTimeMillis() >= rollingUntilMs) {
            finishRolling();
        }
        Minecraft mc = ChallengeHud.client;
        if (mc == null) return;
        if (mc.mouseHandler != null) {
            MouseHandler mh = mc.mouseHandler;
            boolean down = mh.isLeftPressed();
            if (down && !prevLeftDown) {
                handleClick(mh.getScaledXPos(mc.getWindow()), mh.getScaledYPos(mc.getWindow()));
            }
            prevLeftDown = down;
        }
        if (mc.getWindow() != null) {
            // B2: ESC closes the confirm dialog first, then the screen.
            boolean esc = isKeyDown(InputConstants.KEY_ESCAPE);
            if (esc && !prevEscDown) {
                if (confirmAction != ConfirmAction.NONE) {
                    confirmAction = ConfirmAction.NONE;
                } else {
                    onClose();
                }
            }
            prevEscDown = esc;
        }
    }

    /**
     * Reads the raw pressed-state of a key without hard-linking to one {@code InputConstants}
     * signature, because this source set is compiled once and remapped across 1.21.6..1.21.11 and
     * the signature <em>flips inside that range</em>:
     *
     * <ul>
     *   <li>1.21.6: {@code isKeyDown(long window, int key)} — raw GLFW handle, so passing a
     *       {@code Window} fails to compile: {@code error: incompatible types: Window cannot be
     *       converted to long}. The handle is read via {@code Window.getWindow()}.
     *   <li>1.21.11: {@code isKeyDown(Window window, int key)}, and the handle accessor has
     *       become {@code Window.handle()}.
     * </ul>
     *
     * <p>No single direct call can satisfy both ends, so we resolve the overload plus the handle
     * accessor reflectively, on first use, and cache them. A miss degrades to "key not held"
     * (costs ESC-to-close; the mouse path in {@link #tick()} is untouched) instead of throwing —
     * this is the same reason input is polled from raw state at all, since the keyboard callback
     * breaks over the same range ({@code keyPressed(int,int,int)} → {@code keyPressed(KeyEvent)}).
     */
    private static boolean isKeyDown(int keyCode) {
        Minecraft mc = ChallengeHud.client;
        Object window = mc == null ? null : mc.getWindow();
        if (window == null) return false;

        if (!keyDownResolved) {
            keyDownResolved = true;
            keyDownMethod = resolveKeyDown(window.getClass());
            // Discriminate on the real parameter type: resolveKeyDown() falls back to the
            // handle overload when no Window one exists, so a non-null result alone does
            // not tell us which one we got.
            keyDownTakesWindow = keyDownMethod != null
                    && keyDownMethod.getParameterTypes()[0].isAssignableFrom(window.getClass());
            if (keyDownMethod != null) {
                keyDownMethod.setAccessible(true);
                handleMethod = resolveHandle(window.getClass());
                if (handleMethod != null) handleMethod.setAccessible(true);
            }
        }
        if (keyDownMethod == null) return false;

        try {
            Object arg = keyDownTakesWindow ? window : (Object) glfwHandle(window);
            return (Boolean) keyDownMethod.invoke(null, arg, keyCode);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Prefers the {@code Window}-taking overload; falls back to the raw-handle one. */
    private static Method resolveKeyDown(Class<?> windowClass) {
        Method byHandle = null;
        for (Method m : InputConstants.class.getMethods()) {
            if (!"isKeyDown".equals(m.getName())) continue;
            Class<?>[] params = m.getParameterTypes();
            if (params.length != 2 || params[1] != int.class) continue;
            if (params[0].isAssignableFrom(windowClass)) return m;
            if (byHandle == null && params[0] == long.class) byHandle = m;
        }
        return byHandle;
    }

    /** Finds the GLFW handle accessor: {@code handle()} on 1.21.11+, else {@code getWindow()}. */
    private static Method resolveHandle(Class<?> windowClass) {
        Method fallback = null;
        for (Method m : windowClass.getMethods()) {
            if (m.getParameterCount() != 0 || m.getReturnType() != long.class) continue;
            if ("handle".equals(m.getName())) return m;
            if ("getWindow".equals(m.getName())) fallback = m;
        }
        return fallback;
    }

    private static long glfwHandle(Object window) throws ReflectiveOperationException {
        if (handleMethod == null) throw new NoSuchMethodException("no GLFW handle accessor on " + window.getClass());
        return (Long) handleMethod.invoke(window);
    }

    /** Processes a fresh left click at GUI-scaled coordinates (dialog hit-test first). */
    private void handleClick(double mouseX, double mouseY) {
        if (confirmAction != ConfirmAction.NONE) {
            if (inRect(mouseX, mouseY, lockRect())) {
                doConfirm();
            } else if (inRect(mouseX, mouseY, cancelRect())) {
                confirmAction = ConfirmAction.NONE;
            }
            return;
        }
        if (rolling) return;
        if (step == 1) {
            if (inRect(mouseX, mouseY, rollRect())) clickRoll();
            else if (inRect(mouseX, mouseY, backRect())) onClose();
        } else {
            if (inRect(mouseX, mouseY, sealRect())) clickSeal();
            else if (inRect(mouseX, mouseY, rollRect())) clickRoll();
            else if (inRect(mouseX, mouseY, backRect())) onClose();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---- rendering ---------------------------------------------------------------

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        renderBackground(graphics, mouseX, mouseY, delta);
        int cx = width / 2;
        int panelW = panelW();
        int panelH = panelH();
        int panelX = panelX();
        int panelY = panelY();

        // Panel background + 1px accent border
        graphics.fillGradient(panelX, panelY, panelX + panelW, panelY + panelH,
            0x99000000, 0xCC111111);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + 1, COLOR_ACCENT);
        graphics.fill(panelX, panelY + panelH - 1, panelX + panelW, panelY + panelH, COLOR_ACCENT);
        graphics.fill(panelX, panelY, panelX + 1, panelY + panelH, COLOR_ACCENT);
        graphics.fill(panelX + panelW - 1, panelY, panelX + panelW, panelY + panelH, COLOR_ACCENT);

        drawCentered(graphics, translate("challengehud.menu.title"), cx, panelY + 14, COLOR_NAME);

        boolean full = ChallengeHud.mode == ChallengeHud.ChallengeMode.FULL;
        drawCentered(graphics,
            translate(full ? "challengehud.menu.mode.full" : "challengehud.menu.mode.casual"),
            cx, panelY + 26, full ? COLOR_ACCENT : COLOR_TEXT);

        String rules = font.plainSubstrByWidth(
            translate(full ? "challengehud.menu.rules.full" : "challengehud.menu.rules.casual"),
            panelW - 24);
        drawCentered(graphics, rules, cx, panelY + 38, COLOR_TEXT);

        if (full) {
            String cat = font.plainSubstrByWidth(translate(
                ChallengeHud.freshWorld
                    ? "challengehud.menu.category.fresh"
                    : "challengehud.menu.category.practice"),
                panelW - 24);
            drawCentered(graphics, cat, cx, panelY + 50,
                ChallengeHud.freshWorld ? COLOR_TEXT : COLOR_WARNING);
        }

        ItemStack stack = ChallengeHud.targetStack;
        if (step == 2) {
            // Item frame 48x48 with the icon at native size (16x16), centered.
            int frameX = cx - 24;
            int frameY = panelY + 62;
            graphics.fill(frameX, frameY, frameX + 48, frameY + 48, 0x66000000);
            graphics.fill(frameX, frameY, frameX + 48, frameY + 1, COLOR_ACCENT);
            graphics.fill(frameX, frameY + 47, frameX + 48, frameY + 48, COLOR_ACCENT);
            graphics.fill(frameX, frameY, frameX + 1, frameY + 48, COLOR_ACCENT);
            graphics.fill(frameX + 47, frameY, frameX + 48, frameY + 48, COLOR_ACCENT);
            if (!stack.isEmpty()) {
                RenderUtil.drawItem(graphics, stack, frameX + 16, frameY + 16);
            }

            // Item name with registry-id fallback when the hover name is untranslated.
            String itemName = itemDisplayName(stack);
            boolean fallback = stack.isEmpty() || itemName.equals(EraBridge.itemId(stack.getItem()));
            drawCentered(graphics, font.plainSubstrByWidth(itemName, panelW - 24),
                cx, panelY + 116, fallback ? COLOR_TEXT : COLOR_NAME);

            if (full && ChallengeHud.freshWorld) {
                String pb = ChallengeHud.bestMs < 0 ? "--" : ChallengeHud.formatTime(ChallengeHud.bestMs);
                drawCentered(graphics, font.plainSubstrByWidth(
                    translate("challengehud.menu.pb", pb), panelW - 24),
                    cx, panelY + 128, COLOR_ACCENT);
            }
        }

        drawButton(graphics, rollRect(), mouseX, mouseY,
            translate(rolling ? "challengehud.menu.rolling" : "challengehud.menu.roll"));
        if (step == 2) {
            boolean sealed = ChallengeHud.state == ChallengeHud.State.SEALED;
            drawButton(graphics, sealRect(), mouseX, mouseY,
                translate(sealed ? "challengehud.menu.sealed" : "challengehud.menu.seal"));
        }
        drawButton(graphics, backRect(), mouseX, mouseY, translate("challengehud.menu.back"));

        if (confirmAction != ConfirmAction.NONE) {
            renderConfirmDialog(graphics, cx, height / 2, mouseX, mouseY);
        }
    }

    private void renderConfirmDialog(GuiGraphics graphics, int cx, int cy, int mouseX, int mouseY) {
        // Dim the whole screen behind the dialog.
        graphics.fillGradient(0, 0, width, height, 0x88000000, 0x88000000);

        int dlgW = 330;
        int dlgH = 112;
        int dlgX = cx - dlgW / 2;
        int dlgY = cy - dlgH / 2;
        graphics.fillGradient(dlgX, dlgY, dlgX + dlgW, dlgY + dlgH, 0xE6202020, 0xFF111111);
        graphics.fill(dlgX, dlgY, dlgX + dlgW, dlgY + 1, COLOR_ACCENT);
        graphics.fill(dlgX, dlgY + dlgH - 1, dlgX + dlgW, dlgY + dlgH, COLOR_ACCENT);
        graphics.fill(dlgX, dlgY, dlgX + 1, dlgY + dlgH, COLOR_ACCENT);
        graphics.fill(dlgX + dlgW - 1, dlgY, dlgX + dlgW, dlgY + dlgH, COLOR_ACCENT);

        drawCentered(graphics, translate("challengehud.menu.confirm.title"), cx, dlgY + 14, COLOR_NAME);

        // B3: the RESEAL dialog carries no item name (nothing is committed yet).
        String body = confirmAction == ConfirmAction.RESEAL
            ? translate("challengehud.menu.confirm.reseal.body")
            : translate("challengehud.menu.confirm.body", itemDisplayName(ChallengeHud.targetStack));
        body = font.plainSubstrByWidth(body, dlgW - 24);
        drawCentered(graphics, body, cx, dlgY + 34, COLOR_TEXT);

        drawButton(graphics, lockRect(), mouseX, mouseY, translate("challengehud.menu.confirm.ok"));
        drawButton(graphics, cancelRect(), mouseX, mouseY, translate("challengehud.menu.confirm.cancel"));
    }

    private void drawButton(GuiGraphics graphics, int[] r, int mouseX, int mouseY, String label) {
        boolean hover = inRect(mouseX, mouseY, r);
        graphics.fill(r[0], r[1], r[0] + r[2], r[1] + r[3],
            hover ? 0xFF3B4252 : 0xAA2E3440);
        graphics.fill(r[0], r[1], r[0] + r[2], r[1] + 1, COLOR_ACCENT);
        drawCentered(graphics, font.plainSubstrByWidth(label, r[2] - 8),
            r[0] + r[2] / 2, r[1] + 6, COLOR_NAME);
    }

    private static boolean inRect(double x, double y, int[] r) {
        return x >= r[0] && x < r[0] + r[2] && y >= r[1] && y < r[1] + r[3];
    }

    private void drawCentered(GuiGraphics graphics, String text, int cx, int y, int color) {
        graphics.drawString(font, text, cx - font.width(text) / 2, y, color);
    }

    /** Item name with registry-id fallback when the hover name is empty/untranslated. */
    private static String itemDisplayName(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "";
        String name = stack.getHoverName().getString();
        String id = EraBridge.itemId(stack.getItem());
        if (name == null || name.isEmpty()
                || name.startsWith("item.") || name.startsWith("block.")
                || name.equals(id)) {
            return id;
        }
        return name;
    }

    private static String translate(String key, Object... args) {
        return EraBridge.translate(key, args);
    }
}
