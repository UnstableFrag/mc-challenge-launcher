package com.github.mcchallenge.challengehud;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.ItemStack;

/**
 * Interactive challenge setup menu (MC 1.19.4). Custom-drawn UI (no vanilla
 * widgets): two steps — roll a target, then seal it. ESC closes the confirm dialog
 * first (B2), never rolls on open (D), and the RESEAL dialog carries no item name (B3).
 * Java 8 source compatible (compiles at release 17).
 *
 * <p>The challenge mode is a read-only field: the launcher writes
 * {@code config/challenge/mode.json} at roll time and the mod only reads it, so the menu
 * states the mode instead of offering it as a choice (see {@link #drawModeBlock}).
 */
public class ChallengeSetupScreen extends Screen {
    private static final int COLOR_TEXT = 0xFFA6ADC8;
    private static final int COLOR_ACCENT = 0xFF89B4FA;
    private static final int COLOR_NAME = 0xFFFFFFFF;
    private static final int COLOR_WARNING = 0xFFFF5555;
    /** Recessed, non-interactive row (the mode that is not active). 4.9:1 on the panel. */
    private static final int COLOR_DIM = 0xFF7E8598;
    /** Hairline divider inside the panel — decorative, so it stays below the text contrast. */
    private static final int COLOR_RULE = 0xFF3B4252;

    private static final long ROLL_DELAY_MS = 800;

    /** 1 = no target yet (roll first), 2 = target rolled (seal / re-roll). */
    private int step = 1;

