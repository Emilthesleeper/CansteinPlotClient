package com.emilsleeper.cansteinplotclient.client;

import com.emilsleeper.cansteinplotclient.events.PlayerEventListener;
import com.emilsleeper.cansteinplotclient.plot.PlotCreationHandler;
import com.emilsleeper.cansteinplotclient.webserver.WebServer;
import net.fabricmc.api.ClientModInitializer;

public class CansteinplotclientClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        PlayerEventListener.register();
        PlotCreationHandler.register();
        WebServer.addPlotListener((players, together) -> PlotCreationHandler.createPlotWithPlayers(players, together));
        WebServer.addTeleportActionListener((target, players) -> PlotCreationHandler.teleportAndTrust(target, players));
        WebServer.addTeleportToPlotActionListener((x, y, players) -> PlotCreationHandler.teleportToPlotAndTrust(x, y, players));
        WebServer.addPlotCaptureListener((x, y) -> PlotCreationHandler.queryPlotInfo(x, y));
    }
}
