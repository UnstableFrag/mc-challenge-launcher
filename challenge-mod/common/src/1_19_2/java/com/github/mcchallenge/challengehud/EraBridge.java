package com.github.mcchallenge.challengehud;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.architectury.event.events.client.ClientGuiEvent;
import dev.architectury.event.events.client.ClientTickEvent;
import dev.architectury.platform.Platform;
import dev.architectury.registry.client.keymappings.KeyMappingRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.Registry;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Era bridge for MC 1.19.2 (architectury dev.* packages, PoseStack HUD,
 * Component.literal, Registry.ITEM, renderGuiItem(ItemStack,int,int)).
 * Java 8 source compatible (compiles at release 17).
 */
public final class EraBridge extends GuiComponent {
    // GuiComponent is abstract with no abstract methods and a public ctor; the instance is
    // needed because fillGradient(PoseStack,...) is a protected instance method in this era.
    private static final EraBridge GUI = new EraBridge();

    private static final int COLOR_TEXT = 0xFFA6ADC8;
    private static final int COLOR_ACCENT = 0xFF89B4FA;
    private static final int COLOR_NAME = 0xFFFFFFFF;
    private static final int COLOR_WARNING = 0xFFFF5555;

    private static final Logger LOGGER = LoggerFactory.getLogger("challengehud");

    /** Title screen button (top-right corner); null until a TitleScreen is shown. */
    private static Button startButton;

    /** Global "dishonest" keybind (H) — starts a challenge only when nothing is sealed/active. */
    private static final KeyMapping START_KEY = new KeyMapping(
        "key.challengehud.start", InputConstants.KEY_H, "key.categories.challengehud");

    private EraBridge() {}

    public static void registerEvents() {
        ClientTickEvent.CLIENT_POST.register(ChallengeHud::tick);
        ClientGuiEvent.RENDER_HUD.register((matrices, delta) -> renderHud(matrices));
        KeyMappingRegistry.register(START_KEY);
        ClientTickEvent.CLIENT_POST.register(client -> {
            if (START_KEY.consumeClick()) {
                ChallengeHud.tryStartDishonest();
            }
        });
        ClientGuiEvent.INIT_POST.register((screen, access) -> {
            if (screen instanceof TitleScreen) {
                Minecraft client = ChallengeHud.client;
                int w = client.getWindow().getGuiScaledWidth();
                if (w < 320) {
                    LOGGER.info("Title screen too narrow ({} px); skipping challenge button", w);
                    return;
                }
                // B1 mutual exclusion: never offer the setup screen while a run is
                // active or a result is pending; only IDLE (start) and SEALED (view/re-seal).
                if (ChallengeHud.state == ChallengeHud.State.ACTIVE
                        || ChallengeHud.state == ChallengeHud.State.DONE) {
                    LOGGER.info("Challenge in progress ({}); hiding setup button", ChallengeHud.state);
                    return;
                }
                startButton = new Button(w - 158, 8, 150, 20,
                    text(titleButtonLabel()),
                    b -> {
                        if (ChallengeHud.state == ChallengeHud.State.IDLE
                                || ChallengeHud.state == ChallengeHud.State.SEALED) {
                            client.setScreen(new ChallengeSetupScreen());
                        }
                    });
                access.addRenderableWidget(startButton);
                updateStartButton();
            }
        });
    }

    private static String titleButtonLabel() {
        return translate(ChallengeHud.state == ChallengeHud.State.SEALED
            ? "challengehud.title.sealed" : "challengehud.title.start");
    }

    private static void updateStartButton() {
        if (startButton == null) return;
        startButton.setMessage(text(titleButtonLabel()));
    }