    private ConfirmAction confirmAction = ConfirmAction.NONE;
    private boolean rolling = false;
    private long rollingUntilMs = 0;

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
        ChallengeHud.loadRecords();
    }

    // ---- layout helpers -------------------------------------------------------

    private int panelW() {
        return Math.min(step == 1 ? 280 : 320, width - 24);
    }

    private int panelH() {
        return Math.min(step == 1 ? 176 : 236, height - 24);
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

    // ---- input -----------------------------------------------------------------

    @Override
    public void tick() {
        // Completes the roll animation once the deadline passes, so the Roll
        // button stops latching on "Rolling..." and can be clicked again.
        if (rolling && System.currentTimeMillis() >= rollingUntilMs) {
            finishRolling();
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (confirmAction != ConfirmAction.NONE) {
            if (inRect(mouseX, mouseY, lockRect())) {
                doConfirm();
            } else if (inRect(mouseX, mouseY, cancelRect())) {
                confirmAction = ConfirmAction.NONE;
            }
            return true;
        }
        if (rolling) return true;
        if (step == 1) {
            if (inRect(mouseX, mouseY, rollRect())) clickRoll();
            else if (inRect(mouseX, mouseY, backRect())) onClose();
        } else {
            if (inRect(mouseX, mouseY, sealRect())) clickSeal();
            else if (inRect(mouseX, mouseY, rollRect())) clickRoll();
            else if (inRect(mouseX, mouseY, backRect())) onClose();
        }
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // B2: ESC closes the confirm dialog first, then the screen.
        if (keyCode == 256) {
            if (confirmAction != ConfirmAction.NONE) {
                confirmAction = ConfirmAction.NONE;
            } else {
                onClose();
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---- rendering ---------------------------------------------------------------

    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float delta) {
        renderBackground(poseStack);
        int cx = width / 2;
        int panelW = panelW();
        int panelH = panelH();
        int panelX = panelX();
        int panelY = panelY();

        fillGradient(poseStack, panelX, panelY, panelX + panelW, panelY + panelH,
            0x99000000, 0xCC111111);
        fill(poseStack, panelX, panelY, panelX + panelW, panelY + 1, COLOR_ACCENT);
        fill(poseStack, panelX, panelY + panelH - 1, panelX + panelW, panelY + panelH, COLOR_ACCENT);
        fill(poseStack, panelX, panelY, panelX + 1, panelY + panelH, COLOR_ACCENT);
        fill(poseStack, panelX + panelW - 1, panelY, panelX + panelW, panelY + panelH, COLOR_ACCENT);

        drawCentered(poseStack, translate("challengehud.menu.title"), cx, panelY + 14, COLOR_NAME);

        boolean full = ChallengeHud.mode == ChallengeHud.ChallengeMode.FULL;

        // Read-only mode field (launcher-owned): step 1 states both modes so the choice is
        // visible, step 2 states only the active one.
        drawModeBlock(poseStack, panelX, panelW, panelY, step == 1);

        ItemStack stack = ChallengeHud.targetStack;
        if (step == 2) {
            int frameX = cx - 24;
            int frameY = panelY + 92;
            fill(poseStack, frameX, frameY, frameX + 48, frameY + 48, 0x66000000);
            fill(poseStack, frameX, frameY, frameX + 48, frameY + 1, COLOR_ACCENT);
            fill(poseStack, frameX, frameY + 47, frameX + 48, frameY + 48, COLOR_ACCENT);
            fill(poseStack, frameX, frameY, frameX + 1, frameY + 48, COLOR_ACCENT);
            fill(poseStack, frameX + 47, frameY, frameX + 48, frameY + 48, COLOR_ACCENT);
            if (!stack.isEmpty()) {
                minecraft.getItemRenderer().renderGuiItem(poseStack, stack, frameX + 16, frameY + 16);
            }

            String itemName = itemDisplayName(stack);
            boolean fallback = stack.isEmpty() || itemName.equals(EraBridge.itemId(stack.getItem()));
            drawCentered(poseStack, font.plainSubstrByWidth(itemName, panelW - 24),
                cx, panelY + 148, fallback ? COLOR_TEXT : COLOR_NAME);

            if (full && ChallengeHud.freshWorld) {
                String pb = ChallengeHud.bestMs < 0 ? "--" : ChallengeHud.formatTime(ChallengeHud.bestMs);
                drawCentered(poseStack, font.plainSubstrByWidth(
                    translate("challengehud.menu.pb", pb), panelW - 24),
                    cx, panelY + 160, COLOR_ACCENT);
            }
        }

        drawButton(poseStack, rollRect(), mouseX, mouseY,
            translate(rolling ? "challengehud.menu.rolling" : "challengehud.menu.roll"));
        if (step == 2) {
            boolean sealed = ChallengeHud.state == ChallengeHud.State.SEALED;
            drawButton(poseStack, sealRect(), mouseX, mouseY,
                translate(sealed ? "challengehud.menu.sealed" : "challengehud.menu.seal"));
        }
        drawButton(poseStack, backRect(), mouseX, mouseY, translate("challengehud.menu.back"));

        if (confirmAction != ConfirmAction.NONE) {
            renderConfirmDialog(poseStack, cx, height / 2, mouseX, mouseY);
        }
    }

    /**
     * Read-only mode field.
     *
     * <p>The mode is written by the launcher into {@code config/challenge/mode.json} when it
     * rolls the challenge, and nothing in this screen can change it. So the block is drawn as
     * a data field rather than as a control: left-aligned (dialogs centre, data fields do not),
     * flat (no fill behind the rows), no accent frame, no hover state and no mouse coordinates
     * anywhere in this method — so it cannot react to the pointer and cannot imply that a click
     * would do something.
     *
     * <p>The two rows use the same 3x9 bar geometry and differ only in intensity: accent plus
     * white text for the active mode, {@link #COLOR_DIM} for the other one. That reads as two
     * states of the same reported fact, not as two options to pick between — deliberately not a
     * radio dot, which would promise a choice. The header carries the source ("Set by the
     * launcher") so the value is never read as something the player picked on this screen.
     *
     * @param showBoth step 1 lists both modes; step 2, where the mode is settled and the item
     *                  is what matters, states only the active one
     */
    private void drawModeBlock(PoseStack poseStack, int panelX, int panelW, int panelY, boolean showBoth) {
        int barX = panelX + 16;
        int textX = barX + 8;
        int rightEdge = panelX + panelW - 16;
        int textW = rightEdge - textX;
        boolean full = ChallengeHud.mode == ChallengeHud.ChallengeMode.FULL;

        String label = translate("challengehud.menu.mode.label");
        String source = translate("challengehud.menu.mode.source");
        int room = textW - font.width(label) - 12;
        if (room < 24) room = 24;
        source = font.plainSubstrByWidth(source, room);
        drawString(poseStack, font, label, textX, panelY + 26, COLOR_DIM);
        drawString(poseStack, font, source, rightEdge - font.width(source), panelY + 26, COLOR_DIM);
        fill(poseStack, textX, panelY + 36, rightEdge, panelY + 37, COLOR_RULE);

        String activeName = translate(full ? "challengehud.menu.mode.full" : "challengehud.menu.mode.casual");
        fill(poseStack, barX, panelY + 42, barX + 3, panelY + 51, COLOR_ACCENT);
        drawString(poseStack, font, activeName, textX, panelY + 42, COLOR_NAME);
        String[] activeRules = wrapText(
            translate(full ? "challengehud.menu.rules.full" : "challengehud.menu.rules.casual"), textW, 2);
        for (int i = 0; i < activeRules.length; i++) {
            drawString(poseStack, font, activeRules[i], textX, panelY + 53 + i * 10, COLOR_TEXT);
        }

        if (showBoth) {
            String otherName = translate(full ? "challengehud.menu.mode.casual" : "challengehud.menu.mode.full");
            fill(poseStack, barX, panelY + 76, barX + 3, panelY + 85, COLOR_DIM);
            drawString(poseStack, font, otherName, textX, panelY + 76, COLOR_DIM);
            String[] otherRules = wrapText(
                translate(full ? "challengehud.menu.rules.casual" : "challengehud.menu.rules.full"), textW, 2);
            for (int i = 0; i < otherRules.length; i++) {
                drawString(poseStack, font, otherRules[i], textX, panelY + 87 + i * 10, COLOR_DIM);
            }
        }

        // Same story as the mode: the launcher reports whether this run got a fresh instance.
        if (full) {
            String cat = wrapText(translate(
                ChallengeHud.freshWorld
                    ? "challengehud.menu.category.fresh"
                    : "challengehud.menu.category.practice"), textW, 1)[0];
            drawString(poseStack, font, cat, textX, panelY + (showBoth ? 110 : 76),
                ChallengeHud.freshWorld ? COLOR_TEXT : COLOR_WARNING);
        }
    }

    private void renderConfirmDialog(PoseStack poseStack, int cx, int cy, int mouseX, int mouseY) {
        fillGradient(poseStack, 0, 0, width, height, 0x88000000, 0x88000000);

        int dlgW = 330;
        int dlgH = 112;
        int dlgX = cx - dlgW / 2;
        int dlgY = cy - dlgH / 2;
        fillGradient(poseStack, dlgX, dlgY, dlgX + dlgW, dlgY + dlgH, 0xE6202020, 0xFF111111);
        fill(poseStack, dlgX, dlgY, dlgX + dlgW, dlgY + 1, COLOR_ACCENT);
        fill(poseStack, dlgX, dlgY + dlgH - 1, dlgX + dlgW, dlgY + dlgH, COLOR_ACCENT);
        fill(poseStack, dlgX, dlgY, dlgX + 1, dlgY + dlgH, COLOR_ACCENT);
        fill(poseStack, dlgX + dlgW - 1, dlgY, dlgX + dlgW, dlgY + dlgH, COLOR_ACCENT);

        drawCentered(poseStack, translate("challengehud.menu.confirm.title"), cx, dlgY + 14, COLOR_NAME);

        // B3: the RESEAL dialog carries no item name (nothing is committed yet).
        String body = confirmAction == ConfirmAction.RESEAL
            ? translate("challengehud.menu.confirm.reseal.body")
            : translate("challengehud.menu.confirm.body", itemDisplayName(ChallengeHud.targetStack));
        body = font.plainSubstrByWidth(body, dlgW - 24);
        drawCentered(poseStack, body, cx, dlgY + 34, COLOR_TEXT);

        drawButton(poseStack, lockRect(), mouseX, mouseY, translate("challengehud.menu.confirm.ok"));
        drawButton(poseStack, cancelRect(), mouseX, mouseY, translate("challengehud.menu.confirm.cancel"));
    }

    private void drawButton(PoseStack poseStack, int[] r, int mouseX, int mouseY, String label) {
        boolean hover = inRect(mouseX, mouseY, r);
        fill(poseStack, r[0], r[1], r[0] + r[2], r[1] + r[3],
            hover ? 0xFF3B4252 : 0xAA2E3440);
        fill(poseStack, r[0], r[1], r[0] + r[2], r[1] + 1, COLOR_ACCENT);
        drawCentered(poseStack, font.plainSubstrByWidth(label, r[2] - 8),
            r[0] + r[2] / 2, r[1] + 6, COLOR_NAME);
    }

    private static boolean inRect(double x, double y, int[] r) {
        return x >= r[0] && x < r[0] + r[2] && y >= r[1] && y < r[1] + r[3];
    }

    private void drawCentered(PoseStack poseStack, String text, int cx, int y, int color) {
        drawString(poseStack, font, text, cx - font.width(text) / 2, y, color);
    }

    /**
     * Greedy word wrap to {@code maxWidth}, capped at {@code maxLines}; the final line is
     * ellipsized when the text does not fit. Built on {@link java.util.List} and
     * {@code font.width} only, so it is identical on every era and still Java 8 source level.
     */
    private String[] wrapText(String text, int maxWidth, int maxLines) {
        List<String> lines = new ArrayList<String>();
        StringBuilder line = new StringBuilder();
        boolean overflow = false;
        for (String word : text.split(" ")) {
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (line.length() == 0 || font.width(candidate) <= maxWidth) {
                line.setLength(0);
                line.append(candidate);
                continue;
            }
            lines.add(line.toString());
            line.setLength(0);
            if (lines.size() == maxLines) {
                overflow = true;
                break;
            }
            line.append(word);
        }
        if (overflow) {
            int last = lines.size() - 1;
            lines.set(last, font.plainSubstrByWidth(lines.get(last) + "...", maxWidth));
        } else if (line.length() > 0) {
            lines.add(line.toString());
        }
        // A single word longer than the field still has to stay inside the panel.
        for (int i = 0; i < lines.size(); i++) {
            if (font.width(lines.get(i)) > maxWidth) {
                lines.set(i, font.plainSubstrByWidth(lines.get(i), maxWidth));
            }
        }
        return lines.toArray(new String[0]);
    }

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
