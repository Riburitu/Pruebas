package com.riburitu.regionvisualizer.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.riburitu.regionvisualizer.util.Region;
import com.riburitu.regionvisualizer.util.RegionManager;
import com.riburitu.regionvisualizer.item.RegionSelectorItem;
import com.riburitu.regionvisualizer.network.NetworkHandler;
import com.riburitu.regionvisualizer.network.PacketClearSelection;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraftforge.network.PacketDistributor;
import java.util.Optional;

@SuppressWarnings("unused")
public class RegionCommands {
    private final RegionManager regionManager;

    public RegionCommands(RegionManager regionManager) {
        this.regionManager = regionManager;
    }

    public void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("region")
                .requires(source -> source.hasPermission(2))
                
                .then(Commands.literal("add")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .then(Commands.argument("musicFile", StringArgumentType.string())
                            .then(Commands.argument("loop", BoolArgumentType.bool())
                                .executes(ctx -> executeAddRegion(ctx, false)) // Sin fade
                                .then(Commands.argument("fade", BoolArgumentType.bool())
                                    .executes(ctx -> executeAddRegion(ctx, BoolArgumentType.getBool(ctx, "fade"))))))))
                
                .then(Commands.literal("remove")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> executeRemoveRegion(ctx))))
                
                .then(Commands.literal("list")
                    .executes(ctx -> executeListRegions(ctx)))
                
                .then(Commands.literal("tp")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> executeTeleportToRegion(ctx))))
                
                .then(Commands.literal("cancel")
                    .executes(ctx -> executeCancelSelection(ctx)))
                
                .then(Commands.literal("info")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> executeRegionInfo(ctx))))
                
                .then(Commands.literal("here")
                    .executes(ctx -> executeRegionHere(ctx)))
        );
    }

    private int executeAddRegion(CommandContext<CommandSourceStack> ctx, boolean fade) {
        try {
            CommandSourceStack source = ctx.getSource();
            ServerPlayer player = source.getPlayerOrException();
            BlockPos pos1 = RegionSelectorItem.getSelection().pos1;
            BlockPos pos2 = RegionSelectorItem.getSelection().pos2;

            if (pos1 == null || pos2 == null) {
                source.sendFailure(Component.literal("[RegionVisualizer] Debes seleccionar dos posiciones con el Region Selector.").withStyle(ChatFormatting.RED));
                return 0;
            }

            String name = StringArgumentType.getString(ctx, "name");
            String musicFile = StringArgumentType.getString(ctx, "musicFile");
            boolean loopEnabled = BoolArgumentType.getBool(ctx, "loop");
            System.out.println("[RegionVisualizer] 📥 Comando /region add recibido: name=" + name + ", musicFile=" + musicFile + ", loop=" + loopEnabled + ", fade=" + fade);

            Region region = new Region(name, pos1, pos2, musicFile, loopEnabled, fade);
            regionManager.addRegion(region);
            regionManager.saveRegions(player.serverLevel());

            source.sendSuccess(() -> Component.literal("[RegionVisualizer] Región '" + name + "' creada con música '" + musicFile + "', bucle: " + (loopEnabled ? "Activado" : "Desactivado") + ", fade: " + (fade ? "Activado" : "Desactivado")).withStyle(ChatFormatting.GREEN), true);
            NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new PacketClearSelection());

            return 1;

        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("[RegionVisualizer] Error creando región: " + e.getMessage()).withStyle(ChatFormatting.RED));
            System.err.println("[RegionVisualizer] Error creando región: " + e.getMessage());
            e.printStackTrace();
            return 0;
        }
    }

    private int executeRemoveRegion(CommandContext<CommandSourceStack> ctx) {
        try {
            String name = StringArgumentType.getString(ctx, "name");
            CommandSourceStack source = ctx.getSource();

            if (regionManager.removeRegion(name)) {
                regionManager.saveRegions(source.getLevel());
                source.sendSuccess(() -> Component.literal("[RegionVisualizer] Región '" + name + "' eliminada.").withStyle(ChatFormatting.GREEN), true);
                return 1;
            } else {
                source.sendFailure(Component.literal("[RegionVisualizer] No se encontró la región '" + name + "'.").withStyle(ChatFormatting.RED));
                return 0;
            }

        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("[RegionVisualizer] Error eliminando región: " + e.getMessage()).withStyle(ChatFormatting.RED));
            System.err.println("[RegionVisualizer] Error eliminando región: " + e.getMessage());
            e.printStackTrace();
            return 0;
        }
    }

    private int executeListRegions(CommandContext<CommandSourceStack> ctx) {
        try {
            CommandSourceStack source = ctx.getSource();
            source.sendSuccess(() -> Component.literal("[RegionVisualizer] Lista de regiones:").withStyle(ChatFormatting.GOLD), false);

            for (Region region : regionManager.getRegions()) {
                BlockPos center = region.getCenter();
                source.sendSuccess(() -> Component.literal("- " + region.getName() + " en " + center.getX() + ", " + center.getY() + ", " + center.getZ() + ", música: " + (region.getMusicFile() != null ? region.getMusicFile() : "Ninguna") + ", bucle: " + (region.isLoopEnabled() ? "Activado" : "Desactivado") + ", fade: " + (region.isFadeEnabled() ? "Activado" : "Desactivado")).withStyle(ChatFormatting.YELLOW), false);
            }

            return 1;

        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("[RegionVisualizer] Error listando regiones: " + e.getMessage()).withStyle(ChatFormatting.RED));
            System.err.println("[RegionVisualizer] Error listando regiones: " + e.getMessage());
            e.printStackTrace();
            return 0;
        }
    }

    private int executeTeleportToRegion(CommandContext<CommandSourceStack> ctx) {
        try {
            String name = StringArgumentType.getString(ctx, "name");
            CommandSourceStack source = ctx.getSource();
            ServerPlayer player = source.getPlayerOrException();
            Optional<Region> regionOpt = regionManager.getRegionByName(name);

            if (regionOpt.isEmpty()) {
                source.sendFailure(Component.literal("[RegionVisualizer] No se encontró la región '" + name + "'.").withStyle(ChatFormatting.RED));
                return 0;
            }

            Region region = regionOpt.get();
            BlockPos center = region.getCenter();
            player.teleportTo(center.getX() + 0.5, center.getY(), center.getZ() + 0.5);
            source.sendSuccess(() -> Component.literal("[RegionVisualizer] Teletransportado a la región '" + name + "'.").withStyle(ChatFormatting.GREEN), true);
            return 1;

        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("[RegionVisualizer] Error teletransportando: " + e.getMessage()).withStyle(ChatFormatting.RED));
            System.err.println("[RegionVisualizer] Error teletransportando: " + e.getMessage());
            e.printStackTrace();
            return 0;
        }
    }

    private int executeCancelSelection(CommandContext<CommandSourceStack> ctx) {
        try {
            CommandSourceStack source = ctx.getSource();
            ServerPlayer player = source.getPlayerOrException();
            RegionSelectorItem.clearSelection();
            NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new PacketClearSelection());
            source.sendSuccess(() -> Component.literal("[RegionVisualizer] Selección cancelada.").withStyle(ChatFormatting.GREEN), true);
            return 1;

        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("[RegionVisualizer] Error cancelando selección: " + e.getMessage()).withStyle(ChatFormatting.RED));
            System.err.println("[RegionVisualizer] Error cancelando selección: " + e.getMessage());
            e.printStackTrace();
            return 0;
        }
    }

    private int executeRegionInfo(CommandContext<CommandSourceStack> ctx) {
        try {
            String name = StringArgumentType.getString(ctx, "name");
            CommandSourceStack source = ctx.getSource();
            Optional<Region> regionOpt = regionManager.getRegionByName(name);

            if (regionOpt.isEmpty()) {
                source.sendFailure(Component.literal("[RegionVisualizer] No se encontró la región '" + name + "'.").withStyle(ChatFormatting.RED));
                return 0;
            }

            Region region = regionOpt.get();
            BlockPos pos1 = region.getPos1();
            BlockPos pos2 = region.getPos2();
            BlockPos center = region.getCenter();

            source.sendSuccess(() -> Component.literal("=== Información de Región: " + name + " ===").withStyle(ChatFormatting.GOLD), false);
            source.sendSuccess(() -> Component.literal("Posición 1: " + pos1.getX() + ", " + pos1.getY() + ", " + pos1.getZ()).withStyle(ChatFormatting.AQUA), false);
            source.sendSuccess(() -> Component.literal("Posición 2: " + pos2.getX() + ", " + pos2.getY() + ", " + pos2.getZ()).withStyle(ChatFormatting.AQUA), false);
            source.sendSuccess(() -> Component.literal("Centro: " + center.getX() + ", " + center.getY() + ", " + center.getZ()).withStyle(ChatFormatting.GREEN), false);
            source.sendSuccess(() -> Component.literal("Música: " + (region.getMusicFile() != null ? region.getMusicFile() : "Ninguna")).withStyle(ChatFormatting.YELLOW), false);
            source.sendSuccess(() -> Component.literal("Bucle: " + (region.isLoopEnabled() ? "Activado" : "Desactivado")).withStyle(ChatFormatting.YELLOW), false);
            source.sendSuccess(() -> Component.literal("Fade: " + (region.isFadeEnabled() ? "Activado" : "Desactivado")).withStyle(ChatFormatting.YELLOW), false);

            int sizeX = Math.abs(pos1.getX() - pos2.getX()) + 1;
            int sizeY = Math.abs(pos1.getY() - pos2.getY()) + 1;
            int sizeZ = Math.abs(pos1.getZ() - pos2.getZ()) + 1;
            
            source.sendSuccess(() -> Component.literal("Dimensiones: " + sizeX + "x" + sizeY + "x" + sizeZ + " bloques").withStyle(ChatFormatting.YELLOW), false);
                
            return 1;

        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("[RegionVisualizer] Error obteniendo información: " + e.getMessage()).withStyle(ChatFormatting.RED));
            System.err.println("[RegionVisualizer] Error obteniendo información: " + e.getMessage());
            e.printStackTrace();
            return 0;
        }
    }

    private int executeRegionHere(CommandContext<CommandSourceStack> ctx) {
        try {
            CommandSourceStack source = ctx.getSource();
            ServerPlayer player = source.getPlayerOrException();
            BlockPos playerPos = player.blockPosition();

            Optional<Region> regionOpt = regionManager.getRegionContaining(playerPos);
            
            if (regionOpt.isEmpty()) {
                source.sendSuccess(() -> Component.literal("[RegionVisualizer] No estás en ninguna región.").withStyle(ChatFormatting.YELLOW), false);
                return 1;
            }

            Region region = regionOpt.get();
            source.sendSuccess(() -> Component.literal("[RegionVisualizer] Estás en la región: " + region.getName()).withStyle(ChatFormatting.GREEN), false);
                
            return 1;

        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("[RegionVisualizer] Error verificando ubicación: " + e.getMessage()).withStyle(ChatFormatting.RED));
            System.err.println("[RegionVisualizer] Error verificando ubicación: " + e.getMessage());
            e.printStackTrace();
            return 0;
        }
    }
}