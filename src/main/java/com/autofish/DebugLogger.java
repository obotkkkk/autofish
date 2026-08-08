package com.autofish;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Simple file-based debug logger.
 * Toggle in-game with the "Bat/Tat Debug Log" keybind (default: L).
 * Logs are written to <game_dir>/autofish-logs/session-yyyyMMdd-HHmmss.log
 */
public class DebugLogger {
    private static BufferedWriter writer;
    private static boolean enabled = false;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final DateTimeFormatter FILE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    public static boolean isEnabled() {
        return enabled;
    }

    public static void toggle(MinecraftClient client) {
        enabled = !enabled;
        if (enabled) {
            start();
        } else {
            stop();
        }
        if (client.player != null) {
            client.player.sendMessage(
                    Text.of("§b[AutoFish] Debug log: " + (enabled ? "§aBẬT (đang ghi file)" : "§cTẮT")),
                    false
            );
        }
    }

    private static void start() {
        try {
            Path dir = FabricLoader.getInstance().getGameDir().resolve("autofish-logs");
            Files.createDirectories(dir);
            String fileName = "session-" + FILE_FMT.format(LocalDateTime.now()) + ".log";
            Path file = dir.resolve(fileName);
            writer = Files.newBufferedWriter(file);
            log("=== AutoFish debug session started (file: " + file + ") ===");
        } catch (IOException e) {
            enabled = false;
            writer = null;
        }
    }

    private static void stop() {
        if (writer != null) {
            log("=== AutoFish debug session ended ===");
            try {
                writer.flush();
                writer.close();
            } catch (IOException ignored) {
            }
            writer = null;
        }
    }

    public static void log(String msg) {
        if (!enabled || writer == null) return;
        try {
            writer.write("[" + TIME_FMT.format(LocalDateTime.now()) + "] " + msg);
            writer.newLine();
            writer.flush();
        } catch (IOException ignored) {
            // If writing fails mid-session, silently disable to avoid spamming exceptions every tick.
            enabled = false;
        }
    }

    public static void logException(String context, Throwable t) {
        if (!enabled || writer == null) return;
        log("EXCEPTION in " + context + ": " + t);
        for (StackTraceElement el : t.getStackTrace()) {
            log("    at " + el);
        }
    }
}
