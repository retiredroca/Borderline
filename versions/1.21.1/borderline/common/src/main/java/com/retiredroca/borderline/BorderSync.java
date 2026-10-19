package com.retiredroca.borderline;

import net.minecraft.network.protocol.game.ClientboundSetBorderCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderLerpSizePacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderSizePacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.border.BorderChangeListener;
import net.minecraft.world.level.border.WorldBorder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The world-border logic. The overworld border is the source of truth; the Nether and End borders
 * are derived from it using the scales in {@link BorderlineConfig}.
 *
 * <p>Nether and End borders are not persisted by vanilla (they use {@code DerivedLevelData}, whose
 * {@code setWorldBorder} is a no-op), so derived values are re-applied on every server start and on
 * demand via the command.
 *
 * <p>Vanilla only ever sends the overworld border to clients (login, respawn and dimension changes
 * all use {@code server.overworld().getWorldBorder()}), so {@link #syncClients} pushes each player
 * the border of the dimension they are actually in using vanilla packets. That keeps unmodded
 * clients correct without a client-side mod.
 */
public final class BorderSync {
    private static final double EPSILON = 1.0E-4D;

    /** Per-player border last sent to the client, so packets go out only on change. */
    private static final Map<UUID, ClientBorder> sentBorders = new HashMap<>();

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
        // Always store the center: the border has no getter for the raw (pre-coordinate-scale) value,
        // and setting it is idempotent. Vanilla's Level$1 divides it by the dimension's coordinate
        // scale on read, so a centerScale of 1.0 puts the Nether center at overworld/8 and the End at
        // the overworld center, without the mod scaling it a second time.
        target.setCenter(overworld.getCenterX() * dim.centerScale, overworld.getCenterZ() * dim.centerScale);
    }

    /**
     * Pushes the border of each player's current dimension to their client. Called every server tick;
     * packets are only sent when the dimension, center or target size actually changed, so this also
     * corrects the overworld border that vanilla sends on join, respawn and dimension change.
     */
    public static void syncClients(MinecraftServer server) {
        BorderlineConfig config = BorderlineConfig.get();
        if (!config.enabled) {
            return;
        }
        WorldBorder overworld = server.overworld().getWorldBorder();
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        Set<UUID> online = new HashSet<>(players.size());
        for (ServerPlayer player : players) {
            UUID id = player.getUUID();
            online.add(id);
            ServerLevel level = player.serverLevel();
            ResourceKey<Level> dimension = level.dimension();
            WorldBorder border = level.getWorldBorder();
            double centerScale = centerScaleFor(dimension, config);
            double centerX = overworld.getCenterX() * centerScale;
            double centerZ = overworld.getCenterZ() * centerScale;
            double sizeTarget = border.getLerpTarget();

            ClientBorder sent = sentBorders.get(id);
            boolean dimensionChanged = sent == null || !sent.dimension().equals(dimension);
            boolean centerChanged = sent == null
                    || Math.abs(sent.centerX() - centerX) > EPSILON
                    || Math.abs(sent.centerZ() - centerZ) > EPSILON;
            boolean sizeChanged = sent == null || Math.abs(sent.sizeTarget() - sizeTarget) > EPSILON;
            if (!dimensionChanged && !centerChanged && !sizeChanged) {
                continue;
            }
            if (dimensionChanged || centerChanged) {
                player.connection.send(centerPacket(overworld, centerScale));
            }
            if (dimensionChanged || sizeChanged) {
                if (border.getLerpRemainingTime() > 0L) {
                    player.connection.send(new ClientboundSetBorderLerpSizePacket(border));
                } else {
                    player.connection.send(new ClientboundSetBorderSizePacket(border));
                }
            }
            sentBorders.put(id, new ClientBorder(dimension, centerX, centerZ, sizeTarget));
        }
        sentBorders.keySet().retainAll(online);
    }

    /**
     * The center wire value is in overworld coordinates: a Nether client's {@code Level$1} border
     * divides it by 8 itself, exactly like the server's border does.
     */
    private static ClientboundSetBorderCenterPacket centerPacket(WorldBorder overworld, double centerScale) {
        if (centerScale == 1.0D) {
            return new ClientboundSetBorderCenterPacket(overworld);
        }
        WorldBorder wire = new WorldBorder();
        wire.setCenter(overworld.getCenterX() * centerScale, overworld.getCenterZ() * centerScale);
        return new ClientboundSetBorderCenterPacket(wire);
    }

    private static double centerScaleFor(ResourceKey<Level> dimension, BorderlineConfig config) {
        if (Level.NETHER.equals(dimension)) {
            return config.nether != null ? config.nether.centerScale : 1.0D;
        }
        if (Level.END.equals(dimension)) {
            return config.end != null ? config.end.centerScale : 1.0D;
        }
        return 1.0D;
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

    private record ClientBorder(ResourceKey<Level> dimension, double centerX, double centerZ, double sizeTarget) {}

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
