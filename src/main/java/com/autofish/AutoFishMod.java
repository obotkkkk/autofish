package com.autofish;

import com.autofish.mixin.InGameHudAccessor;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import org.lwjgl.glfw.GLFW;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class AutoFishMod implements ClientModInitializer {
    private static KeyBinding toggleKey;
    private static KeyBinding debugKey;
    public static boolean enabled = false;
    public static boolean shouldHoldShift = false;

    public enum State {
        IDLE, PREPARE, BAITING, PICK_BAIT, APPLY_BAIT, RETURN_BAIT, CLOSE_INV, CASTING, WAITING_BITE, FISHING, HARD_RESET_WAIT
    }

    private static State currentState = State.IDLE;
    private static State lastLoggedState = null;
    private static int stateTimer = 0;

    private static String latestActionHud = "";
    private static String lastLoggedHud = null;
    private static long lastBarTime = 0;

    private static long lastActiveFishingTime = 0;
    private static long fishingStartTime = 0;
    private static long lastHardResetTime = 0;
    private static final long MAX_IDLE_TIMEOUT_MS = 60000;
    private static final long MAX_FISHING_DURATION_MS = 90000;
    private static final long HARD_RESET_INTERVAL_MS = 1800000;

    // --- Fishing minigame control tuning ---
    // The minigame is a two-state (bang-bang) control: holding sneak moves the
    // star icon right, releasing it moves the star left. We steer the star
    // toward the NEAREST "target" cell rather than the midpoint of the whole
    // bar, since the white target cells can appear as multiple separate
    // clusters (not one contiguous block).
    private static final char TARGET_CELL_CHAR = '█';
    // Confirmed from live debug logs: this server's fishing minigame uses the
    // "glowing star" emoji specifically (U+1F31F). Other icons like the plain
    // "☀" (U+2600) sun symbol are used elsewhere by the server (e.g. a
    // "+1 điểm" reward/points notification) and must NOT be treated as the
    // fishing star — doing so previously froze the bot permanently in the
    // FISHING state whenever that unrelated message appeared, because it kept
    // refreshing lastBarTime without ever containing real bar data.
    private static final String[] STAR_ICONS = {"🌟"};
    // Hysteresis band (in character-cells) around the target: while the star
    // is within this band we keep the previous shift state instead of
    // flip-flopping every tick, which is what a raw PID with a noisy index
    // signal tends to do.
    private static final double HYSTERESIS = 1.0;

    // --- Bait detection ---
    // Server messages may render with slightly different accents/spacing than
    // expected, so we strip Vietnamese diacritics before matching and check
    // several known phrasings instead of a single exact string.
    private static final String[] BAIT_TRIGGER_PHRASES = {
            "can phai gan moi",
            "gan moi vao can cau",
            "het moi",
            "khong con moi",
            "khong co moi",
            "vui long gan moi",
            "moi da het",
            "ban can gan moi"
    };
    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}");

    // The server renders chat text in a "small caps" stylized font. These are
    // NOT accented Latin letters (which Normalizer/diacritic-stripping would
    // handle) — they are distinct Unicode codepoints from the Phonetic
    // Extensions block (e.g. 'ᴄ' U+1D04 is a different character from 'c' or
    // 'C'). We map them back to plain lowercase ASCII before matching.
    private static final String SMALL_CAPS_SRC =
            "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘꞯʀꜱᴛᴜᴠᴡxʏᴢ";
    private static final String SMALL_CAPS_DST =
            "abcdefghijklmnopqrstuvwxyz";

    private static String smallCapsToAscii(String input) {
        StringBuilder sb = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            int idx = SMALL_CAPS_SRC.indexOf(c);
            sb.append(idx != -1 ? SMALL_CAPS_DST.charAt(idx) : c);
        }
        return sb.toString();
    }

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "Bật/Tắt Auto Fish", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_O, "Auto Fish"
        ));
        debugKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "Bật/Tắt Debug Log", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_I, "Auto Fish"
        ));

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (message != null && !overlay) {
                onChatMessage(message.getString());
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (toggleKey.wasPressed()) {
                enabled = !enabled;
                if (client.player != null) {
                    client.player.sendMessage(Text.of("§e[AutoFish] " + (enabled ? "§aBẬT BOT" : "§cTẮT BOT")), false);
                }
                DebugLogger.log("Toggle enabled=" + enabled);
                if (!enabled) {
                    resetState(client);
                } else {
                    lastHardResetTime = System.currentTimeMillis();
                    lastActiveFishingTime = System.currentTimeMillis();
                    currentState = State.PREPARE;
                }
            }

            if (debugKey.wasPressed()) {
                DebugLogger.toggle(client);
            }

            if (!enabled || client.player == null || client.interactionManager == null) return;

            try {
                handleTick(client);
            } catch (Exception e) {
                // Never let a bug in the bot crash the client tick loop; log it
                // (if debug mode is on) and try to recover to a safe state.
                DebugLogger.logException("handleTick", e);
                resetState(client);
            }
        });
    }

    /** Strip Vietnamese diacritics and lowercase, for robust fuzzy matching of server chat text. */
    private static String normalize(String input) {
        String asciiCaps = smallCapsToAscii(input.toLowerCase());
        String decomposed = Normalizer.normalize(asciiCaps, Normalizer.Form.NFD);
        String noDiacritics = DIACRITICS.matcher(decomposed).replaceAll("");
        return noDiacritics.replace('đ', 'd');
    }

    private void onChatMessage(String text) {
        String normalized = normalize(text);
        boolean isBaitTrigger = false;
        for (String phrase : BAIT_TRIGGER_PHRASES) {
            if (normalized.contains(phrase)) {
                isBaitTrigger = true;
                break;
            }
        }

        if (isBaitTrigger) {
            DebugLogger.log("Chat matched bait-trigger phrase. raw='" + text + "' state=" + currentState);
            if (currentState != State.BAITING && currentState != State.PICK_BAIT
                    && currentState != State.APPLY_BAIT && currentState != State.RETURN_BAIT) {
                MinecraftClient client = MinecraftClient.getInstance();
                // If we were mid-cast/reeling, let go of the rod first so opening
                // the inventory doesn't leave a stuck bobber.
                if (currentState == State.FISHING || currentState == State.WAITING_BITE) {
                    releaseRod(client);
                }
                currentState = State.BAITING;
                stateTimer = 5;
            } else {
                DebugLogger.log("Bait trigger ignored, already in a baiting state.");
            }
        }
    }

    private void parseActionHud(String rawText) {
        if (rawText == null) return;
        String clean = rawText.replaceAll("§[0-9a-fk-orA-FK-OR]", "");

        // Only treat this as the fishing minigame bar if it contains the
        // target-cell character or one of the star icons — these are specific
        // to the fishing bar. A generic check for '[' or '%' also matches
        // unrelated overlay/chat text (e.g. guild broadcast messages like
        // "[Tong Mon] Dat vo chu"), which was falsely hijacking the state
        // machine into a fake FISHING state and wasting the real bite window.
        // Only treat this as the fishing minigame bar if it contains BOTH the
        // target-cell character and a star icon together. Every real
        // fishing-bar frame observed in debug logs has both; requiring only
        // one or the other let unrelated overlay text (chat/guild broadcasts
        // with '[', or reward notifications with a lone star-like symbol)
        // falsely hijack the state machine.
        boolean hasTargetCell = clean.indexOf(TARGET_CELL_CHAR) != -1;
        boolean hasStarIcon = false;
        for (String icon : STAR_ICONS) {
            if (clean.contains(icon)) {
                hasStarIcon = true;
                break;
            }
        }
        if (!hasTargetCell || !hasStarIcon) return;

        latestActionHud = clean;
        lastBarTime = System.currentTimeMillis();
        lastActiveFishingTime = System.currentTimeMillis();
        if (DebugLogger.isEnabled() && !clean.equals(lastLoggedHud)) {
            DebugLogger.log("HUD raw='" + rawText.replace("\u00a7", "&") + "' clean='" + clean + "'");
            lastLoggedHud = clean;
        }
    }

    private void handleTick(MinecraftClient client) {
        long now = System.currentTimeMillis();

        if (client.inGameHud != null) {
            Text overlayText = ((InGameHudAccessor) client.inGameHud).getOverlayMessage();
            if (overlayText != null) {
                parseActionHud(overlayText.getString());
            }
        }

        if (DebugLogger.isEnabled() && currentState != lastLoggedState) {
            DebugLogger.log("STATE " + lastLoggedState + " -> " + currentState + " (timer=" + stateTimer + ")");
            lastLoggedState = currentState;
        }

        if (now - lastHardResetTime > HARD_RESET_INTERVAL_MS && currentState != State.HARD_RESET_WAIT) {
            client.player.sendMessage(Text.of("§d[AutoFish] Hoạt động 30 phút, tạm nghỉ 10s an toàn..."), false);
            DebugLogger.log("Hard reset triggered.");
            releaseRod(client);
            currentState = State.HARD_RESET_WAIT;
            stateTimer = 200;
            lastHardResetTime = now;
            return;
        }

        if (stateTimer > 0) {
            stateTimer--;
            return;
        }

        switch (currentState) {
            case HARD_RESET_WAIT:
                client.player.sendMessage(Text.of("§a[AutoFish] Reset hoàn tất! Khởi động lại..."), false);
                currentState = State.PREPARE;
                break;

            case PREPARE:
                setShift(client, false);
                selectFishingRod(client);
                currentState = State.CASTING;
                stateTimer = 10;
                break;

            case BAITING:
                setShift(client, false);
                if (client.currentScreen == null) {
                    client.setScreen(new InventoryScreen(client.player));
                }
                currentState = State.PICK_BAIT;
                stateTimer = 10;
                break;

            case PICK_BAIT:
                if (client.player.currentScreenHandler != null) {
                    DebugLogger.log("PICK_BAIT screenHandler=" + client.player.currentScreenHandler.getClass().getSimpleName());
                    client.interactionManager.clickSlot(client.player.currentScreenHandler.syncId, 36, 0, SlotActionType.PICKUP, client.player);
                }
                currentState = State.APPLY_BAIT;
                stateTimer = 8;
                break;

            case APPLY_BAIT:
                if (client.player.currentScreenHandler != null) {
                    client.interactionManager.clickSlot(client.player.currentScreenHandler.syncId, 37, 1, SlotActionType.PICKUP, client.player);
                }
                currentState = State.RETURN_BAIT;
                stateTimer = 8;
                break;

            case RETURN_BAIT:
                if (client.player.currentScreenHandler != null) {
                    client.interactionManager.clickSlot(client.player.currentScreenHandler.syncId, 36, 0, SlotActionType.PICKUP, client.player);
                }
                currentState = State.CLOSE_INV;
                stateTimer = 8;
                break;

            case CLOSE_INV:
                if (client.currentScreen != null) {
                    client.player.closeHandledScreen();
                }
                selectFishingRod(client);
                currentState = State.CASTING;
                stateTimer = 15;
                break;

            case CASTING:
                client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);
                currentState = State.WAITING_BITE;
                lastActiveFishingTime = now;
                stateTimer = 20;
                break;

            case WAITING_BITE:
                setShift(client, false);
                if (now - lastActiveFishingTime > MAX_IDLE_TIMEOUT_MS) {
                    client.player.sendMessage(Text.of("§c[AutoFish] Phát hiện kẹt quá 60s! Quăng lại..."), false);
                    DebugLogger.log("Idle timeout in WAITING_BITE, recasting.");
                    releaseRod(client);
                    currentState = State.PREPARE;
                    stateTimer = 20;
                    break;
                }

                if (now - lastBarTime < 1000) {
                    currentState = State.FISHING;
                    fishingStartTime = now;
                }
                break;

            case FISHING:
                if (now - lastBarTime > 1200) {
                    setShift(client, false);
                    currentState = State.PREPARE;
                    stateTimer = 30;
                    break;
                }
                // Hard safety net: even if lastBarTime keeps getting refreshed
                // by something unexpected, never stay in FISHING longer than
                // this. Prevents the bot from ever hanging indefinitely again.
                if (now - fishingStartTime > MAX_FISHING_DURATION_MS) {
                    client.player.sendMessage(Text.of("§c[AutoFish] Câu cá quá lâu (>90s), buộc reset..."), false);
                    DebugLogger.log("FISHING max-duration safety net triggered.");
                    setShift(client, false);
                    releaseRod(client);
                    currentState = State.PREPARE;
                    stateTimer = 30;
                    break;
                }

                handleFishingBarControl(client);
                break;
        }
    }

    /**
     * Two-state (bang-bang) control with hysteresis, steering the star icon
     * toward the nearest target cell instead of the midpoint of the whole bar.
     * This matches the actual minigame mechanic: holding sneak moves the star
     * right, releasing it moves the star left; there is no proportional force,
     * so a PID controller (designed for continuous force output) is the wrong
     * tool and tends to jitter when the raw text indices are noisy.
     */
    private void handleFishingBarControl(MinecraftClient client) {
        int dotX = -1;
        String matchedIcon = null;
        for (String icon : STAR_ICONS) {
            int idx = latestActionHud.indexOf(icon);
            if (idx != -1) {
                dotX = idx;
                matchedIcon = icon;
                break;
            }
        }

        List<Integer> targetCells = new ArrayList<>();
        for (int i = 0; i < latestActionHud.length(); i++) {
            if (latestActionHud.charAt(i) == TARGET_CELL_CHAR) {
                targetCells.add(i);
            }
        }

        if (dotX == -1 || targetCells.isEmpty()) {
            setShift(client, false);
            DebugLogger.log("FISHING no-signal dotX=" + dotX + " targetCells=" + targetCells.size()
                    + " hud='" + latestActionHud + "'");
            return;
        }

        int nearest = targetCells.get(0);
        int bestDist = Math.abs(nearest - dotX);
        for (int cell : targetCells) {
            int dist = Math.abs(cell - dotX);
            if (dist < bestDist) {
                bestDist = dist;
                nearest = cell;
            }
        }

        double error = nearest - dotX; // positive => target is to the right of the star

        boolean hold;
        if (error > HYSTERESIS) {
            hold = true;
        } else if (error < -HYSTERESIS) {
            hold = false;
        } else {
            hold = shouldHoldShift; // inside the deadband: keep previous state, avoid flicker
        }

        setShift(client, hold);

        DebugLogger.log("FISHING icon=" + matchedIcon + " dotX=" + dotX
                + " nearestTarget=" + nearest + " targetCells=" + targetCells.size()
                + " error=" + error + " hold=" + hold + " hud='" + latestActionHud + "'");
    }

    private void setShift(MinecraftClient client, boolean hold) {
        shouldHoldShift = hold;
        if (client.options != null && client.options.sneakKey != null) {
            client.options.sneakKey.setPressed(hold);
        }
        // NOTE: as of MC 1.21.2+, Input/KeyboardInput no longer expose a mutable
        // `sneaking` field — the engine rebuilds an immutable PlayerInput record
        // from sneakKey's pressed state each tick, so setPressed() above is sufficient.
    }

    private void selectFishingRod(MinecraftClient client) {
        if (client.player != null) {
            client.player.getInventory().selectedSlot = 1;
        }
    }

    private void releaseRod(MinecraftClient client) {
        if (client.player != null && client.interactionManager != null) {
            client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);
        }
    }

    private void resetState(MinecraftClient client) {
        currentState = State.IDLE;
        stateTimer = 0;
        setShift(client, false);
        if (client.currentScreen != null && client.player != null) {
            client.player.closeHandledScreen();
        }
    }
}
