package com.autofish;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import org.lwjgl.glfw.GLFW;

public class AutoFishMod implements ClientModInitializer {
    private static KeyBinding toggleKey;
    public static boolean enabled = false;
    public static boolean shouldHoldShift = false;

    // Trạng thái hoạt động
    public enum State {
        IDLE, PREPARE, BAITING, PICK_BAIT, APPLY_BAIT, RETURN_BAIT, CLOSE_INV, CASTING, WAITING_BITE, FISHING, HARD_RESET_WAIT
    }

    private static State currentState = State.IDLE;
    private static int stateTimer = 0;
    
    // Dữ liệu Minigame & PID
    private static final PIDController pid = new PIDController(0.8, 0.0, 0.2);
    private static String latestActionHud = "";
    private static long lastBarTime = 0;

    // Thời gian an toàn & Timeout
    private static long lastActiveFishingTime = 0;
    private static long lastHardResetTime = 0;
    private static final long MAX_IDLE_TIMEOUT_MS = 60000; // 60 giây kẹt
    private static final long HARD_RESET_INTERVAL_MS = 1800000; // 30 phút reset

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "Bật/Tắt Auto Fish", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_O, "Auto Fish"
        ));

        // Lắng nghe cả Chat lẫn Actionbar từ Server
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (message != null) {
                String text = message.getString();
                if (overlay) {
                    parseActionHud(text);
                } else {
                    onChatMessage(text);
                }
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
        // Kiểm tra thông báo hết mồi từ server
        if (clean.contains("cần phải gắn mồi") || clean.contains("can phai gan moi")) {
            if (currentState != State.BAITING && currentState != State.PICK_BAIT) {
                currentState = State.BAITING;
                stateTimer = 5;
            }
        }
    }

    private void parseActionHud(String rawText) {
        String clean = rawText.replaceAll("§[0-9a-fk-orA-FK-OR]", "");
        if (clean.contains("█") || clean.contains("☀️") || clean.contains("☀") || clean.contains("%") || clean.contains("[")) {
            latestActionHud = clean;
            lastBarTime = System.currentTimeMillis();
            lastActiveFishingTime = System.currentTimeMillis();
        }
    }

    private void handleTick(MinecraftClient client) {
        long now = System.currentTimeMillis();

        // 1. Kiểm tra Hard Reset mỗi 30 phút
        if (now - lastHardResetTime > HARD_RESET_INTERVAL_MS && currentState != State.HARD_RESET_WAIT) {
            client.player.sendMessage(Text.of("§d[AutoFish] Hoạt động 30 phút, tạm nghỉ 10s an toàn..."), false);
            releaseRod(client);
            currentState = State.HARD_RESET_WAIT;
            stateTimer = 200; // 10 giây
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
                selectFishingRod(client);
                currentState = State.CASTING;
                stateTimer = 10;
                break;

            case BAITING:
                if (client.currentScreen == null) {
                    client.setScreen(new InventoryScreen(client.player));
                }
                currentState = State.PICK_BAIT;
                stateTimer = 10;
                break;

            case PICK_BAIT:
                if (client.player.currentScreenHandler != null) {
                    // Cầm mồi ở Slot 1 (36)
                    client.interactionManager.clickSlot(client.player.currentScreenHandler.syncId, 36, 0, SlotActionType.PICKUP, client.player);
                }
                currentState = State.APPLY_BAIT;
                stateTimer = 8;
                break;

            case APPLY_BAIT:
                if (client.player.currentScreenHandler != null) {
                    // Chuột phải đè lên cần ở Slot 2 (37)
                    client.interactionManager.clickSlot(client.player.currentScreenHandler.syncId, 37, 1, SlotActionType.PICKUP, client.player);
                }
                currentState = State.RETURN_BAIT;
                stateTimer = 8;
                break;

            case RETURN_BAIT:
                if (client.player.currentScreenHandler != null) {
                    // Đặt lượng mồi dư về lại Slot 1 (36)
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
                // Nếu quá 60s không thấy minigame -> Quăng lại cần (Chống kẹt)
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
                    shouldHoldShift = false;
                    pid.reset();
                    currentState = State.PREPARE;
                    stateTimer = 30; // Chờ 1.5s trước khi lặp lại
                    break;
                }

                handlePIDMovement();
                break;
        }
    }

    private void handlePIDMovement() {
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
            
            // Tính toán lực điều khiển từ bộ PID
            double pwr = pid.calculate(barX, dotX);

            // Nếu Ngôi sao lệch trái so với tâm ô xanh -> Ép Shift
            shouldHoldShift = (pwr > 0);
        } else {
            shouldHoldShift = false;
        }
    }

    private void selectFishingRod(MinecraftClient client) {
        if (client.player != null) {
            client.player.getInventory().selectedSlot = 1; // Slot 2 Hotbar
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
        shouldHoldShift = false;
        pid.reset();
        if (client.currentScreen != null && client.player != null) {
            client.player.closeHandledScreen();
        }
    }
}
