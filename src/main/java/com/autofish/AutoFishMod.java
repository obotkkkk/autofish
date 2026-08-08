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

public class AutoFishMod implements ClientModInitializer {
    private static KeyBinding toggleKey;
    public static boolean enabled = false;
    public static boolean shouldHoldShift = false;

    public enum State {
        IDLE, PREPARE, BAITING, PICK_BAIT, APPLY_BAIT, RETURN_BAIT, CLOSE_INV, CASTING, WAITING_BITE, FISHING, HARD_RESET_WAIT
    }

    private static State currentState = State.IDLE;
    private static int stateTimer = 0;
    
    private static final PIDController pid = new PIDController(0.8, 0.0, 0.2);
    private static String latestActionHud = "";
    private static long lastBarTime = 0;

    private static long lastActiveFishingTime = 0;
    private static long lastHardResetTime = 0;
    private static final long MAX_IDLE_TIMEOUT_MS = 60000;
    private static final long HARD_RESET_INTERVAL_MS = 1800000;

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "Bật/Tắt Auto Fish", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_O, "Auto Fish"
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
                if (!enabled) {
                    resetState(client);
                } else {
                    lastHardResetTime = System.currentTimeMillis();
                    lastActiveFishingTime = System.currentTimeMillis();
                    currentState = State.PREPARE;
                }
            }

            if (!enabled || client.player == null || client.interactionManager == null) return;

            handleTick(client);
        });
    }

    private void onChatMessage(String text) {
        String clean = text.toLowerCase();
        if (clean.contains("cần phải gắn mồi") || clean.contains("can phai gan moi")) {
            if (currentState != State.BAITING && currentState != State.PICK_BAIT) {
                currentState = State.BAITING;
                stateTimer = 5;
            }
        }
    }

    private void parseActionHud(String rawText) {
        if (rawText == null) return;
        String clean = rawText.replaceAll("§[0-9a-fk-orA-FK-OR]", "");
        if (clean.contains("█") || clean.contains("☀️") || clean.contains("☀") || clean.contains("%") || clean.contains("[")) {
            latestActionHud = clean;
            lastBarTime = System.currentTimeMillis();
            lastActiveFishingTime = System.currentTimeMillis();
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

        if (now - lastHardResetTime > HARD_RESET_INTERVAL_MS && currentState != State.HARD_RESET_WAIT) {
            client.player.sendMessage(Text.of("§d[AutoFish] Hoạt động 30 phút, tạm nghỉ 10s an toàn..."), false);
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
                    releaseRod(client);
                    currentState = State.PREPARE;
                    stateTimer = 20;
                    break;
                }

                if (now - lastBarTime < 1000) {
                    currentState = State.FISHING;
                }
                break;

            case FISHING:
                if (now - lastBarTime > 1200) {
                    setShift(client, false);
                    pid.reset();
                    currentState = State.PREPARE;
                    stateTimer = 30;
                    break;
                }

                handlePIDMovement(client);
                break;
        }
    }

    private void handlePIDMovement(MinecraftClient client) {
        int dotX = -1;
        for (String icon : new String[]{"☀️", "☀", "⭐", "★", "☆"}) {
            int idx = latestActionHud.indexOf(icon);
            if (idx != -1) {
                dotX = idx;
                break;
            }
        }

        int firstBlock = latestActionHud.indexOf("█");
        int lastBlock = latestActionHud.lastIndexOf("█");

        if (dotX != -1 && firstBlock != -1 && lastBlock != -1) {
            double barX = (firstBlock + lastBlock) / 2.0;
            double pwr = pid.calculate(barX, dotX);

            boolean hold = (pwr > 0);
            setShift(client, hold);
        } else {
            setShift(client, false);
        }
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
        pid.reset();
        if (client.currentScreen != null && client.player != null) {
            client.player.closeHandledScreen();
        }
    }
}
