package com.retiredroca.borderline;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Borderline configuration, stored as JSON in the loader's config directory. Loaded lazily on first
 * use; defaults are written out when the file does not exist yet.
 */
public final class BorderlineConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "borderline.json";

    private static BorderlineConfig instance;

    /** Master switch for the whole mod. */
    public boolean enabled = true;

    /**
     * When no {@link #worldSize} is saved, skip deriving Nether/End if the overworld border is at the
     * vanilla default (unbounded, {@code WorldBorder.MAX_SIZE}).
     */
    public boolean skipWhenUnbounded = true;

    /** Last requested overworld world size in blocks (diameter), or null if never set. */
    public Double worldSize = null;

    public Dimension nether = new Dimension(true, 0.125D, 1.0D);
    public Dimension end = new Dimension(true, 0.5D, 1.0D);

    public static final class Dimension {
        public boolean enabled;
        public double sizeScale;

        /**
         * Multiplier applied to the overworld center in overworld coordinates. Vanilla applies the
         * dimension's own coordinate scale when the border is read (the Nether divides by 8), so 1.0
         * matches the overworld center in every dimension.
         */
        public double centerScale;

        public Dimension() {}

        public Dimension(boolean enabled, double sizeScale, double centerScale) {
            this.enabled = enabled;
            this.sizeScale = sizeScale;
            this.centerScale = centerScale;
        }
    }

    public static BorderlineConfig get() {
        BorderlineConfig config = instance;
        if (config == null) {
            config = load();
            instance = config;
        }
        return config;
    }

    private static Path configFile() {
        return Borderline.platform().configDirectory().resolve(FILE_NAME);
    }

    private static BorderlineConfig load() {
        Path file = configFile();
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                BorderlineConfig config = GSON.fromJson(reader, BorderlineConfig.class);
                if (config != null) {
                    return config.normalized();
                }
            } catch (IOException | JsonParseException e) {
                Borderline.LOGGER.error("Failed to read {} - using defaults", file, e);
            }
        }
        BorderlineConfig config = new BorderlineConfig();
        config.save();
        return config;
    }

    public void save() {
        Path file = configFile();
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            Borderline.LOGGER.error("Failed to write {}", file, e);
        }
    }

    private BorderlineConfig normalized() {
        if (nether == null) {
            nether = new Dimension(true, 0.125D, 1.0D);
        }
        if (end == null) {
            end = new Dimension(true, 0.5D, 1.0D);
        }
        return this;
    }
}
