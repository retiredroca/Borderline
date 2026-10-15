package com.retiredroca.borderline.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.retiredroca.borderline.BorderSync;
import com.retiredroca.borderline.BorderlineConfig;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.border.WorldBorder;

import java.util.List;
import java.util.Locale;

/** {@code /borderline set|sync|status} - operator command for managing the per-dimension borders. */
public final class BorderlineCommand {
    private BorderlineCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("borderline")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("set")
                        .then(Commands.argument("diameter", DoubleArgumentType.doubleArg(1.0D, WorldBorder.MAX_SIZE))
                                .executes(ctx -> set(ctx, 0L))
                                .then(Commands.argument("seconds", IntegerArgumentType.integer(0))
                                        .executes(ctx -> set(ctx,
                                                IntegerArgumentType.getInteger(ctx, "seconds") * 1000L)))))
                .then(Commands.literal("sync")
                        .executes(BorderlineCommand::sync))
                .then(Commands.literal("status")
                        .executes(BorderlineCommand::status)));
    }

    private static int set(CommandContext<CommandSourceStack> ctx, long millis) {
        CommandSourceStack source = ctx.getSource();
        if (!BorderlineConfig.get().enabled) {
            source.sendFailure(Component.literal("Borderline is disabled in borderline.json."));
            return 0;
        }
        double diameter = DoubleArgumentType.getDouble(ctx, "diameter");
        BorderSync.set(source.getServer(), diameter, millis);
        source.sendSuccess(() -> Component.literal("Set the overworld world size to " + fmt(diameter)
                + (millis > 0L ? " over " + (millis / 1000L) + "s" : "") + "."), true);
        sendStatus(source);
        return 1;
    }

    private static int sync(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!BorderlineConfig.get().enabled) {
            source.sendFailure(Component.literal("Borderline is disabled in borderline.json."));
            return 0;
        }
        BorderSync.sync(source.getServer());
        source.sendSuccess(() -> Component.literal("Synced from the overworld world border."), true);
        sendStatus(source);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        sendStatus(ctx.getSource());
        return 1;
    }

    private static void sendStatus(CommandSourceStack source) {
        List<String> lines = BorderSync.describe(source.getServer());
        source.sendSuccess(() -> Component.literal(String.join("\n", lines)), false);
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