    public static void renderHud(PoseStack poseStack) {
        Minecraft client = ChallengeHud.client;
        if (client == null || client.player == null) return;

        int width = client.getWindow().getGuiScaledWidth();
        int height = client.getWindow().getGuiScaledHeight();

        drawToast(poseStack, client, width);

        // SEALED is drawn too: a sealed challenge that cannot promote (e.g. blocked
        // because the world is not hardcore) keeps its target visible on screen.
        if ((ChallengeHud.state == ChallengeHud.State.ACTIVE
                || ChallengeHud.state == ChallengeHud.State.SEALED)
                && !ChallengeHud.targetStack.isEmpty()) {
            renderPanel(poseStack, client, width, height,
                ChallengeHud.state == ChallengeHud.State.SEALED && ChallengeHud.hardcoreBlocked);
        } else if (ChallengeHud.state == ChallengeHud.State.IDLE
                && ChallengeHud.mode == ChallengeHud.ChallengeMode.FULL) {
            drawIdleHint(poseStack, client, width, height);
        }
    }

    private static void renderPanel(PoseStack poseStack, Minecraft client, int width, int height, boolean blocked) {
        boolean record = ChallengeHud.mode == ChallengeHud.ChallengeMode.FULL
            && ChallengeHud.freshWorld;
        int panelW = Math.min(250, width - 16);
        // C: taller panel when the PB/attempts row is shown (FULL + fresh world).
        int panelH = record ? 80 : 68;
        if (panelW < 200 || panelH < 48) return;
        int panelX = width - panelW - 8;
        int panelY = 8;

        // Panel background + 1px accent border
        GUI.fillGradient(poseStack, panelX, panelY, panelX + panelW, panelY + panelH,
            0x99000000, 0xCC111111);
        int bx = panelX, by = panelY, bw = panelW, bh = panelH;
        GUI.fill(poseStack, bx, by, bx + bw, by + 1, COLOR_ACCENT);
        GUI.fill(poseStack, bx, by + bh - 1, bx + bw, by + bh, COLOR_ACCENT);
        GUI.fill(poseStack, bx, by, bx + 1, by + bh, COLOR_ACCENT);
        GUI.fill(poseStack, bx + bw - 1, by, bx + bw, by + bh, COLOR_ACCENT);

        // Item frame (40x40) with centered icon
        int frameX = panelX + 6;
        int frameY = panelY + 8;
        int frameSize = 40;
        GUI.fill(poseStack, frameX, frameY, frameX + frameSize, frameY + frameSize, 0x66000000);
        GUI.fill(poseStack, frameX, frameY, frameX + frameSize, frameY + 1, COLOR_ACCENT);
        GUI.fill(poseStack, frameX, frameY + frameSize - 1, frameX + frameSize, frameY + frameSize, COLOR_ACCENT);
        GUI.fill(poseStack, frameX, frameY, frameX + 1, frameY + frameSize, COLOR_ACCENT);
        GUI.fill(poseStack, frameX + frameSize - 1, frameY, frameX + frameSize, frameY + frameSize, COLOR_ACCENT);
        client.getItemRenderer().renderGuiItem(ChallengeHud.targetStack, frameX + 12, frameY + 12);

        // Text block right of the frame
        int tx = panelX + 54;
        GUI.drawString(poseStack, client.font,
            translate("challengehud.hud.title"), tx, panelY + 7, COLOR_ACCENT);
        GUI.drawString(poseStack, client.font,
            itemName(ChallengeHud.targetStack, panelW - 62),
            tx, panelY + 17, COLOR_NAME);

        // C: RTA (prominent) + IGT rows. RTA keeps running through pauses, IGT
        // accumulates 50 ms per client tick and is frozen while paused.
        GUI.drawString(poseStack, client.font,
            translate("challengehud.hud.rta", ChallengeHud.formatHms(ChallengeHud.rtaMillis())),
            tx, panelY + 27, COLOR_ACCENT);
        GUI.drawString(poseStack, client.font,
            translate("challengehud.hud.igt", ChallengeHud.formatHms(ChallengeHud.igtMs)),
            tx, panelY + 37, COLOR_TEXT);

        // PB + attempts (FULL mode, fresh world only)
        if (record) {
            String pb = ChallengeHud.bestMs < 0 ? "--" : ChallengeHud.formatTime(ChallengeHud.bestMs);
            String pbLine = translate("challengehud.hud.pb", pb)
                + " \u00B7 " + translate("challengehud.hud.attempts", ChallengeHud.attempts);
            GUI.drawString(poseStack, client.font, pbLine, tx, panelY + 47, COLOR_ACCENT);
        }

        int warnY = panelY + panelH + 8;
        // Practice run warning (FULL mode, existing world — results not counted)
        if (!blocked && ChallengeHud.mode == ChallengeHud.ChallengeMode.FULL && !ChallengeHud.freshWorld) {
            GUI.drawString(poseStack, client.font,
                translate("challengehud.hud.practice"),
                panelX, warnY, COLOR_WARNING);
            warnY += 12;
        }
        // Hardcore block warning (sealed challenge in a non-hardcore world)
        if (blocked) {
            GUI.drawString(poseStack, client.font,
                translate("challengehud.hud.hardcore"),
                panelX, warnY, COLOR_WARNING);
        }
    }

