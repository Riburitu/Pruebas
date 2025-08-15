package com.riburitu.regionvisualizer.util;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;

public class Region {
    private final String name;
    private final BlockPos pos1;
    private final BlockPos pos2;
    private final String musicFile;
    private final boolean loopEnabled;
    private final boolean fadeEnabled; // Nuevo campo para fade

    public Region(String name, BlockPos pos1, BlockPos pos2, String musicFile, boolean loopEnabled, boolean fadeEnabled) {
        this.name = name;
        this.pos1 = pos1;
        this.pos2 = pos2;
        this.musicFile = musicFile;
        this.loopEnabled = loopEnabled;
        this.fadeEnabled = fadeEnabled; // Inicializar fadeEnabled
    }

    public String getName() {
        return name;
    }

    public BlockPos getPos1() {
        return pos1;
    }

    public BlockPos getPos2() {
        return pos2;
    }

    public String getMusicFile() {
        return musicFile;
    }

    public boolean isLoopEnabled() {
        return loopEnabled;
    }

    public boolean isFadeEnabled() { // Nuevo getter
        return fadeEnabled;
    }

    public boolean contains(BlockPos pos) {
        int minX = Math.min(pos1.getX(), pos2.getX());
        int maxX = Math.max(pos1.getX(), pos2.getX());
        int minY = Math.min(pos1.getY(), pos2.getY());
        int maxY = Math.max(pos1.getY(), pos2.getY());
        int minZ = Math.min(pos1.getZ(), pos2.getZ());
        int maxZ = Math.max(pos1.getZ(), pos2.getZ());

        return pos.getX() >= minX && pos.getX() <= maxX &&
               pos.getY() >= minY && pos.getY() <= maxY &&
               pos.getZ() >= minZ && pos.getZ() <= maxZ;
    }

    public BlockPos getCenter() {
        return new BlockPos(
            (pos1.getX() + pos2.getX()) / 2,
            (pos1.getY() + pos2.getY()) / 2,
            (pos1.getZ() + pos2.getZ()) / 2
        );
    }

    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("name", name);
        JsonObject p1 = new JsonObject();
        p1.addProperty("x", pos1.getX());
        p1.addProperty("y", pos1.getY());
        p1.addProperty("z", pos1.getZ());
        obj.add("pos1", p1);
        JsonObject p2 = new JsonObject();
        p2.addProperty("x", pos2.getX());
        p2.addProperty("y", pos2.getY());
        p2.addProperty("z", pos2.getZ());
        obj.add("pos2", p2);
        if (musicFile != null) {
            obj.addProperty("musicFile", musicFile);
            obj.addProperty("loopEnabled", loopEnabled);
            obj.addProperty("fadeEnabled", fadeEnabled); // Serializar fadeEnabled
            System.out.println("[RegionVisualizer] Serializando región " + name + ": musicFile=" + musicFile + ", loopEnabled=" + loopEnabled + ", fadeEnabled=" + fadeEnabled);
        } else {
            System.out.println("[RegionVisualizer] Serializando región " + name + ": sin musicFile, loopEnabled=" + loopEnabled + ", fadeEnabled=" + fadeEnabled);
        }
        return obj;
    }

    public static Region fromJson(JsonObject obj) {
        String name = obj.get("name").getAsString();
        JsonObject p1 = obj.getAsJsonObject("pos1");
        BlockPos pos1 = new BlockPos(
            p1.get("x").getAsInt(),
            p1.get("y").getAsInt(),
            p1.get("z").getAsInt()
        );
        JsonObject p2 = obj.getAsJsonObject("pos2");
        BlockPos pos2 = new BlockPos(
            p2.get("x").getAsInt(),
            p2.get("y").getAsInt(),
            p2.get("z").getAsInt()
        );
        String musicFile = obj.has("musicFile") ? obj.get("musicFile").getAsString() : null;
        boolean loopEnabled = obj.has("loopEnabled") ? obj.get("loopEnabled").getAsBoolean() : false;
        boolean fadeEnabled = obj.has("fadeEnabled") ? obj.get("fadeEnabled").getAsBoolean() : false; // Deserializar fadeEnabled
        System.out.println("[RegionVisualizer] Deserializando región " + name + ": musicFile=" + musicFile + ", loopEnabled=" + loopEnabled + ", fadeEnabled=" + fadeEnabled);
        return new Region(name, pos1, pos2, musicFile, loopEnabled, fadeEnabled);
    }
}