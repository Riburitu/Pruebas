package com.riburitu.regionvisualizer.network;

import com.riburitu.regionvisualizer.item.RegionSelectorItem;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class PacketClearSelection {
    public PacketClearSelection() {}

    public static void encode(PacketClearSelection msg, FriendlyByteBuf buf) {
        // Sin datos
    }

    public static PacketClearSelection decode(FriendlyByteBuf buf) {
        return new PacketClearSelection();
    }

    public static void handle(PacketClearSelection msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            RegionSelectorItem.clearSelection();
        });
        ctx.get().setPacketHandled(true);
    }
}
