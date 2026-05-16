package com.emilsleeper.cansteinplotclient.plot;

import com.emilsleeper.cansteinplotclient.config.Config;
import com.emilsleeper.cansteinplotclient.webserver.WebServer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class PlotCreationHandler {
    private static final Queue<PlotCreationTask> taskQueue = new ConcurrentLinkedQueue<>();
    private static final Queue<int[]> plotRenderQueue = new ConcurrentLinkedQueue<>(); // Queue for plots to render [plotX, plotY]
    private static PlotCreationTask currentTask = null;

    // periodic command timer (milliseconds)
    private static long lastPeriodicCommand = 0L;
    private static final long PERIODIC_INTERVAL_MS = 10_000L;

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> handleTick());
    }

    private static String getCurrentWorldIdentifier(MinecraftClient client) {
        try {
            if (client == null) return "";
            if (client.world != null && client.world.getRegistryKey() != null && client.world.getRegistryKey().getValue() != null) {
                return client.world.getRegistryKey().getValue().toString();
            }
        } catch (Throwable t) {
            // fallthrough and return empty
        }
        return "";
    }

    private static void handleTick() {
        MinecraftClient client = MinecraftClient.getInstance();

        // determine current world identifier (safe): use client.world registry key if available
        String currentWorld = getCurrentWorldIdentifier(client);

        // If a worldName is configured and it does not match the current world, do NOT process the task queue
        String configuredWorld = Config.getWorldName();
        if (configuredWorld != null && !configuredWorld.isEmpty()) {
            if (!configuredWorld.equals(currentWorld)) {
                // execute periodic command every 10s when server is running and periodicCommand is set
                String periodic = Config.getPeriodicCommand();
                if (periodic != null && !periodic.isEmpty() && WebServer.isRunning()) {
                    long now = System.currentTimeMillis();
                    if (now - lastPeriodicCommand >= PERIODIC_INTERVAL_MS) {
                        sendChatCommand(periodic);
                        System.out.println("[CansteinPlotClient] Sent periodic command while not in configured world: " + periodic);
                        lastPeriodicCommand = now;
                    }
                }
                // do not continue processing tasks while not in configured world
                return;
            }
        }

        // Auto-fill render tasks if queue is empty and no non-render tasks are queued
        if (taskQueue.isEmpty() && !plotRenderQueue.isEmpty()) {
            System.out.println("[CansteinPlotClient] [AUTO-FILL] TaskQueue is empty and plotRenderQueue has plots - auto-filling!");

            // Check if there are any non-render tasks that could be added later (optional check)
            // For now, just fill up to 2 render tasks
            int currentRenderCount = 0;
            for (PlotCreationTask task : taskQueue) {
                if (task instanceof RenderTopdownPlotTask) {
                    currentRenderCount++;
                }
            }
            
            System.out.println("[CansteinPlotClient] [AUTO-FILL] Current render tasks in queue: " + currentRenderCount);
            System.out.println("[CansteinPlotClient] [AUTO-FILL] Plots waiting to render: " + plotRenderQueue.size());

            // Auto-add up to 2 render tasks from the plot render queue
            while (currentRenderCount < 2 && !plotRenderQueue.isEmpty()) {
                int[] plotCoords = plotRenderQueue.poll();
                if (plotCoords != null && plotCoords.length == 2) {
                    System.out.println("[CansteinPlotClient] [AUTO-FILL] Auto-queueing render task for plot " + plotCoords[0] + ";" + plotCoords[1]);
                    taskQueue.add(new RenderTopdownPlotTask(plotCoords[0], plotCoords[1]));
                    currentRenderCount++;
                } else {
                    System.out.println("[CansteinPlotClient] [AUTO-FILL] ERROR: Invalid plot coordinates!");
                }
            }
            System.out.println("[CansteinPlotClient] [AUTO-FILL] Auto-fill complete. Render tasks now: " + currentRenderCount);
        }

        // If no task is active, try to start one
        if (currentTask == null) {
            currentTask = taskQueue.poll();
            if (currentTask == null) {
                return;
            }
        }

        if (currentTask.execute()) {
            // task finished — clear currentTask
            currentTask = null;
        }
    }

    // static helper to send chat commands from non-inner-class code
    private static void sendChatCommand(String command) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.player != null) {
            client.player.networkHandler.sendChatCommand(command);
        }
    }


    // Force the player's view to face straight south (yaw=0, pitch=0)
    private static void setPlayerLookSouth(MinecraftClient client) {
        try {
            if (client == null) client = MinecraftClient.getInstance();
            if (client == null || client.player == null) return;

            float yaw = 0.0f; // south
            float pitch = 0.0f; // level

            // Try to call setYaw/setPitch if available
            try {
                Method setYaw = client.player.getClass().getMethod("setYaw", float.class);
                Method setPitch = client.player.getClass().getMethod("setPitch", float.class);
                setYaw.invoke(client.player, yaw);
                setPitch.invoke(client.player, pitch);
            } catch (Throwable e) {
                // fallback to fields
                try {
                    Field yawField = client.player.getClass().getDeclaredField("yaw");
                    Field pitchField = client.player.getClass().getDeclaredField("pitch");
                    yawField.setAccessible(true);
                    pitchField.setAccessible(true);
                    yawField.setFloat(client.player, yaw);
                    pitchField.setFloat(client.player, pitch);
                } catch (Throwable ignored) {
                    // ignore
                }
            }
        } catch (Throwable t) {
            // ignore
        }
    }

    // Helper: Set player to Spectator mode
    private static void setPlayerSpectator(MinecraftClient client) {
        try {
            if (client == null || client.player == null) return;
            
            // Send gamemode spectator command
            sendChatCommand("gamemode spectator");
            System.out.println("[CansteinPlotClient] Set player to Spectator mode");
        } catch (Throwable t) {
            System.out.println("[CansteinPlotClient] Error setting spectator mode: " + t.getMessage());
        }
    }

    // Helper: Calculate plot center coordinates from plot grid coordinates
    private static double[] calculatePlotCenter(int plotX, int plotY) {
        // Assuming each plot is 47x47 blocks, with some spacing between them
        // Standard PlotSquared: center of plot at plotX*blockSize, plotY*blockSize
        // Each plot is 48 blocks wide (47 blocks + 1 border)
        double centerX = plotX * 48.0 + 24.0; // center within plot
        double centerZ = plotY * 48.0 + 24.0;
        return new double[]{centerX, centerZ};
    }

    // Helper: Save current screenshot to PNG file at specified path
    private static boolean saveScreenshotToFile(String filePath) {
        try {
            System.out.println("[CansteinPlotClient] [SCREENSHOT] Starting screenshot save to: " + filePath);

            MinecraftClient client = MinecraftClient.getInstance();
            if (client == null) {
                System.out.println("[CansteinPlotClient] [SCREENSHOT] ERROR: MinecraftClient is null");
                return false;
            }

            System.out.println("[CansteinPlotClient] [SCREENSHOT] MinecraftClient obtained");

            if (client.getFramebuffer() == null) {
                System.out.println("[CansteinPlotClient] [SCREENSHOT] ERROR: Framebuffer is null");
                return false;
            }

            System.out.println("[CansteinPlotClient] [SCREENSHOT] Framebuffer obtained");

            // Get the framebuffer and dimensions
            int width = client.getFramebuffer().textureWidth;
            int height = client.getFramebuffer().textureHeight;
            System.out.println("[CansteinPlotClient] [SCREENSHOT] Framebuffer dimensions: " + width + "x" + height);

            // Create a temporary NativeImage for reading
            System.out.println("[CansteinPlotClient] [SCREENSHOT] Creating NativeImage from framebuffer...");
            NativeImage nativeImage = new NativeImage(NativeImage.Format.RGBA, width, height, false);

            System.out.println("[CansteinPlotClient] [SCREENSHOT] ✓ NativeImage created");

            // Ensure directory exists
            Path filePath_path = Paths.get(filePath);
            Path directory = filePath_path.getParent();
            System.out.println("[CansteinPlotClient] [SCREENSHOT] Creating directory: " + directory);
            Files.createDirectories(directory);
            System.out.println("[CansteinPlotClient] [SCREENSHOT] Directory created successfully");

            // Save the image
            System.out.println("[CansteinPlotClient] [SCREENSHOT] Writing image file...");
            nativeImage.writeTo(new File(filePath));
            System.out.println("[CansteinPlotClient] [SCREENSHOT] ✓ Screenshot successfully saved to: " + filePath);

            // Close the native image
            nativeImage.close();
            System.out.println("[CansteinPlotClient] [SCREENSHOT] NativeImage closed");

            // Verify file exists
            File savedFile = new File(filePath);
            if (savedFile.exists()) {
                System.out.println("[CansteinPlotClient] [SCREENSHOT] ✓ File verified to exist, size: " + savedFile.length() + " bytes");
                return true;
            } else {
                System.out.println("[CansteinPlotClient] [SCREENSHOT] ERROR: File was not created");
                return false;
            }

        } catch (IOException e) {
            System.out.println("[CansteinPlotClient] [SCREENSHOT] ERROR IOException: " + e.getMessage());
            return false;
        } catch (Exception e) {
            System.out.println("[CansteinPlotClient] [SCREENSHOT] ERROR Exception: " + e.getMessage());
            return false;
        }
    }

    public static void createPlotWithPlayers(List<String> players, boolean together) {
        if (together) {
            taskQueue.add(new CreatePlotTogetherTask(players));
        } else {
            for (String player : players) {
                taskQueue.add(new CreatePlotIndividualTask(player));
            }
        }
    }

    public static void queryPlotInfo(int x, int y) {
        taskQueue.add(new QueryPlotInfoTask(x, y));
    }

    // New API tasks: teleport selected players to a target player and trust them
    public static void teleportAndTrust(String targetPlayer, List<String> players) {
        taskQueue.add(new TeleportAndTrustTask(targetPlayer, players));
    }

    // Teleport selected players to plot coordinates and trust them
    public static void teleportToPlotAndTrust(int x, int y, List<String> players) {
        taskQueue.add(new TeleportToPlotAndTrustTask(x, y, players));
    }

    // Queue a top-down render task for a plot
    public static void queueTopdownRenderForPlot(int plotX, int plotY) {
        System.out.println("[CansteinPlotClient] [API] queueTopdownRenderForPlot called for plot " + plotX + ";" + plotY);
        System.out.println("[CansteinPlotClient] [API] Current taskQueue size: " + taskQueue.size());
        System.out.println("[CansteinPlotClient] [API] Current plotRenderQueue size: " + plotRenderQueue.size());

        // Check if there are any non-render tasks in the queue
        boolean hasNonRenderTasks = taskQueue.stream()
                .anyMatch(task -> !(task instanceof RenderTopdownPlotTask));

        System.out.println("[CansteinPlotClient] [API] Non-render tasks in queue: " + hasNonRenderTasks);

        // If there are non-render tasks, don't queue the render task
        if (hasNonRenderTasks) {
            System.out.println("[CansteinPlotClient] [API] ✗ Skipping render task for plot " + plotX + ";" + plotY + " - non-render tasks in queue");
            return;
        }

        // Add to plot render queue instead of directly to task queue
        // The auto-fill mechanism in handleTick() will add it when appropriate
        plotRenderQueue.offer(new int[]{plotX, plotY});
        System.out.println("[CansteinPlotClient] [API] ✓ Queued plot for top-down render: " + plotX + ";" + plotY);
        System.out.println("[CansteinPlotClient] [API] plotRenderQueue size is now: " + plotRenderQueue.size());
    }

    private static abstract class PlotCreationTask {
        protected int tickCounter = 0;
        protected boolean isMovingForward = false;

        abstract boolean execute();

        protected void sendCommand(String command) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null) {
                client.player.networkHandler.sendChatCommand(command);
            }
        }

        protected boolean waitTicks(int ticks) {
            tickCounter++;
            if (tickCounter >= ticks) {
                tickCounter = 0;
                return true;
            }
            return false;
        }

        /**
         * Teleports the player 1 block forward and 0.5 blocks down based on their yaw
         */
        protected void teleportPlayerForward() {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null) {
                return;
            }

            double yaw = Math.toRadians(client.player.getYaw());
            double forwardDistance = 1.0; // 1 block forward
            double downDistance = 0.5; // 0.5 blocks down

            // Calculate new position based on yaw
            double newX = client.player.getX() - Math.sin(yaw) * forwardDistance;
            double newY = client.player.getY() - downDistance;
            double newZ = client.player.getZ() + Math.cos(yaw) * forwardDistance;

            // Set the player position directly
            client.player.setPosition(newX, newY, newZ);
            System.out.println("[CansteinPlotClient] Teleported player 1 block forward and 0.5 blocks down");
        }

        protected boolean moveForwardForTicks(int maxTicks) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null) {
                return false;
            }

            if (tickCounter < maxTicks) {
                // Use velocity to move forward
                double yaw = Math.toRadians(client.player.getYaw());
                double moveSpeed = 0.8;
                client.player.setVelocity(
                    -Math.sin(yaw) * moveSpeed,
                    client.player.getVelocity().y,
                    Math.cos(yaw) * moveSpeed
                );
                client.player.velocityModified = true;
                tickCounter++;
                return false;
            }

            isMovingForward = false;
            tickCounter = 0;
            return true;
        }
    }

    private static class CreatePlotTogetherTask extends PlotCreationTask {
        private final List<String> players;
        private int state = 0;

        CreatePlotTogetherTask(List<String> players) {
            this.players = players;
        }

        @Override
        boolean execute() {
            switch (state) {
                case 0:
                    sendCommand("p auto");
                    System.out.println("[CansteinPlotClient] Sent p auto command");
                    state++;
                    return false;
                case 1:
                    if (waitTicks(60)) {
                        state++;
                    }
                    return false;
                case 2:
                    // Teleport player 1 block forward and 0.5 blocks down before moving
                    teleportPlayerForward();
                    state++;
                    return false;
                case 3:
                    // Move forward 20 ticks
                    if (moveForwardForTicks(20)) {
                        System.out.println("[CansteinPlotClient] Movement completed, sending p auto command");
                        state++;
                    }
                    return false;
                case 4:
                    if (waitTicks(30)) {
                        state++;
                    }
                    return false;
                case 5:
                    if (waitTicks(10)) {
                        sendCommand("p middle");
                        System.out.println("[CansteinPlotClient] Sent p middle command");
                        state++;
                    }
                    return false;
                case 6:
                    for (String player : players) {
                        sendCommand("p trust " + player);
                        System.out.println("[CansteinPlotClient] Sent p trust command for " + player);
                    }
                    state++;
                    return false;
                case 7:
                    sendCommand("p setowner ServerInfo");
                    System.out.println("[CansteinPlotClient] Sent p setowner command");
                    state++;
                    return false;
                case 8:
                    if (waitTicks(20)) {
                        sendCommand("p confirm");
                        System.out.println("[CansteinPlotClient] Sent p confirm command");
                        state++;
                    }
                    return false;
                case 9:
                    if (waitTicks(10)) {
                        for (String player : players) {
                            sendCommand("tphere " + player);
                            System.out.println("[CansteinPlotClient] Sent tphere command for " + player);
                        }
                        state++;
                    }
                    return false;
                case 10:
                    System.out.println("[CansteinPlotClient] CreatePlotTogetherTask completed");
                    return true;
                default:
                    return true;
            }
        }
    }

    private static class CreatePlotIndividualTask extends PlotCreationTask {
        private final String player;
        private int state = 0;

        CreatePlotIndividualTask(String player) {
            this.player = player;
        }

        @Override
        boolean execute() {
            switch (state) {
                case 0:
                    sendCommand("p auto");
                    System.out.println("[CansteinPlotClient] Sent p auto command");
                    state++;
                    return false;
                case 1:
                    if (waitTicks(60)) {
                        state++;
                    }
                    return false;
                case 2:
                    // Teleport player 1 block forward and 0.5 blocks down before moving
                    teleportPlayerForward();
                    state++;
                    return false;
                case 3:
                    // Move forward 20 ticks
                    if (moveForwardForTicks(20)) {
                        System.out.println("[CansteinPlotClient] Movement completed");
                        state++;
                    }
                    return false;
                case 4:
                    if (waitTicks(30)) {
                        state++;
                    }
                    return false;
                case 5:
                    if (waitTicks(10)) {
                        sendCommand("p middle");
                        System.out.println("[CansteinPlotClient] Sent p middle command");
                        state++;
                    }
                    return false;
                case 6:
                    sendCommand("p trust " + player);
                    System.out.println("[CansteinPlotClient] Sent p trust command for " + player);
                    state++;
                    return false;
                case 7:
                    sendCommand("p setowner ServerInfo");
                    System.out.println("[CansteinPlotClient] Sent p setowner command");
                    state++;
                    return false;
                case 8:
                    if (waitTicks(20)) {
                        sendCommand("p confirm");
                        System.out.println("[CansteinPlotClient] Sent p confirm command");
                        state++;
                    }
                    return false;
                case 9:
                    if (waitTicks(10)) {
                        sendCommand("tphere " + player);
                        System.out.println("[CansteinPlotClient] Sent tphere command for " + player);
                        state++;
                    }
                    return false;
                case 10:
                    System.out.println("[CansteinPlotClient] CreatePlotIndividualTask completed for " + player);
                    return true;
                default:
                    return true;
            }
        }
    }

    private static class QueryPlotInfoTask extends PlotCreationTask {
        private final int x;
        private final int y;
        private int state = 0;

        QueryPlotInfoTask(int x, int y) {
            this.x = x;
            this.y = y;
        }

        @Override
        boolean execute() {
            switch (state) {
                case 0:
                    sendCommand("p i " + x + ";" + y);
                    System.out.println("[CansteinPlotClient] Querying plot info for: " + x + ";" + y);
                    state++;
                    return false;
                case 1:
                    if (waitTicks(20)) {
                        System.out.println("[CansteinPlotClient] Plot info query completed for: " + x + ";" + y);
                        return true;
                    }
                    return false;
                default:
                    return true;
            }
        }
    }

    private static class TeleportAndTrustTask extends PlotCreationTask {
        private final String targetPlayer;
        private final List<String> players;
        private int state = 0;

        TeleportAndTrustTask(String targetPlayer, List<String> players) {
            this.targetPlayer = targetPlayer;
            this.players = players;
        }

        @Override
        boolean execute() {
            switch (state) {
                case 0:
                    // teleport each selected player to the target player
                    sendCommand("tp " + targetPlayer);
                    // make the player look south immediately after teleport
                    setPlayerLookSouth(null);
                    state++;
                    return false;
                case 1:
                    if (waitTicks(20)) {
                        state++;
                    }
                    return false;
                case 2:
                    // trust each player on current plot
                    for (String p : players) {
                        sendCommand("p trust " + p);
                    }
                    state++;
                    return false;
                case 3:
                    for (String p : players) {
                        sendCommand("tphere " + p);
                    }
                    state++;
                    return true;
                default:
                    return true;
            }
        }
    }

    private static class TeleportToPlotAndTrustTask extends PlotCreationTask {
        private final int x;
        private final int y;
        private final List<String> players;
        private int state = 0;

        TeleportToPlotAndTrustTask(int x, int y, List<String> players) {
            this.x = x;
            this.y = y;
            this.players = players;
        }

        @Override
        boolean execute() {
            switch (state) {
                case 0:
                    // teleport each selected player to the target player
                    sendCommand("p v " + x + ";" + y);
                    // make the player look south immediately after teleporting to plot
                    setPlayerLookSouth(null);
                    state++;
                    return false;

                case 1:
                    if (waitTicks(40)) {
                        state++;
                    }
                    return false;
                case 2:
                    // Teleport player 1 block forward and 0.5 blocks down before moving
                    teleportPlayerForward();
                    state++;
                    return false;
                case 3:
                    // Move forward 20 ticks
                    if (moveForwardForTicks(20)) {
                        System.out.println("[CansteinPlotClient] Movement completed, sending p auto command");
                        state++;
                    }
                    return false;
                case 4:
                    if (waitTicks(30)) {
                        state++;
                    }
                    return false;
                case 5:
                    // trust each player on current plot
                    for (String p : players) {
                        sendCommand("p trust " + p);
                    }
                    state++;
                    return false;
                case 6:
                    for (String p : players) {
                        sendCommand("tphere " + p);
                    }
                    state++;
                    return true;
                default:
                    return true;
            }
        }
    }

    private static class RenderTopdownPlotTask extends PlotCreationTask {
        private final int plotX;
        private final int plotY;
        private int state = 0;

        RenderTopdownPlotTask(int plotX, int plotY) {
            this.plotX = plotX;
            this.plotY = plotY;
        }

        @Override
        boolean execute() {
            MinecraftClient client = MinecraftClient.getInstance();
            
            switch (state) {
                case 0:
                    // Set player to Spectator mode
                    System.out.println("[CansteinPlotClient] [RENDER] State 0: Setting player to Spectator mode for plot " + plotX + ";" + plotY);
                    setPlayerSpectator(client);
                    state++;
                    return false;
                
                case 1:
                    // Wait for gamemode change
                    System.out.println("[CansteinPlotClient] [RENDER] State 1: Waiting for gamemode change (tick " + tickCounter + "/20)");
                    if (waitTicks(20)) {
                        state++;
                    }
                    return false;
                
                case 2:
                    // Teleport player to northern edge of plot, facing south
                    System.out.println("[CansteinPlotClient] [RENDER] State 2: Teleporting to northern edge of plot " + plotX + ";" + plotY);
                    double[] plotCenter = calculatePlotCenter(plotX, plotY);
                    double centerX = plotCenter[0];
                    double centerZ = plotCenter[1];
                    
                    // Northern edge: 1 block north of the plot, at center X
                    double northX = centerX;
                    double northZ = centerZ - 25.0; // north of center (24 blocks is plot half-size)
                    double startY = 100.0; // high enough to see the whole plot
                    
                    if (client.player != null) {
                        client.player.setPosition(northX, startY, northZ);
                        setPlayerViewPitch(client, 0.0f); // level view
                        setPlayerViewYaw(client, 0.0f); // facing south
                        System.out.println("[CansteinPlotClient] [RENDER] State 2: ✓ Teleported to position: X=" + northX + ", Y=" + startY + ", Z=" + northZ);
                    } else {
                        System.out.println("[CansteinPlotClient] [RENDER] State 2: ERROR - Player is null!");
                    }
                    state++;
                    return false;
                
                case 3:
                    // Wait for chunks to load (3 chunks in each direction)
                    System.out.println("[CansteinPlotClient] [RENDER] State 3: Waiting for chunks to load (tick " + tickCounter + "/60)");
                    if (waitTicks(60)) {
                        state++;
                    }
                    return false;
                
                case 4:
                    // Teleport player directly above plot center and look down
                    System.out.println("[CansteinPlotClient] [RENDER] State 4: Positioning above plot center for top-down view");
                    double[] center = calculatePlotCenter(plotX, plotY);
                    double centerX2 = center[0];
                    double centerZ2 = center[1];
                    double viewHeight = 80.0; // high above the plot for top-down view
                    
                    if (client.player != null) {
                        client.player.setPosition(centerX2, viewHeight, centerZ2);
                        setPlayerViewPitch(client, 90.0f); // pitch down (looking straight down)
                        setPlayerViewYaw(client, 0.0f); // yaw doesn't matter when looking straight down
                        System.out.println("[CansteinPlotClient] [RENDER] State 4: ✓ Positioned above center: X=" + centerX2 + ", Y=" + viewHeight + ", Z=" + centerZ2 + ", Pitch=90°");
                    } else {
                        System.out.println("[CansteinPlotClient] [RENDER] State 4: ERROR - Player is null!");
                    }
                    state++;
                    return false;
                
                case 5:
                    // Wait for rendering
                    System.out.println("[CansteinPlotClient] [RENDER] State 5: Waiting for rendering (tick " + tickCounter + "/10)");
                    if (waitTicks(10)) {
                        state++;
                    }
                    return false;
                
                case 6:
                    // Render and save screenshot
                    System.out.println("[CansteinPlotClient] [RENDER] State 6: Saving screenshot for plot " + plotX + ";" + plotY);
                    try {
                        String runDir = client.runDirectory.getAbsolutePath();
                        System.out.println("[CansteinPlotClient] [RENDER] Run directory: " + runDir);

                        Path cansteinDir = Paths.get(runDir, "canstein");
                        System.out.println("[CansteinPlotClient] [RENDER] Canstein directory: " + cansteinDir);

                        Files.createDirectories(cansteinDir);
                        System.out.println("[CansteinPlotClient] [RENDER] ✓ Canstein directory created/verified");

                        String filename = "plot" + plotX + "-" + plotY + ".png";
                        String filepath = cansteinDir.resolve(filename).toString();
                        System.out.println("[CansteinPlotClient] [RENDER] Target filepath: " + filepath);

                        // Force render frame and save
                        System.out.println("[CansteinPlotClient] [RENDER] Calling saveScreenshotToFile()...");
                        if (saveScreenshotToFile(filepath)) {
                            System.out.println("[CansteinPlotClient] [RENDER] ✓✓✓ Top-down render SUCCESSFULLY saved for plot " + plotX + ";" + plotY);
                        } else {
                            System.out.println("[CansteinPlotClient] [RENDER] ✗✗✗ FAILED to save screenshot!");
                        }
                    } catch (IOException e) {
                        System.out.println("[CansteinPlotClient] [RENDER] ERROR IOException: " + e.getMessage());
                        e.printStackTrace();
                    }
                    state++;
                    return false;
                
                case 7:
                    // Task complete
                    System.out.println("[CansteinPlotClient] [RENDER] State 7: RenderTopdownPlotTask COMPLETED for plot " + plotX + ";" + plotY);
                    return true;
                
                default:
                    System.out.println("[CansteinPlotClient] [RENDER] ERROR: Unknown state " + state);
                    return true;
            }
        }

        // Helper to set player view pitch
        private void setPlayerViewPitch(MinecraftClient client, float pitch) {
            try {
                if (client == null || client.player == null) return;
                
                try {
                    Method setPitch = client.player.getClass().getMethod("setPitch", float.class);
                    setPitch.invoke(client.player, pitch);
                } catch (Throwable e) {
                    try {
                        Field pitchField = client.player.getClass().getDeclaredField("pitch");
                        pitchField.setAccessible(true);
                        pitchField.setFloat(client.player, pitch);
                    } catch (Throwable ignored) {
                        // ignore
                    }
                }
            } catch (Throwable t) {
                // ignore
            }
        }

        // Helper to set player view yaw
        private void setPlayerViewYaw(MinecraftClient client, float yaw) {
            try {
                if (client == null || client.player == null) return;
                
                try {
                    Method setYaw = client.player.getClass().getMethod("setYaw", float.class);
                    setYaw.invoke(client.player, yaw);
                } catch (Throwable e) {
                    try {
                        Field yawField = client.player.getClass().getDeclaredField("yaw");
                        yawField.setAccessible(true);
                        yawField.setFloat(client.player, yaw);
                    } catch (Throwable ignored) {
                        // ignore
                    }
                }
            } catch (Throwable t) {
                // ignore
            }
        }
    }
}
