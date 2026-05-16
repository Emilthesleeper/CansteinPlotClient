package com.emilsleeper.cansteinplotclient.webserver;
import com.emilsleeper.cansteinplotclient.config.Config;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
@Environment(EnvType.CLIENT)
public class WebServer {
    private static HttpServer server;
    private static final List<String> chatMessages = Collections.synchronizedList(new ArrayList<>());
    public static final Map<String, Long> playerStatus = Collections.synchronizedMap(new HashMap<>());
    private static final Map<String, String> plotInfoCache = Collections.synchronizedMap(new HashMap<>());
    private static final Map<Integer, Map<Integer, PlotInfoRecord>> plotInfoRecords = Collections.synchronizedMap(new HashMap<>());
    private static final java.util.concurrent.atomic.AtomicLong lastPlotQueryTime = new java.util.concurrent.atomic.AtomicLong(0);
    private static boolean isRunning = false;
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private static final List<PlotCreationListener> plotListeners = Collections.synchronizedList(new ArrayList<>());
    private static final List<PlotCaptureListener> plotCaptureListeners = Collections.synchronizedList(new ArrayList<>());

    public static void start() {
        if (isRunning) return;
        try {
            String address = Config.getServerAddress();
            int port = Config.getServerPort();
            InetSocketAddress addr = new InetSocketAddress(address, port);
            server = HttpServer.create(addr, 0);
            server.createContext("/", new RootHandler());
            server.createContext("/api/data", new ApiDataHandler());
            server.createContext("/api/plot-info", new ApiPlotInfoHandler());
            server.createContext("/api/players", new ApiPlayersHandler());
            server.createContext("/api/plot-action", new ApiPlotActionHandler());
            server.createContext("/api/plot-capture", new ApiPlotCaptureHandler());
            server.createContext("/api/plots", new ApiPlotsHandler());
            server.setExecutor(null);
            server.start();
            isRunning = true;
            System.out.println("[CansteinPlotClient] Webserver started at http://" + address + ":" + port);
        } catch (IOException e) {
            System.err.println("[CansteinPlotClient] Failed to start webserver: " + e.getMessage());
        }
    }

    // expose running state for other classes
    public static boolean isRunning() { return isRunning; }

    public static void stop() {
        if (server != null && isRunning) {
            server.stop(0);
            isRunning = false;
            scheduler.shutdown();
            System.out.println("[CansteinPlotClient] Webserver stopped");
        }
    }

    public static void addChatMessage(String message) {
        // Store raw message (no timestamp here). UI will format/strip timestamps for display.
        chatMessages.add(message);
        if (chatMessages.size() > 1000) chatMessages.remove(0);

        // Try to detect and parse ParzellenSystem info blocks contained in chat messages
        try {
            parseAndStorePlotInfoFromChat(message);
        } catch (Exception e) {
            // ignore parse errors
        }
    }

    private static void parseAndStorePlotInfoFromChat(String message) {
        if (message == null) return;
        if (!message.contains("ParzellenSystem") && !message.contains("Parzellen System")) return;
        // Normalize line endings and split
        String[] lines = message.replace("\r\n", "\n").replace('\r','\n').split("\n");
        String coords = null;
        String owner = null;
        List<String> permanent = new ArrayList<>();
        for (String l : lines) {
            String line = l.trim();
            if (line.startsWith("Koordinaten:")) {
                String val = line.substring("Koordinaten:".length()).trim();
                coords = val.replaceAll("\\s+","");
            } else if (line.startsWith("Besitzer:")) {
                owner = line.substring("Besitzer:".length()).trim();
                if (owner.equals("-")) owner = "";
            } else if (line.startsWith("> dauerhaft:")) {
                String val = line.substring("> dauerhaft:".length()).trim();
                if (!val.equals("-")) {
                    // split by commas
                    String[] parts = val.split(",");
                    for (String p : parts) {
                        String pp = p.trim();
                        if (!pp.isEmpty()) permanent.add(pp);
                    }
                }
            }
        }
        if (coords != null) {
            String[] p = coords.split(";");
            if (p.length == 2) {
                try {
                    int x = Integer.parseInt(p[0]);
                    int y = Integer.parseInt(p[1]);
                    long ts = System.currentTimeMillis() / 1000L;
                    storePlotRecord(x, y, owner == null ? "" : owner, permanent, ts);
                } catch (NumberFormatException ignored) {}
            }
        }
    }

    private static void storePlotRecord(int x, int y, String owner, List<String> permanent, long timestamp) {
        String coords = x + ";" + y;
        // store legacy cache string for compatibility
        String infoStr = "owner:" + (owner == null ? "" : owner) + ";permanent:" + String.join(",", permanent) + ";ts:" + timestamp;
        plotInfoCache.put(coords, infoStr);

        // store structured record
        synchronized (plotInfoRecords) {
            Map<Integer, PlotInfoRecord> row = plotInfoRecords.get(x);
            if (row == null) {
                row = Collections.synchronizedMap(new HashMap<>());
                plotInfoRecords.put(x, row);
            }
            row.put(y, new PlotInfoRecord(owner == null ? "" : owner, new ArrayList<>(permanent), timestamp));
        }
        System.out.println("[CansteinPlotClient] Stored plot info for " + coords + " owner=" + owner + " permanent=" + permanent + " ts=" + timestamp);
    }

    private static class PlotInfoRecord {
        public final String owner;
        public final List<String> permanent;
        public final long timestamp;

        PlotInfoRecord(String owner, List<String> permanent, long timestamp) {
            this.owner = owner;
            this.permanent = permanent;
            this.timestamp = timestamp;
        }
    }

    public static void setPlayerStatus(String playerName) {
        playerStatus.put(playerName, System.currentTimeMillis());
    }

    public static void removePlayerStatus(String playerName) {
        playerStatus.remove(playerName);
    }

    public static void setPlotInfo(String coordinates, String info) {
        plotInfoCache.put(coordinates, info);
    }

    public static String getPlotInfo(String coordinates) {
        return plotInfoCache.getOrDefault(coordinates, "");
    }

    public static void addPlotListener(PlotCreationListener listener) {
        plotListeners.add(listener);
    }

    public static void removePlotListener(PlotCreationListener listener) {
        plotListeners.remove(listener);
    }

