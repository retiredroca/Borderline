package com.retiredroca.borderline.neoforge;

import com.retiredroca.borderline.platform.BorderlinePlatform;

import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;

public class NeoForgePlatform implements BorderlinePlatform {
    @Override
    public String loaderName() {
        return "NeoForge";
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return !FMLEnvironment.isProduction();
    }

    @Override
    public Path configDirectory() {
        return FMLPaths.CONFIGDIR.get();
    }
}
