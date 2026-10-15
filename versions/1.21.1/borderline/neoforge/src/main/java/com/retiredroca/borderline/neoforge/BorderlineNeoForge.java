package com.retiredroca.borderline.neoforge;

import com.retiredroca.borderline.BorderSync;
import com.retiredroca.borderline.Borderline;
import com.retiredroca.borderline.command.BorderlineCommand;

import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

@Mod(Borderline.MOD_ID)
public class BorderlineNeoForge {
    public BorderlineNeoForge() {
        Borderline.setPlatform(new NeoForgePlatform());
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) -> BorderSync.onServerStarted(event.getServer()));
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> BorderlineCommand.register(event.getDispatcher()));
    }
}
