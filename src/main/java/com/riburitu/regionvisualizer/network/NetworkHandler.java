package com.riburitu.regionvisualizer.network;

import com.riburitu.regionvisualizer.RegionVisualizer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public class NetworkHandler {
    private static final String PROTOCOL_VERSION = "1";
    @SuppressWarnings("removal")
	public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(RegionVisualizer.MODID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    public static void register() {
        int id = 0;
        CHANNEL.registerMessage(id++, PacketClearSelection.class,
                PacketClearSelection::encode,
                PacketClearSelection::decode,
                PacketClearSelection::handle);
        CHANNEL.registerMessage(id++, PlayMusicPacket.class,
        		PlayMusicPacket::encode,
        		PlayMusicPacket::decode,
        		PlayMusicPacket::handle);
    }
    public static void sendPlayMusic(ServerPlayer player, String filename) {
        CHANNEL.sendTo(new PlayMusicPacket(filename), player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
    }
}
