package com.autofish;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

public class AutoFishMod implements ClientModInitializer {
    private static KeyBinding toggleKey;
    public static boolean enabled = false;

    private enum State {
        IDLE,
        OPEN_INV,
        PICK_BAIT,
        APPLY_BAIT,
        RETURN_BAIT,
        CLOSE_INV,
        CAST_ROD,
        WAITING_FOR_FISH,
        PLAYING_MINIGAME
    }

    private static State currentState = State.IDLE;
    private static int stateTimer = 0;
    private static String latestActionBar = "";
    private static long lastMinigameTextTime = 0;

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "Bật/Tắt Auto Fish",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_O,
                "Auto Fish"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (toggleKey.wasPressed()) {
                enabled = !enabled;
                if (client.player != null) {
                    client.player.sendMessage(Text.of("§e[AutoFish] " + (enabled ? "§aĐÃ BẬT" : "§cĐÃ TẮT")), false);
                }
                if (!enabled) {
                    resetState(client);
                }
            }

            if (!enabled || client.player == null || client.interactionManager == null) return;

            handleTick(client);
        });
    }

    public static void processActionBarText(String text) {
        latestActionBar = text;
        lastMinigameTextTime = System.currentTimeMillis();
    }

    private void handleTick(MinecraftClient client) {
        if (stateTimer > 0) {
            stateTimer--;
            return;
        }

        switch (currentState) {
            case IDLE:
                currentState = State.OPEN_INV;
                stateTimer = 5;
                break;

            case OPEN_INV:
                if (client.currentScreen == null) {
                    client.setScreen(new net.minecraft.client.gui.screen.ingame.InventoryScreen(client.player));
                }
                currentState = State.PICK_BAIT;
                stateTimer = 5;
                break;

            case PICK_BAIT:
                if (client.player.currentScreenHandler != null) {
                    client.interactionManager.clickSlot(
                            client.player.currentScreenHandler.syncId,
                            36, 0, SlotActionType.PICKUP, client.player
                    );
                }
                currentState = State.APPLY_BAIT;
                stateTimer = 5;
                break;

            case APPLY_BAIT:
                if (client.player.currentScreenHandler != null) {
                    client.interactionManager.clickSlot(
                            client.player.currentScreenHandler.syncId,
                            37, 1, SlotActionType.PICKUP, client.player
                    );
                }
                currentState = State.RETURN_BAIT;
                stateTimer = 5;
                break;

            case RETURN_BAIT:
                if (client.player.currentScreenHandler != null) {
                    client.interactionManager.clickSlot(
                            client.player.currentScreenHandler.syncId,
                            36, 0, SlotActionType.PICKUP, client.player
                    );
                }
                currentState = State.CLOSE_INV;
                stateTimer = 5;
                break;

            case CLOSE_INV:
                if (client.currentScreen != null) {
                    client.player.closeHandledScreen();
                }
                client.player.getInventory().selectedSlot = 1;
                currentState = State.CAST_ROD;
                stateTimer = 10;
                break;

            case CAST_ROD:
                client.interactionManager.interactItem(client.player, client.player.getActiveHand());
                currentState = State.WAITING_FOR_FISH;
                stateTimer = 20;
                break;

            case WAITING_FOR_FISH:
                if (isMinigameActive()) {
                    currentState = State.PLAYING_MINIGAME;
                }
                break;

            case PLAYING_MINIGAME:
                if (System.currentTimeMillis() - lastMinigameTextTime > 1500) {
                    releaseShift(client);
                    currentState = State.IDLE;
                    stateTimer = 20;
                    break;
                }

                controlShiftForMinigame(client, latestActionBar);
                break;
        }
    }

    private boolean isMinigameActive() {
        return (System.currentTimeMillis() - lastMinigameTextTime < 1000) &&
                (latestActionBar.contains("☀️") || latestActionBar.contains("█") || latestActionBar.contains("["));
    }

    private void controlShiftForMinigame(MinecraftClient client, String text) {
        int starIndex = text.indexOf("☀️");
        if (starIndex == -1) {
            starIndex = text.indexOf("☀");
        }

        if (starIndex == -1) return;

        int firstBlockIndex = text.indexOf("█");
        int lastBlockIndex = text.lastIndexOf("█");

        if (firstBlockIndex == -1) return;

        double targetCenter = (firstBlockIndex + lastBlockIndex) / 2.0;

        if (starIndex < targetCenter) {
            client.options.sneakKey.setPressed(true);
        } else {
            client.options.sneakKey.setPressed(false);
        }
    }

    private void releaseShift(MinecraftClient client) {
        client.options.sneakKey.setPressed(false);
    }

    private void resetState(MinecraftClient client) {
        currentState = State.IDLE;
        stateTimer = 0;
        releaseShift(client);
    }
}
