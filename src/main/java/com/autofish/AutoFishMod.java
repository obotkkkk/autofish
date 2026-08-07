package com.autofish;

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

    private enum State {
        IDLE,
        OPEN_INV,
        PICK_BAIT,
        APPLY_BAIT,
        RETURN_BAIT,
        CLOSE_INV,
        SELECT_ROD_SLOT,
        CAST_ROD,
        WAITING_FOR_FISH,
        PLAYING_MINIGAME
    }

    private static State currentState = State.IDLE;
    private static int stateTimer = 0;
    private static String latestText = "";
    private static long lastMinigameTextTime = 0;
    private static int debugTickCounter = 0;

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "Bật/Tắt Auto Fish",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_O,
                "Auto Fish"
        ));

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (message != null) {
                processIncomingText(message.getString());
            }
        });

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

    public static void processIncomingText(String text) {
        if (text != null && !text.isEmpty()) {
            String clean = text.replaceAll("§[0-9a-fk-orA-FK-OR]", "");
            if (clean.contains("█") || clean.contains("☀️") || clean.contains("☀") || clean.contains("%")) {
                latestText = text;
                lastMinigameTextTime = System.currentTimeMillis();
            }
        }
    }

    private void handleTick(MinecraftClient client) {
        if (stateTimer > 0) {
            stateTimer--;
            return;
        }

        switch (currentState) {
            case IDLE:
                currentState = State.OPEN_INV;
                stateTimer = 10;
                break;

            case OPEN_INV:
                if (client.currentScreen == null) {
                    client.setScreen(new InventoryScreen(client.player));
                }
                currentState = State.PICK_BAIT;
                stateTimer = 8;
                break;

            case PICK_BAIT:
                if (client.player.currentScreenHandler != null) {
                    client.interactionManager.clickSlot(
                            client.player.currentScreenHandler.syncId,
                            36, 0, SlotActionType.PICKUP, client.player
                    );
                }
                currentState = State.APPLY_BAIT;
                stateTimer = 6;
                break;

            case APPLY_BAIT:
                if (client.player.currentScreenHandler != null) {
                    client.interactionManager.clickSlot(
                            client.player.currentScreenHandler.syncId,
                            37, 1, SlotActionType.PICKUP, client.player
                    );
                }
                currentState = State.RETURN_BAIT;
                stateTimer = 6;
                break;

            case RETURN_BAIT:
                if (client.player.currentScreenHandler != null) {
                    client.interactionManager.clickSlot(
                            client.player.currentScreenHandler.syncId,
                            36, 0, SlotActionType.PICKUP, client.player
                    );
                }
                currentState = State.CLOSE_INV;
                stateTimer = 6;
                break;

            case CLOSE_INV:
                if (client.currentScreen != null) {
                    client.player.closeHandledScreen();
                }
                currentState = State.SELECT_ROD_SLOT;
                stateTimer = 8;
                break;

            case SELECT_ROD_SLOT:
                client.player.getInventory().selectedSlot = 1;
                currentState = State.CAST_ROD;
                stateTimer = 10;
                break;

            case CAST_ROD:
                client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);
                if (client.player != null) {
                    client.player.sendMessage(Text.of("§b[AutoFish] Đã quăng cần! Đang chờ cá..."), false);
                }
                currentState = State.WAITING_FOR_FISH;
                stateTimer = 15;
                break;

            case WAITING_FOR_FISH:
                if (isMinigameActive()) {
                    if (client.player != null) {
                        client.player.sendMessage(Text.of("§a[AutoFish] Phát hiện Minigame -> Đang tự động Shift!"), false);
                    }
                    currentState = State.PLAYING_MINIGAME;
                }
                break;

            case PLAYING_MINIGAME:
                if (System.currentTimeMillis() - lastMinigameTextTime > 1200) {
                    releaseShift(client);
                    if (client.player != null) {
                        client.player.sendMessage(Text.of("§e[AutoFish] Hoàn thành! Lặp lại quy trình..."), false);
                    }
                    currentState = State.IDLE;
                    stateTimer = 20;
                    break;
                }

                controlShiftForMinigame(client, latestText);
                break;
        }
    }

    private boolean isMinigameActive() {
        if (System.currentTimeMillis() - lastMinigameTextTime > 1000) return false;
        String cleanText = latestText.replaceAll("§[0-9a-fk-orA-FK-OR]", "");
        return cleanText.contains("█") || cleanText.contains("☀️") || cleanText.contains("☀") || cleanText.contains("%");
    }

private void controlShiftForMinigame(MinecraftClient client, String rawText) {
        String cleanText = rawText.replaceAll("§[0-9a-fk-orA-FK-OR]", "");

        // 1. Tìm vị trí Ngôi sao
        int starIndex = -1;
        String[] starIcons = {"☀️", "☀", "⭐", "★", "☆"};
        for (String icon : starIcons) {
            int idx = cleanText.indexOf(icon);
            if (idx != -1) {
                starIndex = idx;
                break;
            }
        }

        // 2. Tìm vùng thanh ngang (sử dụng dấu gạch ngang '-' hoặc khối '█')
        // Dựa vào ảnh, thanh minigame của bạn có dạng: [--------☀️-----------]
        int firstChar = cleanText.indexOf("-");
        int lastChar = cleanText.lastIndexOf("-");

        if (starIndex != -1 && firstChar != -1 && lastChar != -1) {
            double targetCenter = (firstChar + lastChar) / 2.0;

            // --- LOGIC MỚI: ĐẢO NGƯỢC ---
            // Nếu ngôi sao nằm BÊN TRÁI tâm thanh -> Giữ Shift để nó trôi sang PHẢI
            if (starIndex < targetCenter) {
                client.options.sneakKey.setPressed(true);
            } 
            // Nếu ngôi sao nằm BÊN PHẢI tâm thanh -> Thả Shift để nó trôi sang TRÁI
            else {
                client.options.sneakKey.setPressed(false);
            }
            
            // Ép buộc Minecraft cập nhật trạng thái phím ngay lập tức
            client.options.sneakKey.updatePressedStates();

            // Debug
            debugTickCounter++;
            if (debugTickCounter % 20 == 0 && client.player != null) {
                client.player.sendMessage(Text.of("§7[Debug] Star: " + starIndex + " | Target: " + (int)targetCenter), false);
            }
        }
    }

    private void releaseShift(MinecraftClient client) {
        if (client.options != null && client.options.sneakKey != null) {
            client.options.sneakKey.setPressed(false);
        }
    }

    private void resetState(MinecraftClient client) {
        currentState = State.IDLE;
        stateTimer = 0;
        releaseShift(client);
    }
}
