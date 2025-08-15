// RegionSelectorItem.java

package com.riburitu.regionvisualizer.item;

import com.riburitu.regionvisualizer.util.RegionSelection;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.List;

public class RegionSelectorItem extends Item {
    private static final RegionSelection selection = new RegionSelection();
    private static BlockPos lastPos1 = null; // Para evitar spam
    private static BlockPos lastPos2 = null; // Para evitar spam

    public RegionSelectorItem(Properties props) {
        super(props);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        @SuppressWarnings("unused")
		Level level = context.getLevel();
        BlockPos clicked = context.getClickedPos();

        if (player == null) return InteractionResult.FAIL;

        try {
            if (player.isShiftKeyDown()) {
                // Shift + Click = Establecer Pos2
                if (!clicked.equals(lastPos2)) {
                    selection.setPos2(clicked);
                    lastPos2 = clicked;
                    
                    Component message = Component.literal("[RegionVisualizer] Pos2 establecida: " + formatPos(clicked))
                        .withStyle(ChatFormatting.LIGHT_PURPLE);
                    player.sendSystemMessage(message);
                    
                    System.out.println("[RegionVisualizer] " + player.getName().getString() + " estableció Pos2: " + clicked);
                }
            } else {
                // Click normal = Establecer Pos1
                if (!clicked.equals(lastPos1)) {
                    selection.setPos1(clicked);
                    lastPos1 = clicked;
                    
                    Component message = Component.literal("[RegionVisualizer] Pos1 establecida: " + formatPos(clicked))
                        .withStyle(ChatFormatting.AQUA);
                    player.sendSystemMessage(message);
                    
                    System.out.println("[RegionVisualizer] " + player.getName().getString() + " estableció Pos1: " + clicked);
                }
            }

            // Mostrar el estado actual si la selección está completada.
            if (selection.isComplete()) {
                int sizeX = Math.abs(selection.pos1.getX() - selection.pos2.getX()) + 1;
                int sizeY = Math.abs(selection.pos1.getY() - selection.pos2.getY()) + 1;
                int sizeZ = Math.abs(selection.pos1.getZ() - selection.pos2.getZ()) + 1;
                
                Component statusMessage = Component.literal("[RegionVisualizer] Selección completa - Tamaño: " + 
                    sizeX + "x" + sizeY + "x" + sizeZ + " bloques")
                    .withStyle(ChatFormatting.GREEN);
                player.sendSystemMessage(statusMessage);
                
                System.out.println("[RegionVisualizer] Selección completa para " + player.getName().getString() + 
                    ": pos1=" + selection.pos1 + ", pos2=" + selection.pos2 + ", tamaño=" + sizeX + "x" + sizeY + "x" + sizeZ);
            }

            return InteractionResult.SUCCESS;

        } catch (Exception e) {
            System.err.println("[RegionVisualizer] Error en RegionSelectorItem: " + e.getMessage());
            e.printStackTrace();
            
            Component errorMessage = Component.literal("[RegionVisualizer] Error al seleccionar posición")
                .withStyle(ChatFormatting.RED);
            player.sendSystemMessage(errorMessage);
            
            return InteractionResult.FAIL;
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.literal("Selector de Regiones").withStyle(ChatFormatting.GOLD));
        tooltip.add(Component.literal("Click izquierdo: Establecer Pos1").withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.literal("Shift + Click izquierdo: Establecer Pos2").withStyle(ChatFormatting.LIGHT_PURPLE));
        
        if (selection.pos1 != null) {
            tooltip.add(Component.literal("Pos1: " + formatPos(selection.pos1)).withStyle(ChatFormatting.GRAY));
        }
        
        if (selection.pos2 != null) {
            tooltip.add(Component.literal("Pos2: " + formatPos(selection.pos2)).withStyle(ChatFormatting.GRAY));
        }
        
        if (selection.isComplete()) {
            int sizeX = Math.abs(selection.pos1.getX() - selection.pos2.getX()) + 1;
            int sizeY = Math.abs(selection.pos1.getY() - selection.pos2.getY()) + 1;
            int sizeZ = Math.abs(selection.pos1.getZ() - selection.pos2.getZ()) + 1;
            tooltip.add(Component.literal("Tamaño: " + sizeX + "x" + sizeY + "x" + sizeZ).withStyle(ChatFormatting.GREEN));
        } else {
            tooltip.add(Component.literal("Selección incompleta").withStyle(ChatFormatting.YELLOW));
        }
    }

    public static RegionSelection getSelection() {
        return selection;
    }

    public static void clearSelection() {
        selection.setPos1(null);
        selection.setPos2(null);
        lastPos1 = null;
        lastPos2 = null;
        System.out.println("[RegionVisualizer] Selección limpiada.");
    }

    private static String formatPos(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        clearSelection();
        System.out.println("[RegionVisualizer] Selección limpiada al salir el jugador: " + event.getEntity().getName().getString());
    }

    @SubscribeEvent  
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        // Limpiar selección cuando un jugador se conecta nuevamente para evitar conflictos.
        clearSelection();
        System.out.println("[RegionVisualizer] Selección reiniciada para nuevo jugador: " + event.getEntity().getName().getString());
    }
}