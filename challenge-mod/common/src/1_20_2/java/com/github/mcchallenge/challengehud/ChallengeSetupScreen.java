package com.github.mcchallenge.challengehud;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * Interactive challenge setup menu (MC 1.20.2..1.21.5). Custom-drawn UI (no vanilla
 * widgets): two steps — roll a target, then seal it. ESC closes the confirm dialog
 * first (B2), never rolls on open (D), and the RESEAL dialog carries no item name (B3).
 */
public class ChallengeSetupScreen extends Screen {
    private static final int COLOR_TEXT = 0xFFA6ADC8;
    private static final int COLOR_ACCENT = 0xFF89B4FA;
    private static final int COLOR_NAME = 0xFFFFFFFF;
    private static final int COLOR_WARNING = 0xFFFF5555;

    private static final long ROLL_DELAY_MS = 800;

    /** 1 = no target yet (roll first), 2 = target rolled (seal / re-roll). */
    private int step = 1;

    private ConfirmAction confirmAction = ConfirmAction.NONE;
    private boolean rolling = false;
    private long rollingUntilMs = 0;

    enum ConfirmAction { NONE, SEAL, RESEAL }

    public ChallengeSetupScreen() {
        super(Component.literal("MC Challenge"));
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
        if (ChallengeHud.state == ChallengeHud.State.SEALED) return;
        if (ChallengeHud.mode == ChallengeHud.ChallengeMode.FULL) {
            confirmAction = ConfirmAction.SEAL;
            return;
        }
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
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        renderBackground(graphics, mouseX, mouseY, delta);
        int cx = width / 2;
        int panelW = panelW();
        int panelH = panelH();
        int panelX = panelX();
        int panelY = panelY();

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

            String itemName = itemDisplayName(stack);
            boolean fallback = stack.isEmpty() || itemName.equals(EraBridge.itemId(stack.getItem()));
            drawCentered(graphics, font.plainSubstrByWidth(itemName, panelW - 24),
                cx, panelY + 116, fallback ? COLOR_TEXT : COLOR_NAME);

            if (full && ChallengeHud.freshWorld) {
                String pb = ChallengeHud.bestMs < 0 ? "--"
                    : ChallengeHud.formatTime(ChallengeHud.bestMs);
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
