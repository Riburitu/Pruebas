package com.riburitu.regionvisualizer.util;

import com.google.gson.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.core.BlockPos;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class RegionManager {
    private static final String FOLDER_NAME = "regionvisualizer";
    private static final String FILE_NAME = "regions.json";

    private final List<Region> regions = new ArrayList<>();

    public List<Region> getRegions() {
        return Collections.unmodifiableList(regions);
    }

    public void addRegion(Region region) {
        if (region == null) {
            System.err.println("[RegionVisualizer] Intento de agregar región nula");
            return;
        }
        
        boolean exists = regions.stream()
            .anyMatch(r -> r.getName().equalsIgnoreCase(region.getName()));
            
        if (exists) {
            System.err.println("[RegionVisualizer] Ya existe una región con el nombre: " + region.getName());
            return;
        }
        
        regions.add(region);
        System.out.println("[RegionVisualizer] Región agregada: " + region.getName() + ", música: " + region.getMusicFile() + ", loopEnabled: " + region.isLoopEnabled());
    }

    public boolean removeRegion(String name) {
        if (name == null || name.trim().isEmpty()) {
            System.err.println("[RegionVisualizer] Intento de eliminar región con nombre vacío");
            return false;
        }
        
        boolean removed = regions.removeIf(r -> r.getName().equalsIgnoreCase(name));
        
        if (removed) {
            System.out.println("[RegionVisualizer] Región eliminada: " + name);
        } else {
            System.out.println("[RegionVisualizer] No se encontró región para eliminar: " + name);
        }
        
        return removed;
    }

    public Optional<Region> getRegionContaining(BlockPos pos) {
        if (pos == null) return Optional.empty();
        
        return regions.stream()
            .filter(r -> r.contains(pos))
            .findFirst();
    }

    public Optional<Region> getRegionByName(String name) {
        if (name == null || name.trim().isEmpty()) return Optional.empty();
        
        return regions.stream()
            .filter(r -> r.getName().equalsIgnoreCase(name))
            .findFirst();
    }

    public void saveRegions(ServerLevel level) {
        Path folder = level.getServer().getWorldPath(LevelResource.ROOT).resolve(FOLDER_NAME);
        if (folder == null) {
            System.err.println("[RegionVisualizer] No se pudo obtener la carpeta de guardado");
            return;
        }

        try {
            if (!Files.exists(folder)) {
                Files.createDirectories(folder);
                System.out.println("[RegionVisualizer] Carpeta creada: " + folder);
            }
            
            Path file = folder.resolve(FILE_NAME);
            JsonArray array = new JsonArray();
            for (Region r : regions) {
                try {
                    JsonObject regionJson = r.toJson();
                    if (regionJson != null) {
                        array.add(regionJson);
                        System.out.println("[RegionVisualizer] Serializando región: " + r.getName() + ", música: " + r.getMusicFile() + ", loopEnabled: " + r.isLoopEnabled());
                    }
                } catch (Exception e) {
                    System.err.println("[RegionVisualizer] Error serializando región " + r.getName() + ": " + e.getMessage());
                    e.printStackTrace();
                }
            }

            try (Writer writer = Files.newBufferedWriter(file)) {
                Gson gson = new GsonBuilder().setPrettyPrinting().create();
                gson.toJson(array, writer);
                System.out.println("[RegionVisualizer] Regiones guardadas exitosamente: " + regions.size() + " en " + file);
            } catch (IOException e) {
                System.err.println("[RegionVisualizer] Error de I/O al escribir " + file + ": " + e.getMessage());
                e.printStackTrace();
            }
            
        } catch (IOException e) {
            System.err.println("[RegionVisualizer] Error de I/O al crear carpeta o escribir " + FILE_NAME + ": " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("[RegionVisualizer] Error inesperado guardando regiones: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void loadRegions(ServerLevel level) {
        Path folder = level.getServer().getWorldPath(LevelResource.ROOT).resolve(FOLDER_NAME);
        Path file = folder.resolve(FILE_NAME);
        if (!Files.exists(file)) {
            System.out.println("[RegionVisualizer] No se encontró archivo de regiones: " + file);
            return;
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            Gson gson = new Gson();
            JsonArray array = gson.fromJson(reader, JsonArray.class);
            if (array != null && array.size() > 0) {
                regions.clear();
                for (JsonElement element : array) {
                    try {
                        JsonObject obj = element.getAsJsonObject();
                        Region region = Region.fromJson(obj);
                        regions.add(region);
                        System.out.println("[RegionVisualizer] Región cargada: " + region.getName() + ", música: " + region.getMusicFile() + ", loopEnabled: " + region.isLoopEnabled());
                    } catch (Exception e) {
                        System.err.println("[RegionVisualizer] Error deserializando región: " + e.getMessage());
                        e.printStackTrace();
                    }
                }
                System.out.println("[RegionVisualizer] Regiones cargadas: " + regions.size() + " desde " + file);
            } else {
                System.out.println("[RegionVisualizer] El archivo de regiones está vacío o no es un array JSON válido: " + file);
            }
        } catch (IOException e) {
            System.err.println("[RegionVisualizer] Error de I/O al cargar " + FILE_NAME + ": " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("[RegionVisualizer] Error inesperado cargando regiones: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void clearAllRegions() {
        int count = regions.size();
        regions.clear();
        System.out.println("[RegionVisualizer] Todas las regiones eliminadas (" + count + " regiones)");
    }

    public void printStatistics() {
        System.out.println("[RegionVisualizer] === Estadísticas de Regiones ===");
        System.out.println("Total de regiones: " + regions.size());
        
        if (!regions.isEmpty()) {
            for (Region region : regions) {
                BlockPos center = region.getCenter();
                System.out.println("- " + region.getName() + " en " + center.getX() + ", " + center.getY() + ", " + center.getZ() + ", música: " + region.getMusicFile() + ", loopEnabled: " + region.isLoopEnabled());
            }
        }
    }
}