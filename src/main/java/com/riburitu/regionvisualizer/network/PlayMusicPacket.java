package com.riburitu.regionvisualizer.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import java.util.function.Supplier;

public class PlayMusicPacket {
    private final String command;

    public PlayMusicPacket(String command) {
        this.command = command;
    }

    public static void encode(PlayMusicPacket pkt, FriendlyByteBuf buf) {
        buf.writeUtf(pkt.command);
    }

    public static PlayMusicPacket decode(FriendlyByteBuf buf) {
        return new PlayMusicPacket(buf.readUtf(32767));
    }

    public static void handle(PlayMusicPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            if (ctx.get().getDirection().getReceptionSide().isClient()) {
                handleClientSide(pkt.command);
            }
        });
        ctx.get().setPacketHandled(true);
    }

    private static void handleClientSide(String command) {
        try {
            System.out.println("[RegionVisualizer] 📦 Paquete recibido: " + command);
            
            if (command.startsWith("VOLUME:") || command.equals("GET_VOLUME") || command.equals("CONFIG")) {
                com.riburitu.regionvisualizer.client.sound.MusicManager.handleCommand(command);
                return;
            }

            if (command.startsWith("MUSIC:")) {
                String[] parts = command.split(":", 4);
                if (parts.length < 4) {
                    System.err.println("[RegionVisualizer] ❌ Comando de música inválido: " + command);
                    return;
                }
                String filename = parts[1];
                boolean loop = Boolean.parseBoolean(parts[2]);
                boolean fade = Boolean.parseBoolean(parts[3]);
                com.riburitu.regionvisualizer.client.sound.MusicManager.play(filename, loop, fade);
                return;
            }

            if (command.startsWith("STOP:")) {
                String[] parts = command.split(":", 2);
                boolean fade = parts.length > 1 && Boolean.parseBoolean(parts[1]);
                com.riburitu.regionvisualizer.client.sound.MusicManager.stop(fade);
                System.out.println("[RegionVisualizer] 🛑 Música detenida por comando del servidor, fade=" + fade);
                return;
            }

            switch (command.toUpperCase()) {
                case "INIT":
                    com.riburitu.regionvisualizer.client.sound.MusicManager.forceInitialize();
                    com.riburitu.regionvisualizer.client.sound.MusicManager.listAvailableFiles();
                    System.out.println("[RegionVisualizer] 🔄 Sistema de música reinicializado");
                    break;
                    
                case "LIST":
                    com.riburitu.regionvisualizer.client.sound.MusicManager.listAvailableFiles();
                    System.out.println("[RegionVisualizer] 📝 Lista de música solicitada");
                    break;
                    
                default:
                    if (!command.trim().isEmpty()) {
                        com.riburitu.regionvisualizer.client.sound.MusicManager.play(command, false, false);
                        System.out.println("[RegionVisualizer] 🎵 Reproduciendo: " + command);
                    } else {
                        System.err.println("[RegionVisualizer] ⚠️ Comando vacío recibido");
                    }
                    break;
            }

        } catch (Exception e) {
            System.err.println("[RegionVisualizer] ❌ Error manejando paquete de música: " + e.getMessage());
            e.printStackTrace();
            
            try {
                net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
                if (minecraft.player != null) {
                    minecraft.player.sendSystemMessage(
                        net.minecraft.network.chat.Component.literal("❌ Error de música: " + e.getMessage())
                            .withStyle(net.minecraft.ChatFormatting.RED)
                    );
                }
            } catch (Exception ignored) {
            }
        }
    }
}