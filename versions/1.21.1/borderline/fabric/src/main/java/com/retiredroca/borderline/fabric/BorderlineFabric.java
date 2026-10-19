package com.retiredroca.borderline.fabric;

import com.retiredroca.borderline.BorderSync;
import com.retiredroca.borderline.Borderline;
import com.retiredroca.borderline.command.BorderlineCommand;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

public class BorderlineFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        Borderline.setPlatform(new FabricPlatform());
        ServerLifecycleEvents.SERVER_STARTED.register(BorderSync::onServerStarted);
        ServerTickEvents.END_SERVER_TICK.register(BorderSync::syncClients);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                BorderlineCommand.register(dispatcher));
    }
}
