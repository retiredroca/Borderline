package com.retiredroca.borderline.platform;

import java.nio.file.Path;

/** Loader-specific services used by the shared (common) code. */
public interface BorderlinePlatform {
    String loaderName();

    boolean isDevelopmentEnvironment();

    /** The loader's config directory (e.g. {@code config/}). */
    Path configDirectory();
}
