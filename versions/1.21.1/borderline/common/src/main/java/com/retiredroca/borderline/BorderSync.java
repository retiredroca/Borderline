package com.retiredroca.borderline;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.border.BorderChangeListener;
import net.minecraft.world.level.border.WorldBorder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The world-border logic. The overworld border is the source of truth; the Nether and End borders
 * are derived from it using the scales in {@link BorderlineConfig}.
 *
 * <p>Nether and End borders are not persisted by vanilla (they use {@code DerivedLevelData}, whose
 * {@code setWorldBorder} is a no-op), so derived values are re-applied on every server start and on
 * demand via the command.
 */
public final class BorderSync {
    private static final double EPSILON = 1.0E-4D;

    private BorderSync() {}

    /** Server-start hook: apply the saved world size (if any) and derive the other dimensions. */
    public static void onServerStarted(MinecraftServer server) {
        BorderlineConfig config = BorderlineConfig.get();
        if (!config.enabled) {
            Borderline.LOGGER.info("Borderline is disabled in the config; leaving world borders untouched.");
            return;
        }
        WorldBorder overworld = server.overworld().getWorldBorder();
        if (config.worldSize != null) {
            applyBorder(overworld, config.worldSize, 0L);
            Borderline.LOGGER.info("Applied saved world size {} to the overworld.", config.worldSize);
        }
        // Vanilla registers a DelegateBorderChangeListener on the overworld border that mirrors every
        // change onto the other dimensions 1:1. Our listener is added afterwards (at SERVER_STARTED,
        // after level creation), so it runs last and overwrites that mirror with the scaled values.
        overworld.addListener(new OverworldBorderListener(server));
        derive(server, overworld, config);
    }

    /** Set the overworld world size (diameter) and derive the other dimensions. */
    public static void set(MinecraftServer server, double diameter, long millis) {
        BorderlineConfig config = BorderlineConfig.get();
        WorldBorder overworld = server.overworld().getWorldBorder();
        applyBorder(overworld, diameter, millis);
        config.worldSize = diameter;
        config.save();
        derive(server, overworld, config);
    }

    /** Re-derive from the live overworld border and remember its size. */
    public static void sync(MinecraftServer server) {
        BorderlineConfig config = BorderlineConfig.get();
        WorldBorder overworld = server.overworld().getWorldBorder();
        config.worldSize = overworld.getLerpTarget();
        config.save();
        derive(server, overworld, config);
    }

    private static void derive(MinecraftServer server, WorldBorder overworld, BorderlineConfig config) {
        if (config.worldSize == null && config.skipWhenUnbounded && isUnbounded(overworld.getSize())) {
            Borderline.LOGGER.info("Overworld border is unbounded and no world size is saved; leaving Nether/End untouched.");
            return;
        }
        applyTo(server, Level.NETHER, overworld, config.nether);
        applyTo(server, Level.END, overworld, config.end);
    }

    private static void applyTo(MinecraftServer server, ResourceKey<Level> key, WorldBorder overworld,
                                BorderlineConfig.Dimension dim) {
        if (dim == null || !dim.enabled) {
            return;
        }
        ServerLevel level = server.getLevel(key);
        if (level == null) {
            return;
        }
        WorldBorder target = level.getWorldBorder();
        double desiredSize = overworld.getLerpTarget() * dim.sizeScale;
        if (Math.abs(target.getLerpTarget() - desiredSize) > EPSILON) {
            long millis = overworld.getLerpRemainingTime();
            if (millis > 0L) {
                target.lerpSizeBetween(target.getSize(), desiredSize, millis);
            } else {
                target.setSize(desiredSize);
            }
        }
        double centerX = overworld.getCenterX() * dim.centerScale;
        double centerZ = overworld.getCenterZ() * dim.centerScale;
        if (Math.abs(target.getCenterX() - centerX) > EPSILON
                || Math.abs(target.getCenterZ() - centerZ) > EPSILON) {
            target.setCenter(centerX, centerZ);
        }
    }

    private static void applyBorder(WorldBorder border, double size, long millis) {
        if (millis > 0L) {
            border.lerpSizeBetween(border.getSize(), size, millis);
        } else {
            border.setSize(size);
        }
    }

    private static boolean isUnbounded(double size) {
        return size >= WorldBorder.MAX_SIZE - 1.0D;
    }

    /** Human-readable summary of the current world borders, one line per dimension. */
    public static List<String> describe(MinecraftServer server) {
        BorderlineConfig config = BorderlineConfig.get();
        List<String> lines = new ArrayList<>();
        StringBuilder head = new StringBuilder("enabled=").append(config.enabled);
        if (config.worldSize != null) {
            head.append(", saved worldSize=").append(fmt(config.worldSize));
        }
        lines.add(head.toString());
        lines.add(describe("overworld", server.overworld().getWorldBorder()));
        ServerLevel nether = server.getLevel(Level.NETHER);
        if (nether != null) {
            lines.add(describe("the_nether", nether.getWorldBorder()));
        }
        ServerLevel end = server.getLevel(Level.END);
        if (end != null) {
            lines.add(describe("the_end", end.getWorldBorder()));
        }
        return lines;
    }

    private static String describe(String name, WorldBorder border) {
        return name + ": size=" + fmt(border.getSize())
                + ", center=(" + fmt(border.getCenterX()) + ", " + fmt(border.getCenterZ()) + ")";
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    /**
     * Re-derives the Nether/End borders whenever the overworld border changes, so a vanilla
     * {@code /worldborder} change (which vanilla mirrors 1:1) is immediately replaced with the scaled
     * values.
     */
    private static final class OverworldBorderListener implements BorderChangeListener {
        private final MinecraftServer server;

        private OverworldBorderListener(MinecraftServer server) {
            this.server = server;
        }

        @Override
        public void onBorderSizeSet(WorldBorder border, double size) {
            reDerive();
        }

        @Override
        public void onBorderSizeLerping(WorldBorder border, double from, double to, long time) {
            reDerive();
        }

        @Override
        public void onBorderCenterSet(WorldBorder border, double x, double z) {
            reDerive();
        }

        @Override
        public void onBorderSetWarningTime(WorldBorder border, int time) {}

        @Override
        public void onBorderSetWarningBlocks(WorldBorder border, int blocks) {}

        @Override
        public void onBorderSetDamagePerBlock(WorldBorder border, double damage) {}

        @Override
        public void onBorderSetDamageSafeZOne(WorldBorder border, double safeZone) {}

        private void reDerive() {
            BorderlineConfig config = BorderlineConfig.get();
            if (!config.enabled) {
                return;
            }
            derive(server, server.overworld().getWorldBorder(), config);
        }
    }
}
