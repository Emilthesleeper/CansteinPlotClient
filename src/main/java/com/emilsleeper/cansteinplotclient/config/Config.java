package com.emilsleeper.cansteinplotclient.config;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public class Config {
    public static String serverAddress = "127.0.0.1";
    public static String password = "password";
    public static String worldName = ""; // optional: restrict taskQueue processing to this world identifier
    public static String periodicCommand = ""; // command to run every 10 seconds when not in the configured world

    private static final Path CONFIG_FILE = Paths.get(System.getProperty("user.home"), ".cansteinplotclient", "config.json");

    static {
        loadConfig();
    }

    public static void setServerAddress(String address) {
        serverAddress = address;
    }

    public static void setPassword(String pass) {
        password = pass;
    }

    public static String getServerAddress() {
        return serverAddress;
    }

    public static String getPassword() {
        return password;
    }

    public static int getServerPort() {
        // Extract port from serverAddress if it contains ":"
        if (serverAddress != null && serverAddress.contains(":")) {
            try {
                String[] parts = serverAddress.split(":");
                return Integer.parseInt(parts[parts.length - 1]);
            } catch (NumberFormatException e) {
                System.err.println("[CansteinPlotClient] Invalid port in address: " + serverAddress);
            }
        }
        // Default to 8080 if no port found
        return 8080;
    }

    public static void setWorldName(String wn) { worldName = wn == null ? "" : wn; }
    public static String getWorldName() { return worldName; }

    public static void setPeriodicCommand(String cmd) { periodicCommand = cmd == null ? "" : cmd; }
    public static String getPeriodicCommand() { return periodicCommand; }

    /**
     * Save all config values to a JSON file
     */
    public static void saveConfig() {
        try {
            // Ensure directory exists
            Files.createDirectories(CONFIG_FILE.getParent());

            // Build JSON manually to avoid external dependencies
            String json = "{\n" +
                    "  \"serverAddress\": \"" + escapeJsonString(serverAddress) + "\",\n" +
                    "  \"password\": \"" + escapeJsonString(password) + "\",\n" +
                    "  \"worldName\": \"" + escapeJsonString(worldName) + "\",\n" +
                    "  \"periodicCommand\": \"" + escapeJsonString(periodicCommand) + "\"\n" +
                    "}\n";

            Files.writeString(CONFIG_FILE, json, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);

            System.out.println("[CansteinPlotClient] Config saved to: " + CONFIG_FILE);
        } catch (IOException e) {
            System.err.println("[CansteinPlotClient] Failed to save config: " + e.getMessage());
        }
    }

    /**
     * Load config values from JSON file
     */
    public static void loadConfig() {
        if (!Files.exists(CONFIG_FILE)) {
            System.out.println("[CansteinPlotClient] Config file not found at: " + CONFIG_FILE);
            return;
        }

        try {
            String content = Files.readString(CONFIG_FILE, StandardCharsets.UTF_8);

            // Simple JSON parsing (avoiding external dependencies)
            serverAddress = extractJsonString(content, "serverAddress", serverAddress);
            password = extractJsonString(content, "password", password);
            worldName = extractJsonString(content, "worldName", worldName);
            periodicCommand = extractJsonString(content, "periodicCommand", periodicCommand);

            System.out.println("[CansteinPlotClient] Config loaded from: " + CONFIG_FILE);
        } catch (IOException e) {
            System.err.println("[CansteinPlotClient] Failed to load config: " + e.getMessage());
        }
    }

    /**
     * Extract a JSON string value by key
     */
    private static String extractJsonString(String json, String key, String defaultValue) {
        String searchKey = "\"" + key + "\"";
        int keyIndex = json.indexOf(searchKey);
        if (keyIndex == -1) return defaultValue;

        int colonIndex = json.indexOf(":", keyIndex);
        if (colonIndex == -1) return defaultValue;

        int quoteStart = json.indexOf("\"", colonIndex);
        if (quoteStart == -1) return defaultValue;

        int quoteEnd = quoteStart + 1;
        while (quoteEnd < json.length()) {
            if (json.charAt(quoteEnd) == '"' && json.charAt(quoteEnd - 1) != '\\') {
                break;
            }
            quoteEnd++;
        }

        if (quoteEnd >= json.length()) return defaultValue;

        String value = json.substring(quoteStart + 1, quoteEnd);
        // Unescape JSON string
        value = unescapeJsonString(value);
        return value;
    }

    /**
     * Escape special characters for JSON
     */
    private static String escapeJsonString(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * Unescape JSON string
     */
    private static String unescapeJsonString(String s) {
        if (s == null) return "";
        return s.replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\\", "\\");
    }
}

