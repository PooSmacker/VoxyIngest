package com.dripps.voxyingest.server;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

public class IngestCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("voxyingest")
                .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(ctx -> {
                            ServerPlayer player = EntityArgument.getPlayer(ctx, "player");
                            return startIngest(ctx.getSource(), player);
                        })
                )
        );
    }

    private static int startIngest(CommandSourceStack source, ServerPlayer player) {
        if (ChunkSender.isSessionActive(player)) {
            ChunkSender.cancelSession(player);
            source.sendSuccess(() -> Component.literal(
                    "stopped active ingest for " + player.getGameProfile().name()), true);
            return 1;
        }

        boolean started = ChunkSender.startSending(player);
        if (started) {
            source.sendSuccess(() -> Component.literal(
                    "started sending all generated world data to " + player.getGameProfile().name()
                            + " (scanning region files...)"), true);
        } else {
            source.sendFailure(Component.literal(
                    "couldnt start ingest for " + player.getGameProfile().name()
                            + " — session already active"));
        }
        return started ? 1 : 0;
    }
}
