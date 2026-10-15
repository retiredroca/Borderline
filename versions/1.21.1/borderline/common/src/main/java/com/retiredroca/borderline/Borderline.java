package com.retiredroca.borderline;

import com.retiredroca.borderline.platform.BorderlinePlatform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loader-neutral entry point. Each loader's entrypoint installs its own {@link BorderlinePlatform}
 * implementation; the shared code then uses {@link #platform()} for loader-specific operations.
 */
public final class Borderline {
    public static final String MOD_ID = "borderline";
    public static final Logger LOGGER = LoggerFactory.getLogger("Borderline");

    private static BorderlinePlatform platform;

    private Borderline() {}

    public static void setPlatform(BorderlinePlatform platform) {
        Borderline.platform = platform;
    }

    public static BorderlinePlatform platform() {
        if (platform == null) {
            throw new IllegalStateException("Borderline platform not set - was a loader entrypoint loaded?");
        }
        return platform;
    }
}
