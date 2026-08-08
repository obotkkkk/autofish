package com.autofish;

import com.autofish.mixin.InGameHudAccessor;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
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
    // Minecraft keeps rendering the last action-bar Text object client-side
    // for a few seconds even after the server stops sending updates. Without
    // tracking object identity, re-reading that same lingering text every
    // tick made a stale 99-100% reading from the PREVIOUS catch look like
    // fresh data for the NEXT episode, causing an instant fake catch the
    // moment FISHING started. We only process genuinely new packets now.
    private static Text lastProcessedOverlayText = null;

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
    private static final Pattern PERCENT_PATTERN = Pattern.compile("(\\d{1,3})%");
    // Edge-stall anti-freeze: if the star's index doesn't move for this many
    // consecutive ticks while we're actively correcting a real error, we
    // assume it's pinned against the bar's edge (can't render further) and
    // invert the held direction to break free. Confirmed via debug logs: the
    // star froze at the right wall for 3+ seconds straight while % steadily
    // dropped, because holding shift kept pushing it further right with no
    // visible effect instead of correcting back toward the target.
    private static int lastDotX = Integer.MIN_VALUE;
    private static int stallTicks = 0;
    private static final int STALL_TICKS_THRESHOLD = 6;

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
    // If a bait-trigger message fires again this soon after we already
    // finished a rebait cycle, the rebait clearly didn't add any bait (there
    // was none left to move) -- rather than looping the same failing click
    // sequence forever, we stop the bot and tell the player to restock.
    private static long lastBaitCycleCompletedAt = -1;
    private static final long BAIT_FAILURE_WINDOW_MS = 10000;
    private static boolean justRebaited = false;

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

            MinecraftClient client = MinecraftClient.getInstance();
            if (lastBaitCycleCompletedAt != -1 && System.currentTimeMillis() - lastBaitCycleCompletedAt < BAIT_FAILURE_WINDOW_MS) {
                DebugLogger.log("Bait trigger fired again right after a completed rebait cycle -- treating as truly out of bait, stopping bot.");
                stopDueToOutOfBait(client);
                return;
            }

            if (currentState != State.BAITING && currentState != State.PICK_BAIT
                    && currentState != State.APPLY_BAIT && currentState != State.RETURN_BAIT) {
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

    private void stopDueToOutOfBait(MinecraftClient client) {
        enabled = false;
        resetState(client);
        lastBaitCycleCompletedAt = -1;
        if (client.player != null) {
            client.player.sendMessage(Text.of("§c[AutoFish] Hết mồi thật rồi! Đã TẮT bot, bạn bổ sung mồi rồi bật lại nhé."), false);
        }
        DebugLogger.log("Bot auto-stopped: out of bait.");
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
            if (overlayText != null && overlayText != lastProcessedOverlayText) {
                lastProcessedOverlayText = overlayText;
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
                if (!(client.player.getInventory().getStack(client.player.getInventory().selectedSlot).getItem() instanceof net.minecraft.item.FishingRodItem)) {
                    // selectFishingRod() searched the whole hotbar and still
                    // couldn't find a rod anywhere -- it's genuinely gone
                    // (broken/dropped/moved out of the hotbar entirely).
                    client.player.sendMessage(Text.of("§c[AutoFish] Không tìm thấy cần câu trong hotbar! Có thể cần đã gãy. Đã TẮT bot."), false);
                    DebugLogger.log("PREPARE: no fishing rod found anywhere in hotbar, stopping.");
                    enabled = false;
                    resetState(client);
                    break;
                }
                currentState = State.CASTING;
                stateTimer = 10;
                break;

            case BAITING:
                setShift(client, false);
                // No screen is opened here on purpose: client.player.playerScreenHandler
                // (syncId 0) is always present regardless of what's shown on
                // screen, so we can send the exact same click packets the
                // server expects without ever displaying the inventory GUI.
                // This lets the player freely open the pause menu, chat, etc.
                // while the bot rebaits silently in the background.
                currentState = State.PICK_BAIT;
                stateTimer = 10;
                break;

            case PICK_BAIT:
                client.interactionManager.clickSlot(client.player.playerScreenHandler.syncId, 36, 0, SlotActionType.PICKUP, client.player);
                currentState = State.APPLY_BAIT;
                stateTimer = 8;
                break;

            case APPLY_BAIT:
                client.interactionManager.clickSlot(client.player.playerScreenHandler.syncId, 37, 1, SlotActionType.PICKUP, client.player);
                currentState = State.RETURN_BAIT;
                stateTimer = 8;
                break;

            case RETURN_BAIT:
                client.interactionManager.clickSlot(client.player.playerScreenHandler.syncId, 36, 0, SlotActionType.PICKUP, client.player);
                currentState = State.CLOSE_INV;
                stateTimer = 8;
                break;

            case CLOSE_INV:
                // Nothing to close -- we never opened a screen. As a safety
                // net, make sure the click sequence didn't leave an item
                // stranded on the cursor (with no GUI visible, the player
                // can't see or fix this themselves) -- deposit it into the
                // first empty inventory slot if so.
                ensureCursorEmpty(client);
                justRebaited = true;
                selectFishingRod(client);
                currentState = State.CASTING;
                stateTimer = 15;
                break;

            case CASTING:
                client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);
                currentState = State.WAITING_BITE;
                lastActiveFishingTime = now;
                stateTimer = 20;
                // Critical: clear any leftover bar text/percent from the
                // previous catch. Without this, a stale 99-100% reading
                // carried over from the last episode caused the NEXT episode
                // to be instantly (falsely) "caught" the moment FISHING
                // started, skipping the real minigame entirely -- confirmed
                // in logs where "FISHING reached 100%, reeling in." fired in
                // the same millisecond as "WAITING_BITE -> FISHING".
                latestActionHud = "";
                lastLoggedHud = null;
                if (justRebaited) {
                    lastBaitCycleCompletedAt = now;
                    justRebaited = false;
                }
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
                    lastDotX = Integer.MIN_VALUE;
                    stallTicks = 0;
                    // A real bite happening confirms the rod had bait -- clear
                    // the failure-tracking window so an unrelated bait-trigger
                    // message far later doesn't get misread as a repeat failure.
                    lastBaitCycleCompletedAt = -1;
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

                // Reaching 100% does NOT make the bar disappear by itself on
                // this server -- confirmed by debug logs showing the bot
                // stuck holding shift at 100% for 15+ seconds with no
                // progress. We must actively right-click to reel the catch
                // in once the bar is effectively full.
                int percent = parsePercent(latestActionHud);
                if (percent >= 99) {
                    DebugLogger.log("FISHING reached " + percent + "%, reeling in.");
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

    /** Extracts the trailing "NN%" percentage from the fishing bar HUD text, or -1 if not found. */
    private static int parsePercent(String hud) {
        if (hud == null) return -1;
        java.util.regex.Matcher m = PERCENT_PATTERN.matcher(hud);
        int last = -1;
        while (m.find()) {
            try {
                last = Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return last;
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

        // Edge-stall detection: only makes sense when the star is actually
        // pinned against the visible left/right wall of the bar -- NOT just
        // "hasn't moved this tick" anywhere in the middle, which happens
        // constantly and harmlessly whenever our tick rate outpaces the HUD's
        // own refresh rate. Triggering the kick outside of a real edge was
        // launching the star into wild overshoot oscillations across the
        // whole bar (confirmed in logs: error jumping to ±20 right after a
        // false kick), which made later fishing attempts much less stable
        // than the first.
        int bracketStart = latestActionHud.lastIndexOf('[');
        int bracketEnd = latestActionHud.indexOf(']', bracketStart);
        boolean nearLeftEdge = bracketStart != -1 && dotX <= bracketStart + 2;
        boolean nearRightEdge = bracketEnd != -1 && dotX >= bracketEnd - 3;
        boolean atEdge = nearLeftEdge || nearRightEdge;

        if (atEdge && dotX == lastDotX && Math.abs(error) > HYSTERESIS) {
            stallTicks++;
        } else {
            stallTicks = 0;
        }
        lastDotX = dotX;

        boolean kicked = false;
        if (atEdge && stallTicks > STALL_TICKS_THRESHOLD) {
            hold = !hold;
            kicked = true;
            stallTicks = 0;
        }

        setShift(client, hold);

        DebugLogger.log("FISHING icon=" + matchedIcon + " dotX=" + dotX
                + " nearestTarget=" + nearest + " targetCells=" + targetCells.size()
                + " error=" + error + " hold=" + hold + (kicked ? " KICK(edge-stall)" : "")
                + " hud='" + latestActionHud + "'");
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

    /**
     * Finds and selects whichever hotbar slot currently holds the fishing
     * rod, instead of assuming a fixed index. The bait-application click
     * sequence (PICK_BAIT/APPLY_BAIT/RETURN_BAIT) can end up shuffling items
     * between hotbar slots depending on exactly how the server's custom bait
     * GUI logic handles each click -- confirmed in logs where the rod
     * disappeared from the assumed slot 1 right after a single rebait cycle.
     * Searching dynamically means the bot keeps finding the rod wherever it
     * actually lands instead of "losing" it.
     */
    /**
     * Safety net for the no-GUI bait-click sequence: if an item ends up stuck
     * on the cursor (e.g. a click swapped two different items instead of
     * merging/placing as expected), the player has no visible inventory to
     * fix it themselves. Find the first empty slot and deposit it there.
     */
    private void ensureCursorEmpty(MinecraftClient client) {
        if (client.player == null || client.player.playerScreenHandler == null) return;
        net.minecraft.item.ItemStack cursor = client.player.playerScreenHandler.getCursorStack();
        if (cursor == null || cursor.isEmpty()) return;

        DebugLogger.log("Cursor not empty after bait sequence (" + cursor + "), depositing into first empty slot.");
        net.minecraft.entity.player.PlayerInventory inv = client.player.getInventory();
        // Network slot indices: 9-35 = main inventory (maps 1:1 to
        // PlayerInventory indices 9-35), 36-44 = hotbar (maps to
        // PlayerInventory indices 0-8).
        for (int networkSlot = 9; networkSlot <= 44; networkSlot++) {
            int invIndex = networkSlot <= 35 ? networkSlot : networkSlot - 36;
            net.minecraft.item.ItemStack stack = inv.getStack(invIndex);
            if (stack.isEmpty()) {
                client.interactionManager.clickSlot(client.player.playerScreenHandler.syncId, networkSlot, 0, SlotActionType.PICKUP, client.player);
                return;
            }
        }
    }
        if (client.player == null) return;
        net.minecraft.entity.player.PlayerInventory inv = client.player.getInventory();
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).getItem() instanceof net.minecraft.item.FishingRodItem) {
                inv.selectedSlot = i;
                return;
            }
        }
        // Not found anywhere in the hotbar -- fall back to the historical
        // default slot; the PREPARE state's rod-presence check will catch
        // and report a truly missing rod right after this.
        inv.selectedSlot = 1;
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
