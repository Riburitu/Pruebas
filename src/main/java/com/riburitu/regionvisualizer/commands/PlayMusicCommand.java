package com.riburitu.regionvisualizer.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.riburitu.regionvisualizer.network.NetworkHandler;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.server.level.ServerPlayer;

public class PlayMusicCommand {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("playmusic")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("player", EntityArgument.player())
                    .then(Commands.argument("filename", StringArgumentType.string())
                        .executes(ctx -> executePlayMusic(ctx)))
                    .then(Commands.literal("volume")
                        .then(Commands.argument("volume", FloatArgumentType.floatArg(0.0f, 1.0f))
                            .executes(ctx -> executeSetVolume(ctx))))
                    .then(Commands.literal("stop")
                        .executes(ctx -> executeStopMusic(ctx)))
                    .then(Commands.literal("list")
                        .executes(ctx -> executeListMusic(ctx)))
                    .then(Commands.literal("getvolume")
                        .executes(ctx -> executeGetVolume(ctx)))
                    .then(Commands.literal("config")
                        .executes(ctx -> executeOpenConfig(ctx))))
        );
    }

    private static int executePlayMusic(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer player = EntityArgument.getPlayer(ctx, "player");
            String filename = StringArgumentType.getString(ctx, "filename");

            NetworkHandler.sendPlayMusic(player, filename);
            ctx.getSource().sendSuccess(() -> Component.literal("[RegionVisualizer] Reproduciendo música '" + filename + "' para " + player.getName().getString())
                .withStyle(ChatFormatting.GREEN), true);
            System.out.println("[RegionVisualizer] Enviado comando play: " + filename + " para " + player.getName().getString());
            return 1;

        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("[RegionVisualizer] Error reproduciendo música: " + e.getMessage()));
            System.err.println("[RegionVisualizer] Error en comando playmusic: " + e.getMessage());
            return 0;
        }
    }

    private static int executeSetVolume(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer player = EntityArgument.getPlayer(ctx, "player");
            float volume = FloatArgumentType.getFloat(ctx, "volume");

            NetworkHandler.sendPlayMusic(player, "VOLUME:" + volume);
            ctx.getSource().sendSuccess(() -> Component.literal("[RegionVisualizer] Volumen establecido a " + Math.round(volume * 100) + "% para " + player.getName().getString())
                .withStyle(ChatFormatting.GREEN), true);
            System.out.println("[RegionVisualizer] Enviado comando volume: " + volume + " para " + player.getName().getString());
            return 1;

        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("[RegionVisualizer] Error estableciendo volumen: " + e.getMessage()));
            System.err.println("[RegionVisualizer] Error en comando volume: " + e.getMessage());
            return 0;
        }
    }

    private static int executeStopMusic(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer player = EntityArgument.getPlayer(ctx, "player");

            NetworkHandler.sendPlayMusic(player, "STOP");
            ctx.getSource().sendSuccess(() -> Component.literal("[RegionVisualizer] Música detenida para " + player.getName().getString())
                .withStyle(ChatFormatting.GREEN), true);
            System.out.println("[RegionVisualizer] Enviado comando stop para " + player.getName().getString());
            return 1;

        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("[RegionVisualizer] Error deteniendo música: " + e.getMessage()));
            System.err.println("[RegionVisualizer] Error en comando stop: " + e.getMessage());
            return 0;
        }
    }

    private static int executeListMusic(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer player = EntityArgument.getPlayer(ctx, "player");

            NetworkHandler.sendPlayMusic(player, "LIST");
            ctx.getSource().sendSuccess(() -> Component.literal("[RegionVisualizer] Lista de música enviada a " + player.getName().getString())
                .withStyle(ChatFormatting.GREEN), true);
            System.out.println("[RegionVisualizer] Enviado comando list para " + player.getName().getString());
            return 1;

        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("[RegionVisualizer] Error listando música: " + e.getMessage()));
            System.err.println("[RegionVisualizer] Error en comando list: " + e.getMessage());
            return 0;
        }
    }

    private static int executeGetVolume(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer player = EntityArgument.getPlayer(ctx, "player");

            NetworkHandler.sendPlayMusic(player, "GET_VOLUME");
            ctx.getSource().sendSuccess(() -> Component.literal("[RegionVisualizer] Solicitado volumen actual para " + player.getName().getString())
                .withStyle(ChatFormatting.GREEN), true);
            System.out.println("[RegionVisualizer] Enviado comando getvolume para " + player.getName().getString());
            return 1;

        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("[RegionVisualizer] Error obteniendo volumen: " + e.getMessage()));
            System.err.println("[RegionVisualizer] Error en comando getvolume: " + e.getMessage());
            return 0;
        }
    }

    private static int executeOpenConfig(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer player = EntityArgument.getPlayer(ctx, "player");

            NetworkHandler.sendPlayMusic(player, "CONFIG");
            ctx.getSource().sendSuccess(() -> Component.literal("[RegionVisualizer] Abriendo configuración de música para " + player.getName().getString())
                .withStyle(ChatFormatting.GREEN), true);
            System.out.println("[RegionVisualizer] Enviado comando config para " + player.getName().getString());
            return 1;

        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("[RegionVisualizer] Error abriendo configuración: " + e.getMessage()));
            System.err.println("[RegionVisualizer] Error en comando config: " + e.getMessage());
            return 0;
        }
    }
}