    public static void addPlotCaptureListener(PlotCaptureListener listener) {
        plotCaptureListeners.add(listener);
    }

    public static void removePlotCaptureListener(PlotCaptureListener listener) {
        plotCaptureListeners.remove(listener);
    }

    public static void notifyPlotCreation(List<String> selectedPlayers, boolean together) {
        for (PlotCreationListener listener : plotListeners) {
            listener.onPlotCreation(selectedPlayers, together);
        }
    }

    public static void notifyPlotCapture(int x, int y) {
        for (PlotCaptureListener listener : plotCaptureListeners) {
            try {
                listener.onPlotCapture(x, y);
            } catch (Exception ignored) {
            }
        }
    }

    public interface PlotCreationListener {
        void onPlotCreation(List<String> selectedPlayers, boolean together);
    }

    public interface PlotCaptureListener {
        void onPlotCapture(int x, int y);
    }

    private static boolean authenticate(HttpExchange exchange) throws IOException {
        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer realm=\"CansteinPlot\"");
            exchange.sendResponseHeaders(401, 0);
            exchange.close();
            return false;
        }
        String token = authHeader.substring(7);
        if (!token.equals(Config.getPassword())) {
            exchange.sendResponseHeaders(401, 0);
            exchange.close();
            return false;
        }
        return true;
    }

    static class RootHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            // Root Handler serves the login page without authentication
            String html = generateMainPage();
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, html.getBytes(StandardCharsets.UTF_8).length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(html.getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    static class ApiPlayersHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            if (!authenticate(exchange)) return;
            StringBuilder json = new StringBuilder("{\"players\":[");
            List<String> sortedPlayers = new ArrayList<>(playerStatus.keySet());
            sortedPlayers.removeIf(p -> p.startsWith("~BTLP"));
            Collections.sort(sortedPlayers);
            boolean first = true;
            for (String player : sortedPlayers) {
                if (!first) json.append(",");
                json.append("{\"name\":\"").append(escapeJson(player)).append("\",\"lastUpdate\":").append(playerStatus.get(player)).append("}");
                first = false;
            }
            json.append("]}");
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            byte[] response = json.toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        }
    }

    static class ApiDataHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            if (!authenticate(exchange)) return;
            StringBuilder json = new StringBuilder("{");

            json.append("\"players\":[");
            List<String> sortedPlayers = new ArrayList<>(playerStatus.keySet());
            sortedPlayers.removeIf(p -> p.startsWith("~BTLP"));
            Collections.sort(sortedPlayers);
            boolean firstPlayer = true;
            for (String player : sortedPlayers) {
                if (!firstPlayer) json.append(",");
                json.append("{\"name\":\"").append(escapeJson(player)).append("\"}");
                firstPlayer = false;
            }
            json.append("],");

            json.append("\"messages\":[");
            for (int i = Math.max(0, chatMessages.size() - 50); i < chatMessages.size(); i++) {
                if (i > Math.max(0, chatMessages.size() - 50)) json.append(",");
                json.append("\"").append(escapeJson(chatMessages.get(i))).append("\"");
            }
            json.append("],");

            json.append("\"plots\":{");
            boolean firstPlot = true;
            for (String coords : plotInfoCache.keySet()) {
                if (!firstPlot) json.append(",");
                json.append("\"").append(coords).append("\":\"").append(escapeJson(plotInfoCache.get(coords))).append("\"");
                firstPlot = false;
            }
            json.append("}");

            json.append("}");
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            byte[] response = json.toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        }
    }

    static class ApiPlotInfoHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            if (!authenticate(exchange)) return;
            String query = exchange.getRequestURI().getQuery();
            if (query == null || !query.startsWith("coords=")) {
                exchange.sendResponseHeaders(400, 0);
                exchange.close();
                return;
            }
            String coords = query.substring(7);
            try {
                coords = java.net.URLDecoder.decode(coords, StandardCharsets.UTF_8.name());
            } catch (Exception e) {
                // Keep original
            }

            String[] parts = coords.split(";");
            if (parts.length != 2) {
                exchange.sendResponseHeaders(400, 0);
                exchange.close();
                return;
            }

            String info = plotInfoCache.getOrDefault(coords, "");
            String json = "{\"coordinates\":\"" + escapeJson(coords) + "\",\"info\":\"" + escapeJson(info) + "\"}";

            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            byte[] response = json.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        }
    }

    static class ApiPlotsHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            if (!authenticate(exchange)) return;
            StringBuilder json = new StringBuilder();
            json.append("{\"plots\":{");
            boolean firstX = true;
            synchronized (plotInfoRecords) {
                for (Integer x : plotInfoRecords.keySet()) {
                    if (!firstX) json.append(",");
                    json.append("\"").append(x).append("\":{");
                    boolean firstY = true;
                    Map<Integer, PlotInfoRecord> row = plotInfoRecords.get(x);
                    synchronized (row) {
                        for (Integer y : row.keySet()) {
                            if (!firstY) json.append(",");
                            PlotInfoRecord r = row.get(y);
                            json.append("\"").append(y).append("\":{");
                            json.append("\"owner\":\"").append(escapeJson(r.owner)).append("\",");
                            json.append("\"permanent\":[");
                            boolean fp = true;
                            for (String p : r.permanent) {
                                if (!fp) json.append(",");
                                json.append("\"").append(escapeJson(p)).append("\"");
                                fp = false;
                            }
                            json.append("],");
                            json.append("\"ts\":").append(r.timestamp);
                            json.append("}");
                            firstY = false;
                        }
                    }
                    json.append("}");
                    firstX = false;
                }
            }
            json.append("}}");
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            byte[] response = json.toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        }
    }

    static class ApiPlotActionHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            if (!authenticate(exchange)) return;
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, 0);
                exchange.close();
                return;
            }
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                String json = sb.toString();
                boolean together = json.contains("\"action\":\"createPlotTogether\"");
                List<String> players = new ArrayList<>();
                int start = json.indexOf("[");
                int end = json.lastIndexOf("]");
                if (start != -1 && end != -1) {
                    String playerJson = json.substring(start + 1, end);
                    for (String p : playerJson.split(",")) {
                        p = p.trim().replace("\"", "");
                        if (!p.isEmpty()) players.add(p);
                    }
                }
                notifyPlotCreation(players, together);
                exchange.sendResponseHeaders(200, 0);
            } catch (Exception e) {
                exchange.sendResponseHeaders(500, 0);
            }
            exchange.close();
        }
    }

    static class ApiPlotCaptureHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            if (!authenticate(exchange)) return;
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, 0);
                exchange.close();
                return;
            }
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                String json = sb.toString();
                int xStart = json.indexOf("\"x\":");
                int yStart = json.indexOf("\"y\":");
                if (xStart == -1 || yStart == -1) {
                    exchange.sendResponseHeaders(400, 0);
                    exchange.close();
                    return;
                }
                String xStr = json.substring(xStart + 4).split(",")[0].trim();
                String yStr = json.substring(yStart + 4).split("}")[0].trim();
                int x = Integer.parseInt(xStr);
                int y = Integer.parseInt(yStr);
                String coords = x + ";" + y;
                System.out.println("[CansteinPlotClient] Plot capture requested for: " + coords);
                long now = System.currentTimeMillis();
                long prev = lastPlotQueryTime.get();
                if (now - prev < 1000) {
                    // rate limited, respond with 429
                    exchange.sendResponseHeaders(429, 0);
                    exchange.close();
                    return;
                }
                lastPlotQueryTime.set(now);
                notifyPlotCapture(x, y);
                exchange.sendResponseHeaders(200, 0);
            } catch (Exception e) {
                System.err.println("[CansteinPlotClient] Error in plot capture: " + e.getMessage());
                e.printStackTrace();
                exchange.sendResponseHeaders(500, 0);
            }
            exchange.close();
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    /**
     * Convert Minecraft color codes (§c, §4, etc.) to HTML/CSS
     */
    private static String minecraftCodesToHtml(String text) {
        if (text == null) return "";

        // Map of Minecraft color codes to hex colors
        String[] colorMap = {
            "§0", "#000000", // Black
            "§1", "#0000AA", // Dark Blue
            "§2", "#00AA00", // Dark Green
            "§3", "#00AAAA", // Dark Cyan
            "§4", "#AA0000", // Dark Red
            "§5", "#AA00AA", // Dark Magenta
            "§6", "#FFAA00", // Gold
            "§7", "#AAAAAA", // Light Gray
            "§8", "#555555", // Dark Gray
            "§9", "#5555FF", // Blue
            "§a", "#55FF55", // Green
            "§b", "#55FFFF", // Cyan
            "§c", "#FF5555", // Red
            "§d", "#FF55FF", // Magenta
            "§e", "#FFFF55", // Yellow
            "§f", "#FFFFFF"  // White
        };

        StringBuilder result = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            if (i < text.length() - 1 && text.charAt(i) == '§') {
                char code = text.charAt(i + 1);
                String color = null;

                // Find matching color
                for (int j = 0; j < colorMap.length; j += 2) {
                    if (colorMap[j].equals("§" + code)) {
                        color = colorMap[j + 1];
                        break;
                    }
                }

                if (code == 'r') {
                    // Reset
                    result.append("</span>");
                } else if (code == 'l') {
                    // Bold
                    result.append("<span style=\"font-weight: bold;\">");
                } else if (code == 'o') {
                    // Italic
                    result.append("<span style=\"font-style: italic;\">");
                } else if (code == 'n') {
                    // Underline
                    result.append("<span style=\"text-decoration: underline;\">");
                } else if (color != null) {
                    // Color code
                    result.append("<span style=\"color: ").append(color).append(";\">");
                } else {
                    // Unknown code, just output it
                    result.append("§").append(code);
                    i += 2;
                    continue;
                }
                i += 2;
            } else {
                result.append(text.charAt(i));
                i++;
            }
        }

        return result.toString();
    }

    private static String generateMainPage() {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n");
        html.append("<html>\n");
        html.append("<head>\n");
        html.append("  <meta charset=\"UTF-8\">\n");
        html.append("  <title>CansteinPlot</title>\n");
        html.append("  <style>\n");
        html.append("    * { margin: 0; padding: 0; box-sizing: border-box; }\n");
        html.append("    html, body { width: 100%; height: 100%; }\n");
        html.append("    body { font-family: 'Segoe UI', sans-serif; background: #1e1e1e; color: #fff; overflow: hidden; }\n");
        html.append("    #app { display: flex; flex-direction: column; width: 100vw; height: 100vh; }\n");
        html.append("    #login-screen { display: flex; justify-content: center; align-items: center; width: 100%; height: 100%; background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); }\n");
        html.append("    .login-box { background: #2a2a2a; padding: 30px; border-radius: 8px; width: 300px; text-align: center; }\n");
        html.append("    .login-box h1 { margin-bottom: 20px; color: #667eea; }\n");
        html.append("    .login-box input { width: 100%; padding: 12px; margin: 10px 0; border-radius: 4px; border: 1px solid #404040; background: #1e1e1e; color: #fff; font-size: 14px; }\n");
        html.append("    .login-box button { width: 100%; padding: 12px; margin: 10px 0; border-radius: 4px; border: none; background: #667eea; color: white; font-size: 14px; font-weight: bold; cursor: pointer; }\n");
        html.append("    .login-box button:hover { background: #764ba2; }\n");
        html.append("    #dashboard { display: none; width: 100%; height: 100%; }\n");
        html.append("    .content { display: flex; gap: 5px; padding: 5px; flex: 1; overflow: hidden; }\n");
        html.append("    .panel { background: #2a2a2a; border-radius: 4px; border: 1px solid #404040; overflow: hidden; display: flex; flex-direction: column; }\n");
        html.append("    .panel-header { background: #333; padding: 8px; border-bottom: 1px solid #404040; font-weight: bold; font-size: 12px; user-select: none; }\n");
        html.append("    .panel-content { flex: 1; overflow-y: auto; overflow-x: hidden; padding: 8px; font-size: 12px; }\n");
        html.append("    .player-item { padding: 6px; background: #1e1e1e; border-radius: 3px; margin-bottom: 3px; border-left: 2px solid #667eea; cursor: pointer; }\n");
        html.append("    .player-item:hover { background: #333; }\n");
        html.append("    .player-item.selected { background: #667eea; color: #1e1e1e; font-weight: bold; }\n");
        html.append("    .message-item { padding: 2px 4px; background: #1e1e1e; border-radius: 2px; margin-bottom: 1px; font-family: monospace; font-size: 11px; line-height: 1.1; word-wrap: break-word; }\n");
        html.append("    .map-container { position: relative; background: #111; overflow: hidden; cursor: grab; flex: 1; border: 1px solid #333; min-height: 100vh; max-height: 100vh;}\n");
        html.append("    .map-container.panning { cursor: grabbing; }\n");
        html.append("    .action-btn { padding: 8px; background: #667eea; color: white; border: none; border-radius: 3px; cursor: pointer; width: 100%; margin: 3px 0; font-size: 11px; }\n");
        html.append("    .action-btn:hover { background: #764ba2; }\n");
        html.append("    #left-col { display: flex; flex-direction: column; width: 23vw; gap: 5px; }\n");
        html.append("    #left-col > :first-child { flex: 1; min-height: 200px; }\n");
        html.append("    #left-col > :nth-child(2) { flex: 0 0 50%; min-height: 100px; max-height: 50%; }\n");
        html.append("    #center-col { flex: 1; display: flex; flex-direction: column; gap: 5px; }\n");
        html.append("    #right-col { display: flex; flex-direction: column; width: 17vw; gap: 5px; }\n");
        html.append("    .plot-tooltip { position: absolute; background: rgba(0,0,0,0.85); color: #fff; padding: 4px 6px; border-radius: 3px; font-size: 10px; font-family: monospace; pointer-events: none; z-index: 20; white-space: nowrap; }\n");
        html.append("  </style>\n");
        html.append("</head>\n");
        html.append("<body>\n");
        html.append("  <div id=\"app\">\n");
        html.append("    <div id=\"login-screen\">\n");
        html.append("      <div class=\"login-box\">\n");
        html.append("        <h1>CansteinPlot</h1>\n");
        html.append("        <input type=\"password\" id=\"password-input\" placeholder=\"Passwort\" autocomplete=\"current-password\">\n");
        html.append("        <button type=\"button\" id=\"login-btn\">Login</button>\n");
        html.append("      </div>\n");
        html.append("    </div>\n");
        html.append("    <div id=\"dashboard\">\n");
        html.append("      <div class=\"content\">\n");
        html.append("        <div id=\"left-col\">\n");
        html.append("          <div class=\"panel\">\n");
        html.append("            <div class=\"panel-header\">👥 Spieler</div>\n");
        html.append("            <div class=\"panel-content\" id=\"players-list\"></div>\n");
        html.append("          </div>\n");
        html.append("        </div>\n");
        html.append("        <div id=\"center-col\">\n");
        html.append("          <div class=\"panel\">\n");
        html.append("            <div class=\"panel-header\">🗺️ Plot-Karte</div>\n");
        html.append("            <div class=\"map-container\" id=\"map-container\"></div>\n");
        html.append("          </div>\n");
        html.append("        </div>\n");
        html.append("        <div id=\"right-col\">\n");
        html.append("          <div class=\"panel\">\n");
        html.append("            <div class=\"panel-header\">⚙️ Aktionen</div>\n");
        html.append("            <div class=\"panel-content\" id=\"player-actions\">\n");
        html.append("              <div style=\"text-align: center; color: #888; padding: 10px;\">Spieler auswählen</div>\n");
        html.append("            </div>\n");
        html.append("          </div>\n");
        html.append("          <div class=\"panel\">\n");
        html.append("            <div class=\"panel-header\">💬 Chat</div>\n");
        html.append("            <div class=\"panel-content\" id=\"chat-list\"></div>\n");
        html.append("          </div>\n");
        html.append("        </div>\n");
        html.append("      </div>\n");
        html.append("    </div>\n");
        html.append("  </div>\n");

        html.append("  <script>\n");
        html.append("    const TILE_BASE_URL = '/images/plots/';\n");
        html.append("    let token = null;\n");
        html.append("\n");
        html.append("    function makeRequest(url, opts) {\n");
        html.append("      opts = opts || {};\n");
        html.append("      opts.headers = opts.headers || {};\n");
        html.append("      opts.headers.Authorization = 'Bearer ' + token;\n");
        html.append("      return fetch(url, opts);\n");
        html.append("    }\n");
        html.append("\n");
        html.append("    function handleLogin() {\n");
        html.append("      const password = document.getElementById('password-input').value;\n");
        html.append("      if (password.length === 0) {\n");
        html.append("        alert('Bitte Passwort eingeben');\n");
        html.append("        return;\n");
        html.append("      }\n");
        html.append("      token = password;\n");
        html.append("      console.log('[CansteinPlot] Login successful with token:', token);\n");
        html.append("      document.getElementById('login-screen').style.display = 'none';\n");
        html.append("      document.getElementById('dashboard').style.display = 'flex';\n");
        html.append("      initDashboard();\n");
        html.append("    }\n");
        html.append("\n");
        html.append("    function initDashboard() {\n");
        html.append("      console.log('[CansteinPlot] Initializing dashboard');\n");

        // ...existing code...
        html.append("      const state = {\n");
        html.append("        mapSize: 31,\n");
        html.append("        half: 25,\n");
        html.append("        tileSize: 48,\n");
        html.append("        zoom: 1,\n");
        html.append("        offsetX: 0,\n");
        html.append("        offsetY: 0,\n");
        html.append("        isPanning: false,\n");
        html.append("        panStartX: 0,\n");
        html.append("        panStartY: 0,\n");
        html.append("        selectedPlayers: new Set(),\n");
        html.append("        currentPlayerList: new Set(),\n");
        html.append("        mapData: {},\n");
        html.append("        hoverTooltip: null\n");
        html.append("      };\n");
        html.append("\n");
        html.append("      const mapCont = document.getElementById('map-container');\n");
        html.append("      const canvas = document.createElement('canvas');\n");
        html.append("      canvas.id = 'map-canvas';\n");
        html.append("      canvas.style.position = 'absolute';\n");
        html.append("      canvas.style.top = '0';\n");
        html.append("      canvas.style.left = '0';\n");
        html.append("      mapCont.appendChild(canvas);\n");
        html.append("      const ctx = canvas.getContext('2d');\n");
        html.append("\n");
        html.append("      function resizeCanvas() {\n");
        html.append("        const r = mapCont.getBoundingClientRect();\n");
        html.append("        canvas.width = r.width;\n");
        html.append("        canvas.height = r.height;\n");
        html.append("        drawMap();\n");
        html.append("      }\n");
        html.append("\n");
        html.append("      function worldToScreen(wx, wy) {\n");
        html.append("        const size = state.tileSize * state.zoom;\n");
        html.append("        const sx = (wx * size) + canvas.width / 2 + state.offsetX;\n");
        html.append("        const sy = (wy * size) + canvas.height / 2 + state.offsetY;\n");
        html.append("        return { x: sx, y: sy };\n");
        html.append("      }\n");
        html.append("\n");
        html.append("      function screenToWorld(sx, sy) {\n");
        html.append("        const size = state.tileSize * state.zoom;\n");
        html.append("        const wx = (sx - canvas.width / 2 - state.offsetX) / size;\n");
        html.append("        const wy = (sy - canvas.height / 2 - state.offsetY) / size;\n");
        html.append("        return { x: Math.floor(wx + 0.5), y: Math.floor(wy + 0.5) };\n");
        html.append("      }\n");
        html.append("\n");
        html.append("      function tileImagePath(x, y) {\n");
        html.append("        return TILE_BASE_URL + x + '-' + y + '.jpg';\n");
        html.append("      }\n");
        html.append("\n");
        html.append("      function drawMap() {\n");
        html.append("        ctx.clearRect(0, 0, canvas.width, canvas.height);\n");
        html.append("        const size = state.tileSize * state.zoom;\n");
        html.append("        const leftWorld = Math.floor((-canvas.width / 2 - state.offsetX) / size) - 1;\n");
        html.append("        const rightWorld = Math.floor((canvas.width / 2 - state.offsetX) / size) + 1;\n");
        html.append("        const topWorld = Math.floor((-canvas.height / 2 - state.offsetY) / size) - 1;\n");
        html.append("        const bottomWorld = Math.floor((canvas.height / 2 - state.offsetY) / size) + 1;\n");
        html.append("        const minX = Math.max(-state.half, leftWorld);\n");
        html.append("        const maxX = Math.min(state.half, rightWorld);\n");
        html.append("        const minY = Math.max(-state.half, topWorld);\n");
        html.append("        const maxY = Math.min(state.half, bottomWorld);\n");
        html.append("        for (let wx = minX; wx <= maxX; wx++) {\n");
        html.append("          for (let wy = minY; wy <= maxY; wy++) {\n");
        html.append("            const s = worldToScreen(wx, wy);\n");
        html.append("            const xPix = s.x - size / 2;\n");
        html.append("            const yPix = s.y - size / 2;\n");
        html.append("            ctx.fillStyle = (wx === 0 && wy === 0) ? 'rgba(255,68,68,0.6)' : 'rgba(40,40,40,1)';\n");
        html.append("            ctx.fillRect(xPix, yPix, size, size);\n");
        html.append("            ctx.strokeStyle = 'rgba(0,0,0,0.4)';\n");
        html.append("            ctx.strokeRect(xPix + 0.5, yPix + 0.5, size - 1, size - 1);\n");
        html.append("            const img = new Image();\n");
        html.append("            img.src = tileImagePath(wx, wy);\n");
        html.append("            img.onload = () => ctx.drawImage(img, xPix, yPix, size, size);\n");
        html.append("          }\n");
        html.append("        }\n");
        html.append("      }\n");
        html.append("\n");
        html.append("      function showHoverTooltip(x, y, clientX, clientY) {\n");
        html.append("        if (!state.hoverTooltip) {\n");
        html.append("          const div = document.createElement('div');\n");
        html.append("          div.className = 'plot-tooltip';\n");
        html.append("          mapCont.appendChild(div);\n");
        html.append("          state.hoverTooltip = div;\n");
        html.append("        }\n");
        html.append("        state.hoverTooltip.textContent = 'Plot: ' + x + ';' + y;\n");
        html.append("        const rect = mapCont.getBoundingClientRect();\n");
        html.append("        state.hoverTooltip.style.left = (clientX - rect.left + 10) + 'px';\n");
        html.append("        state.hoverTooltip.style.top = (clientY - rect.top + 10) + 'px';\n");
        html.append("        state.hoverTooltip.style.display = 'block';\n");
        html.append("      }\n");
        html.append("\n");
        html.append("      function hideHoverTooltip() {\n");
        html.append("        if (state.hoverTooltip) state.hoverTooltip.style.display = 'none';\n");
        html.append("      }\n");
        html.append("\n");
        html.append("      function sendPlotCaptureRequest(x, y) {\n");
        html.append("        makeRequest('/api/plot-capture', {\n");
        html.append("          method: 'POST',\n");
        html.append("          headers: { 'Content-Type': 'application/json' },\n");
        html.append("          body: JSON.stringify({ x: x, y: y })\n");
        html.append("        }).catch(e => console.error(e));\n");
        html.append("      }\n");
        html.append("\n");
        html.append("      mapCont.addEventListener('mousedown', (e) => {\n");
        html.append("        if (e.button !== 0) return;\n");
        html.append("        state.isPanning = true;\n");
        html.append("        state.panStartX = e.clientX - state.offsetX;\n");
        html.append("        state.panStartY = e.clientY - state.offsetY;\n");
        html.append("      });\n");
        html.append("\n");
        html.append("      mapCont.addEventListener('mousemove', (e) => {\n");
        html.append("        const rect = canvas.getBoundingClientRect();\n");
        html.append("        const mouseX = e.clientX - rect.left;\n");
        html.append("        const mouseY = e.clientY - rect.top;\n");
        html.append("        if (state.isPanning) {\n");
        html.append("          state.offsetX = e.clientX - state.panStartX;\n");
        html.append("          state.offsetY = e.clientY - state.panStartY;\n");
        html.append("          drawMap();\n");
        html.append("          hideHoverTooltip();\n");
        html.append("        } else {\n");
        html.append("          const w = screenToWorld(mouseX, mouseY);\n");
        html.append("          if (Math.abs(w.x) <= state.half && Math.abs(w.y) <= state.half) {\n");
        html.append("            showHoverTooltip(w.x, w.y, e.clientX, e.clientY);\n");
        html.append("          } else {\n");
        html.append("            hideHoverTooltip();\n");
        html.append("          }\n");
        html.append("        }\n");
        html.append("      });\n");
        html.append("\n");
        html.append("      mapCont.addEventListener('mouseup', () => { state.isPanning = false; });\n");
        html.append("      mapCont.addEventListener('mouseleave', () => {\n");
        html.append("        state.isPanning = false;\n");
        html.append("        hideHoverTooltip();\n");
        html.append("      });\n");
        html.append("\n");
        html.append("      mapCont.addEventListener('wheel', (e) => {\n");
        html.append("        e.preventDefault();\n");
        html.append("        const rect = canvas.getBoundingClientRect();\n");
        html.append("        const mouseX = e.clientX - rect.left;\n");
        html.append("        const mouseY = e.clientY - rect.top;\n");
        html.append("        const before = screenToWorld(mouseX, mouseY);\n");
        html.append("        const delta = e.deltaY < 0 ? 1.1 : 0.9;\n");
        html.append("        state.zoom = Math.min(5, Math.max(0.2, state.zoom * delta));\n");
        html.append("        const after = screenToWorld(mouseX, mouseY);\n");
        html.append("        const size = state.tileSize * state.zoom;\n");
        html.append("        state.offsetX += (after.x - before.x) * size;\n");
        html.append("        state.offsetY += (after.y - before.y) * size;\n");
        html.append("        drawMap();\n");
        html.append("      }, { passive: false });\n");
        html.append("\n");
        html.append("      mapCont.addEventListener('click', (e) => {\n");
        html.append("        if (state.isPanning) return;\n");
        html.append("        const rect = canvas.getBoundingClientRect();\n");
        html.append("        const mouseX = e.clientX - rect.left;\n");
        html.append("        const mouseY = e.clientY - rect.top;\n");
        html.append("        const w = screenToWorld(mouseX, mouseY);\n");
        html.append("        if (Math.abs(w.x) <= state.half && Math.abs(w.y) <= state.half) {\n");
        html.append("          sendPlotCaptureRequest(w.x, w.y);\n");
        html.append("        }\n");
        html.append("      });\n");
        html.append("\n");
        html.append("      async function updateData() {\n");
        html.append("        try {\n");
        html.append("          const r = await makeRequest('/api/data');\n");
        html.append("          const d = await r.json();\n");
        html.append("          state.mapData = d;\n");
        html.append("          const newPlayerSet = new Set(d.players.map(p => p.name));\n");
        html.append("          state.selectedPlayers.forEach(p => {\n");
        html.append("            if (!newPlayerSet.has(p)) state.selectedPlayers.delete(p);\n");
        html.append("          });\n");
        html.append("          state.currentPlayerList = newPlayerSet;\n");
        html.append("          updatePlayers(d.players);\n");
        html.append("          updateChat(d.messages);\n");
        html.append("        } catch (e) {\n");
        html.append("          console.error('[CansteinPlot] Error updating data:', e);\n");
        html.append("        }\n");
        html.append("      }\n");
        html.append("\n");
        html.append("      function updatePlayers(players) {\n");
        html.append("        const list = document.getElementById('players-list');\n");
        html.append("        const current = new Set(Array.from(list.children).map(el => el.textContent));\n");
        html.append("        const playerSet = new Set(players.map(p => p.name));\n");
        html.append("        for (let p of current) {\n");
        html.append("          if (!playerSet.has(p)) {\n");
        html.append("            const el = Array.from(list.children).find(el => el.textContent === p);\n");
        html.append("            if (el) el.remove();\n");
        html.append("          }\n");
        html.append("        }\n");
        html.append("        for (let p of players) {\n");
        html.append("          let el = Array.from(list.children).find(el => el.textContent === p.name);\n");
        html.append("          if (!el) {\n");
        html.append("            el = document.createElement('div');\n");
        html.append("            el.className = 'player-item';\n");
        html.append("            el.textContent = p.name;\n");
        html.append("            el.onclick = () => togglePlayer(p.name);\n");
        html.append("            list.appendChild(el);\n");
        html.append("          }\n");
        html.append("          if (state.selectedPlayers.has(p.name)) {\n");
        html.append("            el.classList.add('selected');\n");
        html.append("          } else {\n");
        html.append("            el.classList.remove('selected');\n");
        html.append("          }\n");
        html.append("        }\n");
        html.append("      }\n");
        html.append("\n");
         html.append("      function minecraftCodesToHtml(text) {\n");
         html.append("        const colorMap = {\n");
         html.append("          '0': '#000000', '1': '#0000AA', '2': '#00AA00', '3': '#00AAAA',\n");
         html.append("          '4': '#AA0000', '5': '#AA00AA', '6': '#FFAA00', '7': '#AAAAAA',\n");
         html.append("          '8': '#555555', '9': '#5555FF', 'a': '#55FF55', 'b': '#55FFFF',\n");
         html.append("          'c': '#FF5555', 'd': '#FF55FF', 'e': '#FFFF55', 'f': '#FFFFFF'\n");
         html.append("        };\n");
         html.append("        let result = '';\n");
         html.append("        let i = 0;\n");
         html.append("        while (i < text.length) {\n");
         html.append("          if (text[i] === '§' && i < text.length - 1) {\n");
         html.append("            const code = text[i + 1];\n");
         html.append("            if (code === 'r') {\n");
         html.append("              result += '</span>';\n");
         html.append("            } else if (code === 'l') {\n");
         html.append("              result += '<span style=\"font-weight: bold;\">';\n");
         html.append("            } else if (code === 'o') {\n");
         html.append("              result += '<span style=\"font-style: italic;\">';\n");
         html.append("            } else if (code === 'n') {\n");
         html.append("              result += '<span style=\"text-decoration: underline;\">';\n");
         html.append("            } else if (colorMap[code]) {\n");
         html.append("              result += '<span style=\"color: ' + colorMap[code] + ';\">';\n");
         html.append("            } else {\n");
         html.append("              result += '§' + code;\n");
         html.append("            }\n");
         html.append("            i += 2;\n");
         html.append("          } else {\n");
         html.append("            const char = text[i];\n");
         html.append("            if (char === '<') result += '&lt;';\n");
         html.append("            else if (char === '>') result += '&gt;';\n");
         html.append("            else if (char === '&') result += '&amp;';\n");
         html.append("            else result += char;\n");
         html.append("            i++;\n");
         html.append("          }\n");
         html.append("        }\n");
         html.append("        return result;\n");
         html.append("      }\n");
         html.append("\n");
         html.append("      function updateChat(messages) {\n");
         html.append("        const list = document.getElementById('chat-list');\n");
         html.append("        list.innerHTML = '';\n");
         html.append("        for (let msg of messages) {\n");
         html.append("          let text = msg\n");
         html.append("            .replace(/^\\[?\\d{1,2}:\\d{2}(?::\\d{2})?\\]?\\s*-?\\s*/, '')\n");
         html.append("            .replace(/^\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}(?::\\d{2})?\\s*-?\\s*/, '');\n");
         html.append("          const div = document.createElement('div');\n");
         html.append("          div.className = 'message-item';\n");
         html.append("          div.innerHTML = minecraftCodesToHtml(text);\n");
         html.append("          list.appendChild(div);\n");
         html.append("        }\n");
         html.append("        list.scrollTop = list.scrollHeight;\n");
         html.append("      }\n");
        html.append("\n");
        html.append("      function togglePlayer(name) {\n");
        html.append("        if (state.selectedPlayers.has(name)) {\n");
        html.append("          state.selectedPlayers.delete(name);\n");
        html.append("        } else {\n");
        html.append("          state.selectedPlayers.add(name);\n");
        html.append("        }\n");
        html.append("        updatePlayers(Array.from(state.currentPlayerList).map(p => ({ name: p })));\n");
        html.append("        updatePlayerActions();\n");
        html.append("      }\n");
        html.append("\n");
         html.append("      function updatePlayerActions() {\n");
         html.append("        const actionsDiv = document.getElementById('player-actions');\n");
         html.append("        if (state.selectedPlayers.size === 0) {\n");
         html.append("          actionsDiv.innerHTML = '<div style=\"text-align: center; color: #888; padding: 10px;\">Spieler auswählen</div>';\n");
         html.append("          return;\n");
         html.append("        }\n");
         html.append("        actionsDiv.innerHTML = '';\n");
         html.append("        const players = Array.from(state.selectedPlayers);\n");
         html.append("\n");
         html.append("        const btn1 = document.createElement('button');\n");
         html.append("        btn1.className = 'action-btn';\n");
         html.append("        btn1.textContent = 'Neuer Plot (zusammen)';\n");
         html.append("        btn1.onclick = () => createPlot(true);\n");
         html.append("        actionsDiv.appendChild(btn1);\n");
         html.append("\n");
         html.append("        const btn2 = document.createElement('button');\n");
         html.append("        btn2.className = 'action-btn';\n");
         html.append("        btn2.textContent = 'Neuer Plot (alleine)';\n");
         html.append("        btn2.onclick = () => createPlot(false);\n");
         html.append("        actionsDiv.appendChild(btn2);\n");
         html.append("\n");
         html.append("        const btn3Div = document.createElement('div');\n");
         html.append("        btn3Div.style.display = 'flex';\n");
         html.append("        btn3Div.style.gap = '3px';\n");
         html.append("        btn3Div.style.marginTop = '3px';\n");
         html.append("        const btn3Label = document.createElement('span');\n");
         html.append("        btn3Label.textContent = 'Zu';\n");
         html.append("        btn3Label.style.padding = '8px';\n");
         html.append("        btn3Label.style.fontSize = '11px';\n");
         html.append("        btn3Label.style.whiteSpace = 'nowrap';\n");
         html.append("        btn3Label.style.display = 'flex';\n");
         html.append("        btn3Label.style.alignItems = 'center';\n");
         html.append("        const btn3Select = document.createElement('select');\n");
         html.append("        btn3Select.style.flex = '1';\n");
         html.append("        btn3Select.style.padding = '8px';\n");
         html.append("        btn3Select.style.fontSize = '11px';\n");
         html.append("        btn3Select.style.background = '#1e1e1e';\n");
         html.append("        btn3Select.style.color = '#fff';\n");
         html.append("        btn3Select.style.border = '1px solid #404040';\n");
         html.append("        btn3Select.style.borderRadius = '3px';\n");
         html.append("        const option0 = document.createElement('option');\n");
         html.append("        option0.textContent = 'Spieler wählen...';\n");
         html.append("        option0.value = '';\n");
         html.append("        btn3Select.appendChild(option0);\n");
         html.append("        for (let p of state.mapData.players) {\n");
         html.append("          if (!state.selectedPlayers.has(p.name)) {\n");
         html.append("            const opt = document.createElement('option');\n");
         html.append("            opt.textContent = p.name;\n");
         html.append("            opt.value = p.name;\n");
         html.append("            btn3Select.appendChild(opt);\n");
         html.append("          }\n");
         html.append("        }\n");
         html.append("        const btn3Button = document.createElement('button');\n");
         html.append("        btn3Button.className = 'action-btn';\n");
         html.append("        btn3Button.textContent = 'teleportieren und trusten';\n");
         html.append("        btn3Button.style.flex = '1';\n");
         html.append("        btn3Button.onclick = () => {\n");
         html.append("          if (btn3Select.value) {\n");
         html.append("            teleportAndTrust(btn3Select.value);\n");
         html.append("          }\n");
         html.append("        };\n");
         html.append("        btn3Div.appendChild(btn3Label);\n");
         html.append("        btn3Div.appendChild(btn3Select);\n");
         html.append("        btn3Div.appendChild(btn3Button);\n");
         html.append("        actionsDiv.appendChild(btn3Div);\n");
         html.append("\n");
         html.append("        const btn4Div = document.createElement('div');\n");
         html.append("        btn4Div.style.marginTop = '3px';\n");
         html.append("        const btn4Label = document.createElement('div');\n");
         html.append("        btn4Label.textContent = 'Zu Plot teleportieren und trusten:';\n");
         html.append("        btn4Label.style.fontSize = '11px';\n");
         html.append("        btn4Label.style.marginBottom = '3px';\n");
         html.append("        btn4Div.appendChild(btn4Label);\n");
         html.append("        const btn4InputDiv = document.createElement('div');\n");
         html.append("        btn4InputDiv.style.display = 'flex';\n");
         html.append("        btn4InputDiv.style.gap = '3px';\n");
         html.append("        const btn4InputX = document.createElement('input');\n");
         html.append("        btn4InputX.type = 'number';\n");
         html.append("        btn4InputX.placeholder = 'X';\n");
         html.append("        btn4InputX.style.flex = '1';\n");
         html.append("        btn4InputX.style.padding = '6px';\n");
         html.append("        btn4InputX.style.fontSize = '11px';\n");
         html.append("        btn4InputX.style.background = '#1e1e1e';\n");
         html.append("        btn4InputX.style.color = '#fff';\n");
         html.append("        btn4InputX.style.border = '1px solid #404040';\n");
         html.append("        btn4InputX.style.borderRadius = '3px';\n");
         html.append("        const btn4InputY = document.createElement('input');\n");
         html.append("        btn4InputY.type = 'number';\n");
         html.append("        btn4InputY.placeholder = 'Y';\n");
         html.append("        btn4InputY.style.flex = '1';\n");
         html.append("        btn4InputY.style.padding = '6px';\n");
         html.append("        btn4InputY.style.fontSize = '11px';\n");
         html.append("        btn4InputY.style.background = '#1e1e1e';\n");
         html.append("        btn4InputY.style.color = '#fff';\n");
         html.append("        btn4InputY.style.border = '1px solid #404040';\n");
         html.append("        btn4InputY.style.borderRadius = '3px';\n");
         html.append("        const btn4Button = document.createElement('button');\n");
         html.append("        btn4Button.className = 'action-btn';\n");
         html.append("        btn4Button.textContent = 'Los';\n");
         html.append("        btn4Button.style.flex = '0.5';\n");
         html.append("        btn4Button.onclick = () => {\n");
         html.append("          const x = parseInt(btn4InputX.value);\n");
         html.append("          const y = parseInt(btn4InputY.value);\n");
         html.append("          if (!isNaN(x) && !isNaN(y)) {\n");
         html.append("            teleportToPlotAndTrust(x, y);\n");
         html.append("          } else {\n");
         html.append("            alert('Bitte gültige Koordinaten eingeben');\n");
         html.append("          }\n");
         html.append("        };\n");
         html.append("        btn4InputDiv.appendChild(btn4InputX);\n");
         html.append("        btn4InputDiv.appendChild(btn4InputY);\n");
         html.append("        btn4InputDiv.appendChild(btn4Button);\n");
         html.append("        btn4Div.appendChild(btn4InputDiv);\n");
         html.append("        actionsDiv.appendChild(btn4Div);\n");
         html.append("      }\n");
        html.append("\n");
         html.append("      function createPlot(together) {\n");
         html.append("        const players = Array.from(state.selectedPlayers);\n");
         html.append("        makeRequest('/api/plot-action', {\n");
         html.append("          method: 'POST',\n");
         html.append("          headers: { 'Content-Type': 'application/json' },\n");
         html.append("          body: JSON.stringify({\n");
         html.append("            action: together ? 'createPlotTogether' : 'createPlotIndividual',\n");
         html.append("            players: players\n");
         html.append("          })\n");
         html.append("        }).catch(e => console.error(e));\n");
         html.append("        state.selectedPlayers.clear();\n");
         html.append("        updatePlayers(Array.from(state.currentPlayerList).map(p => ({ name: p })));\n");
         html.append("        updatePlayerActions();\n");
         html.append("      }\n");
         html.append("\n");
         html.append("      function teleportAndTrust(targetPlayer) {\n");
         html.append("        const players = Array.from(state.selectedPlayers);\n");
         html.append("        makeRequest('/api/plot-action', {\n");
         html.append("          method: 'POST',\n");
         html.append("          headers: { 'Content-Type': 'application/json' },\n");
         html.append("          body: JSON.stringify({\n");
         html.append("            action: 'teleportAndTrust',\n");
         html.append("            targetPlayer: targetPlayer,\n");
         html.append("            players: players\n");
         html.append("          })\n");
         html.append("        }).catch(e => console.error(e));\n");
         html.append("        state.selectedPlayers.clear();\n");
         html.append("        updatePlayers(Array.from(state.currentPlayerList).map(p => ({ name: p })));\n");
         html.append("        updatePlayerActions();\n");
         html.append("      }\n");
         html.append("\n");
         html.append("      function teleportToPlotAndTrust(x, y) {\n");
         html.append("        const players = Array.from(state.selectedPlayers);\n");
         html.append("        makeRequest('/api/plot-action', {\n");
         html.append("          method: 'POST',\n");
         html.append("          headers: { 'Content-Type': 'application/json' },\n");
         html.append("          body: JSON.stringify({\n");
         html.append("            action: 'teleportToPlotAndTrust',\n");
         html.append("            plotX: x,\n");
         html.append("            plotY: y,\n");
         html.append("            players: players\n");
         html.append("          })\n");
         html.append("        }).catch(e => console.error(e));\n");
         html.append("        state.selectedPlayers.clear();\n");
         html.append("        updatePlayers(Array.from(state.currentPlayerList).map(p => ({ name: p })));\n");
         html.append("        updatePlayerActions();\n");
         html.append("      }\n");
         html.append("\n");
         html.append("      resizeCanvas();\n");
        html.append("      window.addEventListener('resize', resizeCanvas);\n");
        html.append("      updateData();\n");
        html.append("      setInterval(updateData, 1000);\n");
        html.append("      drawMap();\n");
        html.append("    }\n");
        html.append("\n");
        html.append("    document.addEventListener('DOMContentLoaded', () => {\n");
        html.append("      console.log('[CansteinPlot] DOM loaded, setting up event listeners');\n");
        html.append("      const loginBtn = document.getElementById('login-btn');\n");
        html.append("      const passwordInput = document.getElementById('password-input');\n");
        html.append("      \n");
        html.append("      if (loginBtn) {\n");
        html.append("        loginBtn.addEventListener('click', handleLogin);\n");
        html.append("        console.log('[CansteinPlot] Login button listener attached');\n");
        html.append("      }\n");
        html.append("      \n");
        html.append("      if (passwordInput) {\n");
        html.append("        passwordInput.addEventListener('keypress', (e) => {\n");
        html.append("          if (e.key === 'Enter') handleLogin();\n");
        html.append("        });\n");
        html.append("        console.log('[CansteinPlot] Password input listener attached');\n");
        html.append("      }\n");
        html.append("    });\n");
        html.append("  </script>\n");
        html.append("</body>\n");
        html.append("</html>\n");
        return html.toString();
    }
}