    private static void drawIdleHint(PoseStack poseStack, Minecraft client, int width, int height) {
        String key = START_KEY.getTranslatedKeyMessage().getString();
        String hint = translate("challengehud.hud.idle", key);
        drawCentered(poseStack, client, hint, width / 2, height - 24, COLOR_TEXT);
    }

    /** Transient start toast (top-center), queued by {@link ChallengeHud#showToast}. */
    private static void drawToast(PoseStack poseStack, Minecraft client, int width) {
        String text = ChallengeHud.toastText;
        if (text == null) return;
        if (System.currentTimeMillis() >= ChallengeHud.toastUntilMs) {
            ChallengeHud.toastText = null;
            return;
        }
        int tw = client.font.width(text);
        int x = (width - tw - 16) / 2;
        int y = 16;
        int w = tw + 16;
        int h = 20;
        GUI.fillGradient(poseStack, x, y, x + w, y + h, 0x99000000, 0xCC111111);
        GUI.fill(poseStack, x, y, x + w, y + 1, COLOR_ACCENT);
        GUI.fill(poseStack, x, y + h - 1, x + w, y + h, COLOR_ACCENT);
        GUI.fill(poseStack, x, y, x + 1, y + h, COLOR_ACCENT);
        GUI.fill(poseStack, x + w - 1, y, x + w, y + h, COLOR_ACCENT);
        drawCentered(poseStack, client, text, width / 2, y + 6, COLOR_NAME);
    }

    private static void drawCentered(PoseStack poseStack, Minecraft client, String text, int cx, int y, int color) {
        GUI.drawString(poseStack, client.font, text, cx - client.font.width(text) / 2, y, color);
    }

    /** Localized string via I18n (falls back to the key when the lang file is missing). */
    public static String translate(String key, Object... args) {
        return I18n.get(key, args);
    }

    /**
     * Item display name with a registry-id fallback when the hover name is empty or
     * an untranslated translation key (e.g. missing lang file for a modded item).
     */
    public static String itemName(ItemStack stack, int maxWidth) {
        String name = stack.getHoverName().getString();
        if (name == null || name.isEmpty()
                || name.startsWith("item.") || name.startsWith("block.")
                || name.equals(itemPath(stack.getItem()))) {
            name = itemId(stack.getItem());
        }
        return Minecraft.getInstance().font.plainSubstrByWidth(name, maxWidth);
    }

    public static Path configDir() {
        return Platform.getConfigFolder();
    }

    public static List<Item> items() {
        return Registry.ITEM.stream().collect(Collectors.toList());
    }

    public static String itemId(Item item) {
        return Registry.ITEM.getKey(item).toString();
    }

    public static Item itemById(String id) {
        try {
            return Registry.ITEM.get(new ResourceLocation(id));
        } catch (Exception e) {
            return Items.AIR;
        }
    }

    public static String itemPath(Item item) {
        return Registry.ITEM.getKey(item).getPath();
    }

    public static int countItem(Minecraft client, Item item) {
        return client.player.getInventory().countItem(item);
    }

    public static Component text(String s) {
        return Component.literal(s);
    }
}
