package com.riburitu.regionvisualizer.client.sound;

// Mis paquetes
import com.riburitu.regionvisualizer.RegionVisualizer;
import com.riburitu.regionvisualizer.util.Region;
import com.riburitu.regionvisualizer.util.RegionManager;

// Minecraft
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.world.level.Level;

// Java Sound API
import javax.sound.sampled.*;

// Java IO
import java.io.*;

// Java Nio
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

// Java utils
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.stream.Stream;

// Java Security
import java.security.MessageDigest;
import java.security.DigestInputStream;

// Java Lang
import java.lang.reflect.Type;

// Gson
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;




public class MusicManager {
    
    // ========================================
    // CONSTANTES Y CONFIGURACIÓN
    // ========================================
    
	private static final String[] SUPPORTED_FORMATS = {".wav", ".ogg"};
	
	// Cache híbrido - configuración
	private static final int MAX_RAM_CACHED_FILES = 4;
	private static final int MAX_DISK_CACHED_FILES = 50;
	private static final long MAX_FILE_SIZE_FOR_RAM_CACHE = 10 * 1024 * 1024; // 10MB
	private static final long MAX_FILE_SIZE_FOR_DISK_CACHE = 50 * 1024 * 1024; // 50MB
	private static final Path configFile = Paths.get(Minecraft.getInstance().gameDirectory.getAbsolutePath(), "config", "regionvisualizer.properties");
    
    // ========================================
    // VARIABLES DE ESTADO PRINCIPALES
    // ========================================
    
    // Audio streams y clips
    private static AudioInputStream currentAudioStream = null;
    private static Clip currentClip = null;
    private static AudioInputStream previousAudioStream = null;
    private static Clip previousClip = null;
    private static FloatControl volumeControl = null;
    private static FloatControl previousVolumeControl = null;
    
    // Control de estado
    private static final AtomicBoolean isPlaying = new AtomicBoolean(false);
    private static final AtomicBoolean isPreviousPlaying = new AtomicBoolean(false);
    private static volatile boolean shouldStopPrevious = false;
    private static boolean initialized = false;
    
    // Sincronización y threading
    private static final Object audioLock = new Object();
    private static final ExecutorService fadeExecutor = Executors.newFixedThreadPool(2);
    
    // Configuración de audio
    private static float modVolume = 0.85f;
    private static float maxModVolume = 0.85f;
    private static float fadeDuration = 5.0f;
    private static long fadeInterval = 50;
    private static float fadeInStart = 0.45f;
    
    // Sistema de archivos y cache HÍBRIDO
    private static Path musicFolder = null;
    private static Path diskCacheFolder = null;
    private static final ConcurrentHashMap<String, CachedAudioData> ramCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, DiskCachedAudioInfo> diskCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> accessTimes = new ConcurrentHashMap<>();

    
    // ========================================
    // MÉTODOS PÚBLICOS PRINCIPALES (API)
    // ========================================
    
    /**
     * Inicializa el sistema de música
     */
    public static void initialize() {
        if (initialized) return;

        synchronized (audioLock) {
            try {
                setupMusicFolder();
                debugAudioSystem();
                initializeAudioFormats();
                validateAudioSystem();
                loadConfig();
                
                initialized = true;
                listAvailableFiles();
                System.out.println("[RegionVisualizer] ✅ Sistema de música inicializado correctamente");

            } catch (Exception e) {
                System.err.println("[RegionVisualizer] ❌ Error inicializando sistema de música: " + e.getMessage());
                sendMessageSync("❌ Error inicializando sistema de música: " + e.getMessage(), ChatFormatting.RED);
                e.printStackTrace();
            }
        }
    }
    
    /**
     * Reproduce un archivo de música
     */
    public static void play(String filename, boolean loop, boolean fade) {
        synchronized (audioLock) {
            playInternal(filename, loop, fade);
        }
    }
    
    /**
     * Detiene la música actual
     */
    public static void stop(boolean fade) {
        synchronized (audioLock) {
            stopInternal(fade);
        }
    }
    
    /**
     * Pausa la música (solo en singleplayer)
     */
    public static void pauseMusic(boolean fade) {
        if (!Minecraft.getInstance().isSingleplayer()) {
            System.out.println("[RegionVisualizer] 🚫 Pausa de música ignorada: no está en singleplayer");
            return;
        }
        synchronized (audioLock) {
            if (currentClip == null || !isPlaying.get()) {
                System.out.println("[RegionVisualizer] ⚠️ No hay música reproduciéndose para pausar");
                return;
            }
            stopInternal(fade);
            System.out.println("[RegionVisualizer] 🎵 Música pausada en singleplayer, fade=" + fade);
        }
    }
    
    /**
     * Reanuda la música basada en la región actual del jugador
     */
    public static void resumeMusic(LocalPlayer player) {
        System.out.println("[RegionVisualizer] 🔄 Iniciando resumeMusic para jugador: " + (player != null ? player.getName().getString() : "null"));
        if (!Minecraft.getInstance().isSingleplayer()) {
            System.out.println("[RegionVisualizer] 🚫 Reanudación de música ignorada: no está en singleplayer");
            return;
        }
        
        synchronized (audioLock) {
            if (isPlaying.get() && currentClip != null) {
                System.out.println("[RegionVisualizer] ⚠️ No se puede reanudar música: ya está reproduciendo");
                return;
            }
            
            if (player == null) {
                System.out.println("[RegionVisualizer] ⚠️ Jugador es null, no se puede reanudar música");
                return;
            }
            
            Level level = player.level();
            String regionName = RegionVisualizer.getCurrentRegion(level, player.blockPosition());
            
            if (regionName != null) {
                RegionManager regionManager = RegionVisualizer.INSTANCE.getRegionManager();
                Optional<Region> regionOpt = regionManager.getRegionByName(regionName);
                
                if (regionOpt.isPresent()) {
                    Region region = regionOpt.get();
                    System.out.println("[RegionVisualizer] 🎵 Reanudando música para región: " + regionName);
                    
                    // Limpiar recursos y reinicializar
                    cleanupResources();
                    cleanupPreviousResources();
                    initialized = false;
                    initialize();
                    
                    playInternal(region.getMusicFile(), region.isLoopEnabled(), region.isFadeEnabled());
                } else {
                    System.out.println("[RegionVisualizer] ⚠️ No se encontró región: " + regionName);
                    sendMessageSync("⚠️ Región no encontrada: " + regionName, ChatFormatting.YELLOW);
                }
            } else {
                System.out.println("[RegionVisualizer] ⚠️ No estás en una región con música");
                sendMessageSync("⚠️ No estás en una región con música", ChatFormatting.YELLOW);
            }
        }
    }
    
    // ========================================
    // CONTROL DE VOLUMEN
    // ========================================
    
    public static float getCurrentVolume() {
        float minVolume = fadeInStart * maxModVolume;
        return (modVolume - minVolume) / (maxModVolume - minVolume);
    }

    public static void setVolume(float sliderValue) {
        synchronized (audioLock) {
            float minVolume = fadeInStart * maxModVolume;
            modVolume = minVolume + (maxModVolume - minVolume) * sliderValue;
            modVolume = Math.max(minVolume, Math.min(maxModVolume, modVolume));
            setClipVolume(modVolume);
            saveConfig();
            System.out.println("[RegionVisualizer] 🔊️ Volumen del mod establecido a: " + (modVolume * 100) + "%");
        }
    }
    
    // ========================================
    // GESTIÓN DE COMANDOS
    // ========================================
    
    public static void handleCommand(String command) {
        synchronized (audioLock) {
            try {
                System.out.println("[RegionVisualizer] 📥 Comando recibido: " + command);

                if (command.startsWith("VOLUME:")) {
                    handleVolumeCommand(command);
                } else if (command.equals("GET_VOLUME")) {
                    handleGetVolumeCommand();
                } else if (command.equals("CONFIG")) {
                    handleConfigCommand();
                } else if (command.startsWith("MUSIC:")) {
                    handleMusicCommand(command);
                } else if (command.startsWith("STOP:")) {
                    handleStopCommand(command);
                } else {
                    handleNormalCommands(command);
                }
            } catch (Exception e) {
                System.err.println("[RegionVisualizer] ❌ Error manejando comando: " + e.getMessage());
                sendMessageSync("❌ Error manejando comando: " + e.getMessage(), ChatFormatting.RED);
                e.printStackTrace();
            }
        }
    }
    
    // ========================================
    // UTILIDADES PÚBLICAS
    // ========================================
    
    public static void listAvailableFiles() {
        try {
            if (!Files.exists(musicFolder)) {
                sendMessageSync("⚠️ Carpeta de música no encontrada: " + musicFolder, ChatFormatting.YELLOW);
                return;
            }
            
            sendMessageSync("📂 Archivos de música disponibles:", ChatFormatting.GOLD);
            List<Path> files = Files.list(musicFolder)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase();
                        for (String format : SUPPORTED_FORMATS) {
                            if (name.endsWith(format)) {
                                return true;
                            }
                        }
                        return false;
                    })
                    .collect(Collectors.toList());

            if (files.isEmpty()) {
                sendMessageSync("  • No se encontraron archivos de música.", ChatFormatting.YELLOW);
                sendMessageSync("  💡 Formatos soportados: WAV, OGG", ChatFormatting.AQUA);
            } else {
                for (Path file : files) {
                    String filename = file.getFileName().toString();
                    long size = Files.size(file);
                    String sizeStr = formatFileSize(size);
                    String extension = getFileExtension(filename).toLowerCase();
                    boolean supported = isFormatSupported(extension);
                    String status = supported ? "✅" : "⚠️";
                    
                    sendMessageSync("  • " + filename + " (" + sizeStr + ") " + status, 
                        supported ? ChatFormatting.GRAY : ChatFormatting.YELLOW);
                }
            }

            sendMessageSync("💡 Usa el comando /playmusic o configura regiones para reproducir música.", ChatFormatting.AQUA);
        } catch (Exception e) {
            System.err.println("[RegionVisualizer] ❌ Error listando archivos: " + e.getMessage());
            sendMessageSync("❌ Error listando archivos: " + e.getMessage(), ChatFormatting.RED);
            e.printStackTrace();
        }
    }
    
    public static boolean isSystemHealthy() {
        synchronized (audioLock) {
            boolean healthy = initialized && 
                             musicFolder != null && 
                             Files.exists(musicFolder) &&
                             !fadeExecutor.isShutdown();
            
            if (!healthy) {
                System.err.println("[RegionVisualizer] ⚠️ Sistema de música no está saludable");
            }
            
            return healthy;
        }
    }
    
    public static void shutdown() {
        synchronized (audioLock) {
            isPreviousPlaying.set(false);
            stopInternal(true);
            cleanupPreviousResources();
            
            // IMPORTANTE: NO limpiar cache de disco en shutdown normal
            // Solo guardar el índice para persistencia
            if (diskCache.size() > 0) {
                saveDiskCacheIndex();
                System.out.println("[Cache] 💾 Índice guardado para próxima sesión");
            }
            
            // RAM se limpiará automáticamente al cerrar la JVM
            
            initialized = false;
            System.out.println("[RegionVisualizer] 🎵 Sistema de música cerrado");
        }
    }
    
    public static void forceInitialize() {
        synchronized (audioLock) {
            isPreviousPlaying.set(false);
            stopInternal(true);
            cleanupPreviousResources();
            
            // NUEVO: No limpiar cache de disco en reinicialización forzada
            // Solo limpiar RAM cache
            System.out.println("[Cache] 🔄 Reinicialización forzada - manteniendo cache de disco");
            ramCache.clear(); // Solo limpiar RAM
            
            initialized = false;
            initialize();
            System.out.println("[RegionVisualizer] 🎵 Sistema de música reinicializado");
        }
    }
    
    public static void onPlayerLoggedOut() {
        synchronized (audioLock) {
            System.out.println("[RegionVisualizer] 🚪 Jugador salió del mundo - limpiando sistema de música");
            if (Minecraft.getInstance().isSingleplayer()) {
                stopInternal(true);
            } else {
                stopInternal(false);
            }
            cleanupPreviousResources();
            
            // Guardar estado del cache antes del logout
            if (diskCache.size() > 0) {
                saveDiskCacheIndex();
            }
            
            shutdown();
            System.out.println("[RegionVisualizer] ✅ Sistema de música limpiado tras salir del mundo");
        }
    }
    
    // ========================================
    // MÉTODOS INTERNOS DE REPRODUCCIÓN
    // ========================================
    
    private static void playInternal(String filename, boolean loop, boolean fade) {
        synchronized (audioLock) {
            System.out.println("[RegionVisualizer] 🎵 Iniciando playInternal: " + filename);

            // Preparar transición de clips
            prepareClipTransition(fade);

            // Validar archivo
            if (!validateMusicFile(filename)) {
                return;
            }

            Path filePath = musicFolder.resolve(filename);
            
            try {
                long startTime = System.currentTimeMillis();
                
                // Preparar audio
                prepareAudioClipOptimized(filePath, filename);
                
                // Configurar controles
                setupVolumeControl(filename, fade);
                
                // Configurar loop
                if (loop) {
                    currentClip.loop(Clip.LOOP_CONTINUOUSLY);
                    System.out.println("[RegionVisualizer] 🔄 Bucle activado para: " + filename);
                }

                isPlaying.set(true);
                shouldStopPrevious = false;

                // Iniciar reproducción
                if (fade && volumeControl != null) {
                    fadeIn();
                } else {
                    setClipVolume(modVolume);
                    currentClip.setFramePosition(0);
                    currentClip.start();
                }

                long totalTime = System.currentTimeMillis() - startTime;
                String fileExt = getFileExtension(filename).toUpperCase();
                sendMessageSync("🎵 Reproduciendo: " + filename + " (" + fileExt + ") en " + totalTime + "ms", ChatFormatting.GREEN);

            } catch (Exception e) {
                handlePlaybackError(e, filename);
            }
        }
    }
    
    private static void stopInternal(boolean fade) {
        synchronized (audioLock) {
            // Cancelar fade-out anterior
            if (isPreviousPlaying.get()) {
                isPreviousPlaying.set(false);
                cleanupPreviousResources();
            }

            if (currentClip == null || !isPlaying.get()) {
                System.out.println("[RegionVisualizer] ⚠️ No hay música reproduciéndose para detener");
                cleanupPreviousResources();
                return;
            }
            
            shouldStopPrevious = true;

            if (fade && volumeControl != null && currentClip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                // Mover clip actual a previous para fade-out
                previousClip = currentClip;
                previousAudioStream = currentAudioStream;
                previousVolumeControl = volumeControl;
                currentClip = null;
                currentAudioStream = null;
                volumeControl = null;
                isPlaying.set(false);
                
                fadeOutPrevious();
            } else {
                cleanupResources();
                System.out.println("[RegionVisualizer] 🎵 Música detenida, fade=" + fade);
            }
        }
    }
    
    // ========================================
    // SISTEMA DE FADE
    // ========================================
    
    private static void fadeIn() {
        fadeExecutor.submit(() -> {
            float targetVolume = modVolume;
            float startVolume = fadeInStart * modVolume;
            long startTime = System.currentTimeMillis();
            long endTime = startTime + (long)(fadeDuration * 1000);

            synchronized (audioLock) {
                if (currentClip == null || !isPlaying.get()) {
                    System.out.println("[RegionVisualizer] ⚠️ Fade-in cancelado: clip no disponible");
                    cleanupResources();
                    return;
                }

                setClipVolume(startVolume);
                System.out.println("[RegionVisualizer] 🎵 Fade-in iniciado: " + (startVolume * 100) + "% ➜ " + (targetVolume * 100) + "%");
                currentClip.setFramePosition(0);
                currentClip.start();
            }

            while (System.currentTimeMillis() < endTime && isPlaying.get()) {
                synchronized (audioLock) {
                    if (currentClip == null || !isPlaying.get()) {
                        cleanupResources();
                        return;
                    }
                    
                    float progress = (System.currentTimeMillis() - startTime) / (float)(fadeDuration * 1000);
                    progress = Math.min(1.0f, Math.max(0.0f, progress));
                    float currentVolume = startVolume + (targetVolume - startVolume) * progress;
                    currentVolume = Math.min(currentVolume, modVolume);
                    setClipVolume(currentVolume);
                }
                
                try {
                    Thread.sleep(fadeInterval);
                } catch (InterruptedException e) {
                    cleanupResources();
                    return;
                }
            }

            synchronized (audioLock) {
                if (isPlaying.get()) {
                    setClipVolume(targetVolume);
                    System.out.println("[RegionVisualizer] ✅ Fade-in completado: " + (targetVolume * 100) + "%");
                }
            }
        });
    }

    private static void fadeOutPrevious() {
        fadeExecutor.submit(() -> {
            float startVolume = modVolume;
            long startTime = System.currentTimeMillis();
            long endTime = startTime + (long)(fadeDuration * 1000);

            synchronized (audioLock) {
                if (previousClip == null || !previousClip.isOpen() || !previousClip.isRunning()) {
                    cleanupPreviousResources();
                    return;
                }
                
                isPreviousPlaying.set(true);
                setPreviousClipVolume(startVolume);
                System.out.println("[RegionVisualizer] 🎵 Fade-out anterior iniciado: " + (startVolume * 100) + "% ➜ 0%");
            }

            while (System.currentTimeMillis() < endTime && isPreviousPlaying.get()) {
                synchronized (audioLock) {
                    if (previousClip == null || !previousClip.isOpen() || !previousClip.isRunning() || !isPreviousPlaying.get()) {
                        break;
                    }
                    
                    float progress = (System.currentTimeMillis() - startTime) / (float)(fadeDuration * 1000);
                    progress = Math.min(1.0f, Math.max(0.0f, progress));
                    float currentVolume = startVolume * (1.0f - progress);
                    setPreviousClipVolume(currentVolume);
                }
                
                try {
                    Thread.sleep(fadeInterval);
                } catch (InterruptedException e) {
                    break;
                }
            }

            synchronized (audioLock) {
                if (previousClip != null && previousClip.isOpen()) {
                    setPreviousClipVolume(0.0f);
                }
                isPreviousPlaying.set(false);
                cleanupPreviousResources();
                System.out.println("[RegionVisualizer] ✅ Fade-out anterior completado");
            }
        });
    }
    
    // ========================================
    // GESTIÓN DE VOLUMEN INTERNO
    // ========================================
    
    private static void setClipVolume(float volume) {
        synchronized (audioLock) {
            if (volumeControl == null || currentClip == null || !currentClip.isOpen()) {
                return;
            }
            
            volume = Math.min(volume, maxModVolume);
            float min = volumeControl.getMinimum();
            float max = volumeControl.getMaximum();
            float gain = min + (max - min) * volume;
            gain = Math.max(min, Math.min(max, gain));
            
            try {
                volumeControl.setValue(gain);
            } catch (Exception e) {
                System.err.println("[RegionVisualizer] ⚠️ Error ajustando volumen actual: " + e.getMessage());
            }
        }
    }

    private static void setPreviousClipVolume(float volume) {
        synchronized (audioLock) {
            if (previousVolumeControl == null || previousClip == null || !previousClip.isOpen()) {
                return;
            }
            
            volume = Math.min(volume, maxModVolume);
            float min = previousVolumeControl.getMinimum();
            float max = previousVolumeControl.getMaximum();
            float gain = min + (max - min) * volume;
            gain = Math.max(min, Math.min(max, gain));
            
            try {
                previousVolumeControl.setValue(gain);
            } catch (Exception e) {
                System.err.println("[RegionVisualizer] ⚠️ Error ajustando volumen anterior: " + e.getMessage());
            }
        }
    }
    
    // ========================================
    // LIMPIEZA DE RECURSOS
    // ========================================
    
    private static void cleanupResources() {
        synchronized (audioLock) {
            if (currentClip != null) {
                try {
                    currentClip.stop();
                    currentClip.flush();
                    currentClip.close();
                } catch (Exception e) {
                    System.err.println("[RegionVisualizer] ⚠️ Error cerrando clip actual: " + e.getMessage());
                }
                currentClip = null;
            }
            
            if (currentAudioStream != null) {
                try {
                    currentAudioStream.close();
                } catch (IOException e) {
                    System.err.println("[RegionVisualizer] ⚠️ Error cerrando audio stream actual: " + e.getMessage());
                }
                currentAudioStream = null;
            }
            
            isPlaying.set(false);
            volumeControl = null;
            System.out.println("[RegionVisualizer] 🎵 Recursos de audio actuales liberados");
        }
    }

    private static void cleanupPreviousResources() {
        synchronized (audioLock) {
            isPreviousPlaying.set(false);
            
            if (previousClip != null) {
                try {
                    previousClip.stop();
                    previousClip.flush();
                    previousClip.close();
                } catch (Exception e) {
                    System.err.println("[RegionVisualizer] ⚠️ Error cerrando clip anterior: " + e.getMessage());
                }
                previousClip = null;
            }
            
            if (previousAudioStream != null) {
                try {
                    previousAudioStream.close();
                } catch (IOException e) {
                    System.err.println("[RegionVisualizer] ⚠️ Error cerrando audio stream anterior: " + e.getMessage());
                }
                previousAudioStream = null;
            }
            
            previousVolumeControl = null;
            shouldStopPrevious = false;
            System.out.println("[RegionVisualizer] 🎵 Recursos de audio anteriores liberados");
        }
    }
    
    // ========================================
    // GESTIÓN DE CONFIGURACIÓN
    // ========================================
    
    private static void loadConfig() {
        try {
            Properties props = new Properties();
            if (Files.exists(configFile)) {
                try (FileInputStream in = new FileInputStream(configFile.toFile())) {
                    props.load(in);
                    
                    modVolume = Float.parseFloat(props.getProperty("modVolume", "0.85"));
                    modVolume = Math.max(0.0f, Math.min(maxModVolume, modVolume));

                    maxModVolume = Float.parseFloat(props.getProperty("maxModVolume", "0.85"));
                    maxModVolume = Math.max(0.0f, Math.min(1.0f, maxModVolume));

                    fadeDuration = Float.parseFloat(props.getProperty("fadeDuration", "5.0"));
                    fadeDuration = Math.max(0.1f, fadeDuration);

                    fadeInterval = Long.parseLong(props.getProperty("fadeInterval", "50"));
                    fadeInterval = Math.max(10L, Math.min(500L, fadeInterval));

                    fadeInStart = Float.parseFloat(props.getProperty("fadeInStart", "0.45"));
                    fadeInStart = Math.max(0.0f, Math.min(maxModVolume, fadeInStart));

                    System.out.println("[RegionVisualizer] ✅ Configuración cargada correctamente");
                }
            } else {
                Files.createDirectories(configFile.getParent());
                saveConfig();
            }
        } catch (Exception e) {
            System.err.println("[RegionVisualizer] ❌ Error cargando configuración: " + e.getMessage());
            resetToDefaults();
            saveConfig();
        }
    }

    public static void saveConfig() {
        try {
            Properties props = new Properties();
            props.setProperty("modVolume", String.valueOf(modVolume));
            props.setProperty("maxModVolume", String.valueOf(maxModVolume));
            props.setProperty("fadeDuration", String.valueOf(fadeDuration));
            props.setProperty("fadeInterval", String.valueOf(fadeInterval));
            props.setProperty("fadeInStart", String.valueOf(fadeInStart));
            
            try (FileOutputStream out = new FileOutputStream(configFile.toFile())) {
                props.store(out, "RegionVisualizer Configuration");
                System.out.println("[RegionVisualizer] ✅ Configuración guardada");
            }
        } catch (Exception e) {
            System.err.println("[RegionVisualizer] ❌ Error guardando configuración: " + e.getMessage());
            sendMessageSync("❌ Error guardando configuración: " + e.getMessage(), ChatFormatting.RED);
        }
    }

    private static void resetToDefaults() {
        modVolume = 0.85f;
        maxModVolume = 0.85f;
        fadeDuration = 5.0f;
        fadeInterval = 50L;
        fadeInStart = 0.45f;
        System.out.println("[RegionVisualizer] 🔄 Configuración restablecida a valores por defecto");
    }
    // ========================================
    // SISTEMA DE AUDIO CACHE HIBRIDA (RAM & DISK)
    // ========================================

    private static class DiskCachedAudioInfo {
        final String filename;
        final String originalPath;
        final String cacheFileName; 
        final AudioFormat format;
        final long fileSize;
        final long creationTime;
        final String originalHash;
        
        DiskCachedAudioInfo(String filename, String originalPath, String cacheFileName, 
                           AudioFormat format, long fileSize, String originalHash) {
            this.filename = filename;
            this.originalPath = originalPath;
            this.cacheFileName = cacheFileName;
            this.format = format;
            this.fileSize = fileSize;
            this.creationTime = System.currentTimeMillis();
            this.originalHash = originalHash;
        }
 }

 private static class CacheMetadata {
     String filename;
     String originalPath;
     String originalHash;
     long creationTime;
     // AudioFormat serialization
     String formatEncoding;
     float sampleRate;
     int sampleSizeInBits;
     int channels;
     int frameSize;
     float frameRate;
     boolean bigEndian;
     
     public CacheMetadata() {} // Constructor para Gson
     
     public CacheMetadata(DiskCachedAudioInfo info) {
         this.filename = info.filename;
         this.originalPath = info.originalPath;
         this.originalHash = info.originalHash;
         this.creationTime = info.creationTime;
         
         AudioFormat fmt = info.format;
         this.formatEncoding = fmt.getEncoding().toString();
         this.sampleRate = fmt.getSampleRate();
         this.sampleSizeInBits = fmt.getSampleSizeInBits();
         this.channels = fmt.getChannels();
         this.frameSize = fmt.getFrameSize();
         this.frameRate = fmt.getFrameRate();
         this.bigEndian = fmt.isBigEndian();
     }
     
     public AudioFormat toAudioFormat() {
         AudioFormat.Encoding encoding = new AudioFormat.Encoding(formatEncoding);
         return new AudioFormat(encoding, sampleRate, sampleSizeInBits, 
                              channels, frameSize, frameRate, bigEndian);
     }
 }

 // ========================================
 // INICIALIZACIÓN DEL CACHE HÃBRIDO
 // ========================================
 private static class CachedAudioData {
     final byte[] audioData;
     final AudioFormat format;
     final long timestamp;
     final String filename;
     
     CachedAudioData(byte[] data, AudioFormat format, String filename) {
         this.audioData = data.clone();
         this.format = format;
         this.timestamp = System.currentTimeMillis();
         this.filename = filename;
     }
     
     AudioInputStream createStream() {
         ByteArrayInputStream byteStream = new ByteArrayInputStream(audioData);
         return new AudioInputStream(byteStream, format, audioData.length / format.getFrameSize());
     }
 }
 
 private static void setupCacheSystem() throws IOException {
     // Crear carpeta de cache en disco
     File gameDir = Minecraft.getInstance().gameDirectory;
     diskCacheFolder = Paths.get(gameDir.getAbsolutePath(), "music", "cache");
     
     if (!Files.exists(diskCacheFolder)) {
         Files.createDirectories(diskCacheFolder);
         System.out.println("[Cache] âœ… Carpeta de cache creada: " + diskCacheFolder);
         createCacheReadme();
     }
     
     // IMPORTANTE: RAM siempre empieza vacía tras reinicio
     ramCache.clear();
     accessTimes.clear();
     
     // Limpiar cache de disco antiguo (>7 días)
     cleanupOldDiskCache();
     
     // PERSISTENCIA: Cargar índice de cache de disco existente
     int diskFilesLoaded = loadDiskCacheIndex();
     
     System.out.println("[Cache]   Sistema híbrido inicializado");
     System.out.println("[Cache]   RAM: 0/" + MAX_RAM_CACHED_FILES + " archivos (vacía tras reinicio)");
     System.out.println("[Cache]   DISCO: " + diskFilesLoaded + "/" + MAX_DISK_CACHED_FILES + " archivos (persistente)");
     
     if (diskFilesLoaded > 0) {
         System.out.println("[Cache] " + diskFilesLoaded + " archivos pre-procesados disponibles desde sesión anterior");
     }
 }

 // ========================================
 // LÓGICA PRINCIPAL DE CACHE
 // ========================================

 private static AudioInputStream getCachedAudioStream(String filename, Path originalPath) throws Exception {
     String cacheKey = originalPath.toString();
     updateAccessTime(cacheKey);
     
     // 1. NIVEL 1: RAM Cache (más rápido - ~1ms)
     CachedAudioData ramData = ramCache.get(cacheKey);
     if (ramData != null) {
         System.out.println("[Cache] 🌩️ RAM HIT: " + filename + " (instantáneo)");
         return ramData.createStream();
     }
     
     // 2. NIVEL 2: Disco Cache (rápido - ~10-50ms)
     DiskCachedAudioInfo diskInfo = diskCache.get(cacheKey);
     if (diskInfo != null && isValidDiskCache(diskInfo, originalPath)) {
         System.out.println("[Cache] 🌩️ DISK HIT: " + filename + " (cargando desde disco...)");
         AudioInputStream stream = loadFromDiskCache(diskInfo);
         
         // PROMOCIÓN: Si hay espacio en RAM, subir archivo frecuente
         promoteToRamIfSuitable(cacheKey, stream, diskInfo);
         
         return stream;
     }
     
     // 3. NIVEL 3: Procesar archivo original (lento - ~100-500ms)
     System.out.println("[Cache] 🌩️ COMPLETE MISS: " + filename + " - Procesando desde original...");
     return processAndCache(filename, originalPath, cacheKey);
 }

 private static AudioInputStream processAndCache(String filename, Path originalPath, String cacheKey) throws Exception {
     long startTime = System.currentTimeMillis();
     
     // Procesar archivo original
     AudioInputStream rawStream = AudioSystem.getAudioInputStream(originalPath.toFile());
     AudioInputStream processedStream;
     
     String extension = getFileExtension(filename).toLowerCase();
     if (extension.equals(".ogg")) {
         processedStream = OggOptimizer.optimizeOggStream(rawStream, originalPath);
     } else {
         processedStream = convertToPlayableFormatLazy(rawStream);
     }
     
     long fileSize = Files.size(originalPath);
     
     // Crear stream duplicado para poder usar uno para cache y otro para retorno
     AudioInputStream streamForCache = duplicateStream(processedStream);
     AudioInputStream streamForReturn = duplicateStream(processedStream);
     
     // Decidir tipo de cache basado en tamaño
     if (fileSize <= MAX_FILE_SIZE_FOR_RAM_CACHE && ramCache.size() < MAX_RAM_CACHED_FILES) {
         // Cache en RAM para archivos pequeños
         storeInRamCache(cacheKey, streamForCache, filename);
         System.out.println("[Cache] Almacenado en RAM: " + filename);
     } else if (fileSize <= MAX_FILE_SIZE_FOR_DISK_CACHE) {
         // Cache en disco para archivos grandes
         storeToDiskCache(cacheKey, streamForCache, filename, originalPath);
         System.out.println("[Cache] Almacenado en DISCO: " + filename);
     } else {
         System.out.println("[Cache] Archivo demasiado grande para cache: " + filename + 
             " (" + formatFileSize(fileSize) + ")");
     }
     
     long processingTime = System.currentTimeMillis() - startTime;
     System.out.println("[Cache] ðŸ”„ Procesado en " + processingTime + "ms: " + filename);
     
     return streamForReturn;
 }

 private static AudioInputStream duplicateStream(AudioInputStream original) throws IOException {
     if (!original.markSupported()) {
         // Si no soporta mark, convertir a ByteArray
         ByteArrayOutputStream buffer = new ByteArrayOutputStream();
         byte[] tempBuffer = new byte[8192];
         int bytesRead;
         
         while ((bytesRead = original.read(tempBuffer)) != -1) {
             buffer.write(tempBuffer, 0, bytesRead);
         }
         
         byte[] audioData = buffer.toByteArray();
         ByteArrayInputStream byteStream = new ByteArrayInputStream(audioData);
         return new AudioInputStream(byteStream, original.getFormat(), 
             audioData.length / original.getFormat().getFrameSize());
     } else {
         // Si soporta mark, hacer reset
         original.mark(Integer.MAX_VALUE);
         return original;
     }
 }

 // ========================================
 // GESTIÓN DE CACHE EN RAM
 // ========================================

 private static void storeInRamCache(String cacheKey, AudioInputStream stream, String filename) throws IOException {
     // Evict si es necesario
     evictOldestRamCacheIfNeeded();
     
     // Crear copia del stream para cache
     ByteArrayOutputStream buffer = new ByteArrayOutputStream();
     byte[] tempBuffer = new byte[8192];
     int bytesRead;
     
     // Leer stream completo
     while ((bytesRead = stream.read(tempBuffer)) != -1) {
         buffer.write(tempBuffer, 0, bytesRead);
     }
     
     byte[] audioData = buffer.toByteArray();
     CachedAudioData cachedData = new CachedAudioData(audioData, stream.getFormat(), filename);
     ramCache.put(cacheKey, cachedData);
     
     System.out.println("[Cache] RAM almacenado: " + filename + 
         " (" + formatFileSize(audioData.length) + ")");
 }

 private static void evictOldestRamCacheIfNeeded() {
     if (ramCache.size() < MAX_RAM_CACHED_FILES) return;
     
     String oldestKey = findOldestAccessedKey(ramCache.keySet());
     if (oldestKey != null) {
         CachedAudioData removed = ramCache.remove(oldestKey);
         // NO eliminamos accessTimes para mantener historial
         System.out.println("[Cache] RAM evitado (aún en disco): " + removed.filename);
         
         // Verificar si existe en disco
         boolean inDisk = diskCache.values().stream()
             .anyMatch(info -> info.originalPath.equals(oldestKey));
         if (inDisk) {
             System.out.println("[Cache]Archivo disponible en disco: " + removed.filename);
         }
     }
 }

 // ========================================
 // GESTIÓN DE CACHE EN DISCO
 // ========================================

 private static void storeToDiskCache(String cacheKey, AudioInputStream stream, 
                                    String filename, Path originalPath) throws Exception {
     evictOldestDiskCacheIfNeeded();
     
     String originalHash = calculateFileHash(originalPath);
     String cacheFileName = generateCacheFileName(filename, originalHash);
     Path cacheFilePath = diskCacheFolder.resolve(cacheFileName + ".cache");
     Path metaFilePath = diskCacheFolder.resolve(cacheFileName + ".meta");
     
     // Guardar datos de audio procesados
     try (FileOutputStream fos = new FileOutputStream(cacheFilePath.toFile());
          BufferedOutputStream bos = new BufferedOutputStream(fos)) {
         
         byte[] buffer = new byte[8192];
         int bytesRead;
         
         while ((bytesRead = stream.read(buffer)) != -1) {
             bos.write(buffer, 0, bytesRead);
         }
     }
     
     // Guardar metadatos
     long fileSize = Files.size(cacheFilePath);
     DiskCachedAudioInfo info = new DiskCachedAudioInfo(
         filename, cacheKey, cacheFileName, stream.getFormat(), fileSize, originalHash);
     
     saveAudioMetadata(metaFilePath, info);
     
     // Actualizar índice en memoria
     diskCache.put(cacheKey, info);
     
     System.out.println("[Cache] DISK almacenado: " + filename + 
         " (" + formatFileSize(fileSize) + ")");
     
     saveDiskCacheIndex();
 }

 private static void saveAudioMetadata(Path metaFilePath, DiskCachedAudioInfo info) throws IOException {
     CacheMetadata metadata = new CacheMetadata(info);
     Gson gson = new Gson();
     String json = gson.toJson(metadata);
     
     Files.writeString(metaFilePath, json);
 }

 private static AudioInputStream loadFromDiskCache(DiskCachedAudioInfo info) throws Exception {
     Path cacheFilePath = diskCacheFolder.resolve(info.cacheFileName + ".cache");
     
     if (!Files.exists(cacheFilePath)) {
         // Cache inválido, remover del índice
         diskCache.remove(info.originalPath);
         throw new FileNotFoundException("Cache archivo no encontrado: " + cacheFilePath);
     }
     
     FileInputStream fis = new FileInputStream(cacheFilePath.toFile());
     return new AudioInputStream(fis, info.format, 
         info.fileSize / info.format.getFrameSize());
 }

 private static void promoteToRamIfSuitable(String cacheKey, AudioInputStream stream, 
                                          DiskCachedAudioInfo diskInfo) {
     try {
         if (diskInfo.fileSize <= MAX_FILE_SIZE_FOR_RAM_CACHE && 
             ramCache.size() < MAX_RAM_CACHED_FILES && 
             !ramCache.containsKey(cacheKey)) {
             
             // Crear stream duplicado para RAM sin afectar el original
             AudioInputStream ramStream = loadFromDiskCache(diskInfo);
             storeInRamCache(cacheKey, ramStream, diskInfo.filename);
             System.out.println("[Cache] Promovido a RAM: " + diskInfo.filename);
         }
     } catch (Exception e) {
         System.err.println("[Cache] Error promoviendo a RAM: " + e.getMessage());
     }
 }

 // ========================================
 // UTILIDADES DE CACHE
 // ========================================

 private static void updateAccessTime(String cacheKey) {
     accessTimes.put(cacheKey, System.currentTimeMillis());
 }

 private static String findOldestAccessedKey(Set<String> keySet) {
	    long oldestTime = Long.MAX_VALUE;
	    String oldestKey = null;
	    
	    // PASO 1: Buscar usando accessTimes (más preciso)
	    for (String key : keySet) {
	        Long accessTime = accessTimes.get(key);
	        if (accessTime != null && accessTime < oldestTime) {
	            oldestTime = accessTime;
	            oldestKey = key;
	        }
	    }
	    
	    // PASO 2: Si no se encuentra en accessTimes, usar timestamp del cache como fallback
	    if (oldestKey == null) {
	        System.out.println("[Cache] Usando timestamp como fallback para LRU");
	        oldestTime = Long.MAX_VALUE; // Reset para segunda búsqueda
	        
	        for (String key : keySet) {
	            CachedAudioData data = ramCache.get(key);
	            if (data != null && data.timestamp < oldestTime) {
	                oldestTime = data.timestamp;
	                oldestKey = key;
	                System.out.println("[Cache] Candidato por timestamp: " + data.filename + 
	                    " (creado hace " + (System.currentTimeMillis() - data.timestamp) / 60000 + " min)");
	            }
	        }
	    }
	    
	    // PASO 3: Si aún no hay candidato, usar el primero disponible
	    if (oldestKey == null && !keySet.isEmpty()) {
	        oldestKey = keySet.iterator().next();
	        System.out.println("[Cache] Usando primer elemento disponible como fallback: " + oldestKey);
	    }
	    
	    if (oldestKey != null) {
	        System.out.println("[Cache] Seleccionado para eviction: " + oldestKey);
	    }
	    
	    return oldestKey;
	}

 private static boolean isValidDiskCache(DiskCachedAudioInfo info, Path originalPath) {
     try {
         if (!Files.exists(originalPath)) return false;
         
         String currentHash = calculateFileHash(originalPath);
         return info.originalHash.equals(currentHash);
     } catch (Exception e) {
         System.err.println("[Cache] Error validando cache: " + e.getMessage());
         return false;
     }
 }

 private static String calculateFileHash(Path filePath) throws Exception {
     MessageDigest md = MessageDigest.getInstance("SHA-256");
     
     try (FileInputStream fis = new FileInputStream(filePath.toFile());
          DigestInputStream dis = new DigestInputStream(fis, md)) {
         
         byte[] buffer = new byte[8192];
         while (dis.read(buffer) != -1) {
             // Solo leer para calcular hash
         }
     }
     
     byte[] hash = md.digest();
     StringBuilder sb = new StringBuilder();
     for (byte b : hash) {
         sb.append(String.format("%02x", b));
     }
     return sb.toString().substring(0, 16); // Solo primeros 16 chars
 }

 private static String generateCacheFileName(String originalName, String hash) {
     // Limpiar nombre de archivo de caracteres problemáticos
     String baseName = originalName.replaceAll("[^a-zA-Z0-9._-]", "_");
     if (baseName.length() > 50) {
         baseName = baseName.substring(0, 50);
     }
     return baseName + "_" + hash;
 }

 // ========================================
 // GESTIÓN DE ÍNDICE DE DISCO
 // ========================================

 private static void saveDiskCacheIndex() {
     try {
         Path indexPath = diskCacheFolder.resolve("cache_index.json");
         
         // Convertir diskCache a formato serializable
         Map<String, CacheMetadata> serializableIndex = new HashMap<>();
         for (Map.Entry<String, DiskCachedAudioInfo> entry : diskCache.entrySet()) {
             serializableIndex.put(entry.getKey(), new CacheMetadata(entry.getValue()));
         }
         
         Gson gson = new Gson();
         String json = gson.toJson(serializableIndex);
         
         Files.writeString(indexPath, json);
         System.out.println("[Cache] Índice guardado: " + diskCache.size() + " entradas");
         
     } catch (Exception e) {
         System.err.println("[Cache] Error guardando índice: " + e.getMessage());
     }
 }

 private static int loadDiskCacheIndex() {
     try {
         Path indexPath = diskCacheFolder.resolve("cache_index.json");
         
         if (Files.exists(indexPath)) {
             String json = Files.readString(indexPath);
             Gson gson = new Gson();
             
             Type type = new TypeToken<Map<String, CacheMetadata>>(){}.getType();
             Map<String, CacheMetadata> loadedIndex = gson.fromJson(json, type);
             
             int validFiles = 0;
             for (Map.Entry<String, CacheMetadata> entry : loadedIndex.entrySet()) {
                 try {
                     CacheMetadata metadata = entry.getValue();
                     Path cacheFile = diskCacheFolder.resolve(metadata.filename + "_" + 
                         metadata.originalHash.substring(0, 16) + ".cache");
                     Path metaFile = diskCacheFolder.resolve(metadata.filename + "_" + 
                         metadata.originalHash.substring(0, 16) + ".meta");
                     
                     if (Files.exists(cacheFile) && Files.exists(metaFile)) {
                         // Reconstruir info
                         DiskCachedAudioInfo info = new DiskCachedAudioInfo(
                             metadata.filename,
                             metadata.originalPath,
                             metadata.filename + "_" + metadata.originalHash.substring(0, 16),
                             metadata.toAudioFormat(),
                             Files.size(cacheFile),
                             metadata.originalHash
                         );
                         
                         diskCache.put(entry.getKey(), info);
                         validFiles++;
                     } else {
                         System.out.println("[Cache] Archivo de cache perdido: " + metadata.filename);
                     }
                 } catch (Exception e) {
                     System.err.println("[Cache] Error cargando entrada: " + e.getMessage());
                 }
             }
             
             System.out.println("[Cache] Índice cargado: " + validFiles + " archivos válidos");
             return validFiles;
             
         } else {
             System.out.println("[Cache] Primera ejecución - no hay índice previo");
             return 0;
         }
     } catch (Exception e) {
         System.err.println("[Cache] Error cargando índice: " + e.getMessage());
         return 0;
     }
 }

 // ========================================
 // LIMPIEZA Y MANTENIMIENTO
 // ========================================

 private static void evictOldestDiskCacheIfNeeded() {
     if (diskCache.size() < MAX_DISK_CACHED_FILES) return;
     
     // Encontrar el archivo menos accedido recientemente
     String oldestKey = null;
     long oldestTime = Long.MAX_VALUE;
     
     for (Map.Entry<String, DiskCachedAudioInfo> entry : diskCache.entrySet()) {
         Long accessTime = accessTimes.get(entry.getKey());
         long timeToUse = (accessTime != null) ? accessTime : entry.getValue().creationTime;
         
         if (timeToUse < oldestTime) {
             oldestTime = timeToUse;
             oldestKey = entry.getKey();
         }
     }
     
     if (oldestKey != null) {
         DiskCachedAudioInfo info = diskCache.remove(oldestKey);
         
         // Eliminar archivos del disco
         try {
             Files.deleteIfExists(diskCacheFolder.resolve(info.cacheFileName + ".cache"));
             Files.deleteIfExists(diskCacheFolder.resolve(info.cacheFileName + ".meta"));
             System.out.println("[Cache] DISK evictado: " + info.filename);
         } catch (Exception e) {
             System.err.println("[Cache] Error eliminando cache: " + e.getMessage());
         }
         
         // Actualizar í.ndice
         saveDiskCacheIndex();
     }
 }

 private static void cleanupOldDiskCache() {
     try {
         long cutoffTime = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L); // 7 dí.as
         
         Files.list(diskCacheFolder)
             .filter(path -> path.toString().endsWith(".cache") || 
                            path.toString().endsWith(".meta"))
             .filter(path -> {
                 try {
                     return Files.getLastModifiedTime(path).toMillis() < cutoffTime;
                 } catch (IOException e) {
                     return true; // Si hay error, eliminar
                 }
             })
             .forEach(path -> {
                 try {
                     Files.delete(path);
                     System.out.println("[Cache] Eliminado archivo antiguo: " + path.getFileName());
                 } catch (IOException e) {
                     System.err.println("[Cache] Error eliminando: " + path);
                 }
             });
     } catch (Exception e) {
         System.err.println("[Cache] Error en limpieza: " + e.getMessage());
     }
 }

 private static void createCacheReadme() {
     try {
         Path readmePath = diskCacheFolder.resolve("README.txt");
         Files.writeString(readmePath,
             "RegionVisualizer Audio Cache\n" +
             "============================\n" +
             "Esta carpeta contiene archivos de audio pre-procesados para mejorar el rendimiento.\n" +
             "\n" +
             "ESTRUCTURA:\n" +
             "- .cache: Datos de audio convertidos y optimizados\n" +
             "- .meta: Metadatos del formato de audio (JSON)\n" +
             "- cache_index.json: Índice principal de archivos cacheados\n" +
             "\n" +
             "FUNCIONAMIENTO:\n" +
             "- RAM Cache: 4 archivos más frecuentes (10MB cada uno)\n" +
             "- Disk Cache: Hasta 50 archivos pre-procesados (50MB cada uno)\n" +
             "- Limpieza automática: Archivos luego de 7 días se eliminan\n" +
             "\n" +
             "NOTA: Estos archivos se generan automáticamente.\n" +
             "Puedes eliminar toda la carpeta sin problemas - se regenerará cuando sea necesario.\n" +
             "\n" +
             "COMANDOS ÚTILES:\n" +
             "- /playcache stats: Ver estadísticas de uso\n" +
             "- /playcache clear: Limpiar todo el cache\n" +
             "- /playcache status: Estado detallado del cache" +
             "(Actualmente estos comandos no existen)");
         
         System.out.println("[Cache] README creado en .minecraft/music/cache");
     } catch (Exception e) {
         System.err.println("[Cache] Error creando README: " + e.getMessage());
     }
 }

 // ========================================
 // ESTADÃSTICAS Y COMANDOS PÃšBLICOS
 // ========================================

 public static void printCacheStats() {
     long ramMemory = ramCache.values().stream()
         .mapToLong(data -> data.audioData.length)
         .sum();
     
     long diskMemory = diskCache.values().stream()
         .mapToLong(info -> info.fileSize)
         .sum();
         
     System.out.println("[Cache] Estadísticas detalladas:");
     System.out.println("  RAM Cache: " + ramCache.size() + "/" + MAX_RAM_CACHED_FILES + 
         " archivos (" + formatFileSize(ramMemory) + ")");
     System.out.println("  Disk Cache: " + diskCache.size() + "/" + MAX_DISK_CACHED_FILES + 
         " archivos (" + formatFileSize(diskMemory) + ")");
     
     if (!ramCache.isEmpty()) {
         System.out.println("  Archivos en RAM:");
         ramCache.values().forEach(data -> 
             System.out.println("    - " + data.filename + " (" + 
                 formatFileSize(data.audioData.length) + ")")
         );
     }
     
     sendMessageSync(" Cache - RAM: " + ramCache.size() + " files (" + 
         formatFileSize(ramMemory) + "), DISK: " + diskCache.size() + " files (" + 
         formatFileSize(diskMemory) + ")", ChatFormatting.AQUA);
         
//     if (diskMemory > 100 * 1024 * 1024) { // >100MB
//         sendMessageSync("Cache de disco grande. Usa '/music cache_clear' si necesitas espacio", 
//             ChatFormatting.YELLOW);
// Falta crear los comandos.
     }

 public static void clearAllCaches() {
     synchronized (audioLock) {
         // Limpiar RAM
         int ramCleared = ramCache.size();
         ramCache.clear();
         
         // Limpiar disco
         int diskCleared = diskCache.size();
         diskCache.clear();
         accessTimes.clear();
         
         // Eliminar archivos del disco
         try {
             Files.list(diskCacheFolder)
                 .filter(path -> path.toString().endsWith(".cache") || 
                                path.toString().endsWith(".meta") ||
                                path.toString().endsWith("cache_index.json"))
                 .forEach(path -> {
                     try {
                         Files.delete(path);
                     } catch (IOException e) {
                         System.err.println("[Cache] Error eliminando: " + path);
                     }
                 });
         } catch (Exception e) {
             System.err.println("[Cache] Error limpiando disco: " + e.getMessage());
         }
         
         System.out.println("[Cache] Cache completamente limpiado:");
         System.out.println("  - RAM: " + ramCleared + " archivos eliminados");
         System.out.println("  - DISCO: " + diskCleared + " archivos eliminados");
         
         sendMessageSync(" Cache completamente limpiado - RAM: " + ramCleared + 
             " files, DISK: " + diskCleared + " files", ChatFormatting.GREEN);
     }
 }

 // TODOS LOS DEPRECATED - mantener para compatibilidad
 public static void clearCache() {
     clearAllCaches();
 }
    
    // ========================================
    // PROCESAMIENTO DE AUDIO
    // ========================================
    
 private static void prepareAudioClipOptimized(Path filePath, String filename) throws Exception {
	    System.out.println("[RegionVisualizer] Preparando: " + filename);
	    long startTime = System.currentTimeMillis();
	    
	    // NUEVO: usar cache híbrido en lugar del sistema anterior
	    currentAudioStream = getCachedAudioStream(filename, filePath);
	    
	    long totalTime = System.currentTimeMillis() - startTime;
	    System.out.println("[Cache] ðŸ”„ Archivo preparado en " + totalTime + "ms");
	    
	    currentClip = AudioSystem.getClip();
	    currentClip.open(currentAudioStream);
	}
    
    private static AudioInputStream recreateStream(Path filePath, String filename) throws Exception {
        AudioInputStream rawStream = AudioSystem.getAudioInputStream(filePath.toFile());
        String extension = getFileExtension(filename).toLowerCase();
        
        if (extension.equals(".ogg")) {
            return OggOptimizer.optimizeOggStream(rawStream, filePath);
        } else {
            return convertToPlayableFormatLazy(rawStream);
        }
    }
    
    private static AudioInputStream convertToPlayableFormatLazy(AudioInputStream audioInputStream) throws IOException {
        AudioFormat sourceFormat = audioInputStream.getFormat();
        
        if (isPlayableFormat(sourceFormat)) {
            System.out.println("[RegionVisualizer] Formato ya es reproducible");
            return audioInputStream;
        }
        
        return convertToPlayableFormatOptimized(audioInputStream);
    }
    
    private static AudioInputStream convertToPlayableFormatOptimized(AudioInputStream audioInputStream) throws IOException {
        AudioFormat sourceFormat = audioInputStream.getFormat();
        
        if (sourceFormat.getEncoding().equals(AudioFormat.Encoding.PCM_SIGNED) ||
            sourceFormat.getEncoding().equals(AudioFormat.Encoding.PCM_UNSIGNED)) {
            System.out.println("[RegionVisualizer] Formato ya es PCM");
            return audioInputStream;
        }
        
        AudioFormat targetFormat = new AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            sourceFormat.getSampleRate(),
            16,
            sourceFormat.getChannels(),
            sourceFormat.getChannels() * 2,
            sourceFormat.getSampleRate(),
            false
        );
        
        try {
            if (AudioSystem.isConversionSupported(targetFormat, sourceFormat)) {
                System.out.println("[RegionVisualizer] Convirtiendo formato de audio");
                return AudioSystem.getAudioInputStream(targetFormat, audioInputStream);
            } else {
                System.err.println("[RegionVisualizer] Conversión no soportada");
                return audioInputStream;
            }
        } catch (Exception e) {
            System.err.println("[RegionVisualizer] Error en conversión: " + e.getMessage());
            return audioInputStream;
        }
    }
    
    private static boolean isPlayableFormat(AudioFormat format) {
        return (format.getEncoding().equals(AudioFormat.Encoding.PCM_SIGNED) ||
                format.getEncoding().equals(AudioFormat.Encoding.PCM_UNSIGNED)) &&
               format.getSampleSizeInBits() > 0 &&
               format.getChannels() > 0;
    }
    
    private static class OggOptimizer {
        private static final ConcurrentHashMap<String, Boolean> compatibilityCache = new ConcurrentHashMap<>();
        
        public static AudioInputStream optimizeOggStream(AudioInputStream originalStream, Path filePath) throws IOException {
            String cacheKey = filePath.toString() + "_compat";
            AudioFormat sourceFormat = originalStream.getFormat();
            
            Boolean isCompatible = compatibilityCache.get(cacheKey);
            if (isCompatible != null && isCompatible) {
                System.out.println("[OGG] Compatible directo");
                return originalStream;
            }
            
            if (isDirectlyPlayable(sourceFormat)) {
                compatibilityCache.put(cacheKey, true);
                System.out.println("[OGG] Reproducible sin conversión");
                return originalStream;
            }
            
            compatibilityCache.put(cacheKey, false);
            AudioFormat optimizedFormat = createOptimizedOggFormat(sourceFormat);
            
            if (AudioSystem.isConversionSupported(optimizedFormat, sourceFormat)) {
                System.out.println("[OGG] ðŸ”§ Convertido: " + sourceFormat + " â†’ " + optimizedFormat);
                return AudioSystem.getAudioInputStream(optimizedFormat, originalStream);
            }
            
            return convertToPlayableFormatOptimized(originalStream);
        }
        
        private static boolean isDirectlyPlayable(AudioFormat format) {
            return (format.getEncoding() == AudioFormat.Encoding.PCM_SIGNED && 
                    format.getSampleSizeInBits() == 16 &&
                    format.getChannels() <= 2) ||
                   (format.getEncoding() == AudioFormat.Encoding.PCM_UNSIGNED && 
                    format.getSampleSizeInBits() == 8);
        }
        
        private static AudioFormat createOptimizedOggFormat(AudioFormat sourceFormat) {
            return new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                Math.min(sourceFormat.getSampleRate(), 44100),
                16,
                Math.min(sourceFormat.getChannels(), 2),
                Math.min(sourceFormat.getChannels(), 2) * 2,
                Math.min(sourceFormat.getSampleRate(), 44100),
                false
            );
        }
    }
    
    // ========================================
    // MÃ‰TODOS AUXILIARES DE COMANDOS
    // ========================================
    
    private static void handleVolumeCommand(String command) {
        try {
            String volumeStr = command.substring(7);
            float volume = Float.parseFloat(volumeStr);
            setVolume(volume);
            sendMessageSync(" Volumen del mod establecido: " + Math.round(volume * 100) + "%", ChatFormatting.AQUA);
        } catch (NumberFormatException e) {
            sendMessageSync("âŒ Volumen inválido", ChatFormatting.RED);
        }
    }
    
    private static void handleGetVolumeCommand() {
        float currentVol = getCurrentVolume();
        sendMessageSync("ðŸŽšï¸ Volumen actual del mod: " + Math.round(currentVol * 100) + "%", ChatFormatting.AQUA);
    }
    
    private static void handleConfigCommand() {
        Minecraft.getInstance().execute(() -> {
            Minecraft.getInstance().setScreen(new MusicConfigScreen(Minecraft.getInstance().screen));
        });
        System.out.println("[RegionVisualizer] Abriendo MusicConfigScreen");
    }
    
    private static void handleMusicCommand(String command) {
        String[] parts = command.split(":", 4);
        if (parts.length < 4) {
            sendMessageSync("Comando de música inválido", ChatFormatting.RED);
            return;
        }
        String filename = parts[1];
        boolean loop = Boolean.parseBoolean(parts[2]);
        boolean fade = Boolean.parseBoolean(parts[3]);
        play(filename, loop, fade);
    }
    
    private static void handleStopCommand(String command) {
        boolean fade = Boolean.parseBoolean(command.substring(5));
        stop(fade);
    }
    private static void printCacheStatus() {
        System.out.println("=== CACHE STATUS DETALLADO ===");
        
        // RAM Cache con timestamp
        System.out.println("RAM Cache (" + ramCache.size() + "/" + MAX_RAM_CACHED_FILES + "):");
        ramCache.entrySet().forEach(entry -> {
            CachedAudioData data = entry.getValue();
            long ageMinutes = (System.currentTimeMillis() - data.timestamp) / 60000;
            System.out.println("  ðŸ§  " + data.filename + " (" + 
                formatFileSize(data.audioData.length) + ") - edad: " + ageMinutes + " min");
        });
        
        // Disk Cache con creationTime
        System.out.println("Disk Cache (" + diskCache.size() + "/" + MAX_DISK_CACHED_FILES + "):");
        diskCache.entrySet().forEach(entry -> {
            DiskCachedAudioInfo info = entry.getValue();
            long ageMinutes = (System.currentTimeMillis() - info.creationTime) / 60000;
            System.out.println("  ðŸ’¾ " + info.filename + " (" + 
                formatFileSize(info.fileSize) + ") - edad: " + ageMinutes + " min");
        });
        
        // Access Times (LRU) - mantenido igual
        System.out.println("Access Times (LRU):");
        accessTimes.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .limit(10)
            .forEach(entry -> {
                String filename = Paths.get(entry.getKey()).getFileName().toString();
                long minutesAgo = (System.currentTimeMillis() - entry.getValue()) / 60000;
                System.out.println("  â° " + filename + " (hace " + minutesAgo + " min)");
            });
        
        sendMessageSync("ðŸ“Š Ver consola para estado detallado del cache", ChatFormatting.AQUA);
    }

    private static void printCacheInfo() {
        try {
            long diskFolderSize = 0;
            int diskFiles = 0;
            
            if (Files.exists(diskCacheFolder)) {
                try (Stream<Path> files = Files.list(diskCacheFolder)) {
                    for (Path file : files.collect(Collectors.toList())) {
                        if (file.toString().endsWith(".cache") || file.toString().endsWith(".meta")) {
                            diskFolderSize += Files.size(file);
                            diskFiles++;
                        }
                    }
                }
            }
            
            long ramMemory = ramCache.values().stream()
                .mapToLong(data -> data.audioData.length)
                .sum();
            
            System.out.println("=== CACHE INFO ===");
            System.out.println("ðŸ“ Disk Cache Folder: " + diskCacheFolder);
            System.out.println("ðŸ“Š Configuración:");
            System.out.println("  - Max RAM files: " + MAX_RAM_CACHED_FILES);
            System.out.println("  - Max DISK files: " + MAX_DISK_CACHED_FILES);
            System.out.println("  - Max RAM file size: " + formatFileSize(MAX_FILE_SIZE_FOR_RAM_CACHE));
            System.out.println("  - Max DISK file size: " + formatFileSize(MAX_FILE_SIZE_FOR_DISK_CACHE));
            System.out.println("ðŸ”¢ Estado actual:");
            System.out.println("  - RAM memory used: " + formatFileSize(ramMemory));
            System.out.println("  - DISK files on filesystem: " + diskFiles);
            System.out.println("  - DISK space used: " + formatFileSize(diskFolderSize));
            
            sendMessageSync("â„¹ï¸ Cache Info - RAM: " + formatFileSize(ramMemory) + 
                ", DISK: " + diskFiles + " files (" + formatFileSize(diskFolderSize) + ")", 
                ChatFormatting.AQUA);
                
        } catch (Exception e) {
            System.err.println("[Cache] âš ï¸ Error obteniendo info: " + e.getMessage());
            sendMessageSync("âŒ Error obteniendo información del cache", ChatFormatting.RED);
        }
    }
    
    private static void handleNormalCommands(String command) {
        // NUEVO: Verificar primero si es comando de cache
        if (command.toUpperCase().startsWith("CACHE_")) {
            handleCacheCommand(command);
            return;
        }
        
        switch (command.toUpperCase()) {
            case "STOP":
                stop(false);
                break;
            case "INIT":
                forceInitialize();
                listAvailableFiles();
                break;
            case "LIST":
                listAvailableFiles();
                break;
            case "FORMATS":
                logSupportedFormats();
                sendMessageSync("Revisar la consola para ver los formatos soportados", ChatFormatting.AQUA);
                break;
            // Comandos de cache ahora se manejan arriba con handleCacheCommand()
            case "CACHE_STATS":
                printCacheStats();
                break;
            case "CACHE_CLEAR":
                clearAllCaches();
                break;
            case "CACHE_STATUS":
                printCacheStatus();
                break;
            case "CACHE_INFO":
                printCacheInfo();
                break;
            case "LOGOUT":
            case "SHUTDOWN":
                shutdown();
                break;
            default:
                if (!command.trim().isEmpty()) {
                    play(command, false, false);
                } else {
                    sendMessageSync("âŒ Comando vacío", ChatFormatting.RED);
                }
                break;
        }
    }
    private static void handleCacheCommand(String command) {
        switch (command.toUpperCase()) {
            case "CACHE_STATS":
                printCacheStats();
                break;
            case "CACHE_CLEAR":
                clearAllCaches();
                sendMessageSync("ðŸ§¹ Cache completamente limpiado", ChatFormatting.GREEN);
                break;
            case "CACHE_STATUS":
                printCacheStatus();
                break;
            case "CACHE_INFO":
                printCacheInfo();
                break;
            // NUEVOS comandos específicos
            case "CACHE_RAM_CLEAR":
                synchronized (audioLock) {
                    int ramCleared = ramCache.size();
                    ramCache.clear();
                    System.out.println("[Cache] ðŸ§¹ RAM cache limpiado: " + ramCleared + " archivos");
                    sendMessageSync("ðŸ§  RAM Cache limpiado: " + ramCleared + " archivos", ChatFormatting.GREEN);
                }
                break;
            case "CACHE_DISK_CLEAR":
                synchronized (audioLock) {
                    int diskCleared = diskCache.size();
                    diskCache.clear();
                    // Eliminar archivos físicos
                    try {
                        Files.list(diskCacheFolder)
                            .filter(path -> path.toString().endsWith(".cache") || 
                                           path.toString().endsWith(".meta") ||
                                           path.toString().endsWith("cache_index.json"))
                            .forEach(path -> {
                                try {
                                    Files.delete(path);
                                } catch (IOException e) {
                                    System.err.println("[Cache] âš ï¸ Error eliminando: " + path);
                                }
                            });
                    } catch (Exception e) {
                        System.err.println("[Cache] âš ï¸ Error limpiando disco: " + e.getMessage());
                    }
                    System.out.println("[Cache] ðŸ§¹ Disk cache limpiado: " + diskCleared + " archivos");
                    sendMessageSync("ðŸ’¾ Disk Cache limpiado: " + diskCleared + " archivos", ChatFormatting.GREEN);
                }
                break;
            case "CACHE_OPTIMIZE":
                // Promover archivos frecuentes a RAM si hay espacio
                synchronized (audioLock) {
                    int promoted = 0;
                    for (Map.Entry<String, DiskCachedAudioInfo> entry : diskCache.entrySet()) {
                        if (ramCache.size() >= MAX_RAM_CACHED_FILES) break;
                        
                        DiskCachedAudioInfo info = entry.getValue();
                        if (info.fileSize <= MAX_FILE_SIZE_FOR_RAM_CACHE && 
                            !ramCache.containsKey(entry.getKey())) {
                            
                            Long lastAccess = accessTimes.get(entry.getKey());
                            if (lastAccess != null && 
                                (System.currentTimeMillis() - lastAccess) < 30 * 60 * 1000) { // 30 min
                                try {
                                    AudioInputStream ramStream = loadFromDiskCache(info);
                                    storeInRamCache(entry.getKey(), ramStream, info.filename);
                                    promoted++;
                                    System.out.println("[Cache] â¬†ï¸ Optimización - promovido: " + info.filename);
                                } catch (Exception e) {
                                    System.err.println("[Cache] âš ï¸ Error promoviendo: " + info.filename);
                                }
                            }
                        }
                    }
                    sendMessageSync("âš¡ Cache optimizado: " + promoted + " archivos promovidos a RAM", 
                        ChatFormatting.GREEN);
                }
                break;
            default:
                sendMessageSync("âŒ Comando de cache desconocido: " + command, ChatFormatting.RED);
                sendMessageSync("ðŸ’¡ Comandos disponibles: CACHE_STATS, CACHE_CLEAR, CACHE_STATUS, " +
                    "CACHE_INFO, CACHE_RAM_CLEAR, CACHE_DISK_CLEAR, CACHE_OPTIMIZE", ChatFormatting.YELLOW);
                break;
        }
    }
    
    // ========================================
    // MÃ‰TODOS AUXILIARES DE REPRODUCCIÓN
    // ========================================
    
    private static void prepareClipTransition(boolean fade) {
        // Limpiar fade-out anterior
        if (isPreviousPlaying.get()) {
            isPreviousPlaying.set(false);
            cleanupPreviousResources();
        }

        if (currentClip != null && isPlaying.get()) {
            // Mover clip actual a anterior para fade-out
            previousClip = currentClip;
            previousAudioStream = currentAudioStream;
            previousVolumeControl = volumeControl;
            currentClip = null;
            currentAudioStream = null;
            volumeControl = null;
            
            if (fade && previousVolumeControl != null && previousClip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                shouldStopPrevious = true;
                fadeOutPrevious();
            } else {
                cleanupPreviousResources();
            }
        } else {
            cleanupPreviousResources();
        }
    }
    
    private static boolean validateMusicFile(String filename) {
        if (filename == null || filename.trim().isEmpty()) {
            sendMessageSync("âŒ Nombre de archivo de música vacío", ChatFormatting.RED);
            return false;
        }

        Path filePath = musicFolder.resolve(filename);
        if (!Files.exists(filePath)) {
            sendMessageSync("âŒ Archivo de música no encontrado: " + filename, ChatFormatting.RED);
            return false;
        }

        String fileExtension = getFileExtension(filename).toLowerCase();
        boolean isSupported = false;
        for (String format : SUPPORTED_FORMATS) {
            if (fileExtension.equals(format)) {
                isSupported = true;
                break;
            }
        }
        
        if (!isSupported) {
            sendMessageSync("âŒ Formato no soportado: " + filename + " (" + fileExtension + ")", ChatFormatting.RED);
            return false;
        }
        
        return true;
    }
    
    private static void setupVolumeControl(String filename, boolean fade) {
        if (currentClip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
            volumeControl = (FloatControl) currentClip.getControl(FloatControl.Type.MASTER_GAIN);
        } else {
            sendMessageSync("âš ï¸ El archivo " + filename + " no soporta control de volumen", ChatFormatting.YELLOW);
            fade = false;
        }
    }
    
    private static void handlePlaybackError(Exception e, String filename) {
        if (e instanceof UnsupportedAudioFileException) {
            String fileExt = getFileExtension(filename).toLowerCase();
            sendMessageSync("âŒ Formato no soportado: " + filename + " (" + fileExt + ")", ChatFormatting.RED);
            
            if (fileExt.equals(".mp3")) {
                sendMessageSync("ðŸ’¡ Intenta convertir el MP3 a WAV u OGG", ChatFormatting.YELLOW);
            } else if (fileExt.equals(".ogg")) {
                sendMessageSync("ðŸ’¡ Asegurate de que el archivo OGG esté en formato Vorbis", ChatFormatting.YELLOW);
            }
        } else if (e instanceof IOException) {
            sendMessageSync("âŒ Error al reproducir: " + filename, ChatFormatting.RED);
        } else if (e instanceof LineUnavailableException) {
            sendMessageSync("âŒ No se pudo reproducir: Línea de audio no disponible", ChatFormatting.RED);
        } else {
            sendMessageSync("âŒ Error inesperado: " + e.getMessage(), ChatFormatting.RED);
        }
        
        System.err.println("[RegionVisualizer] âŒ Error reproduciendo " + filename + ": " + e.getMessage());
        e.printStackTrace();
        cleanupResources();
    }
    
    // ==========================================
    // INICIALIZACIÓN Y CONFIGURACIÓN DEL SISTEMA
    // ==========================================
    
    private static void setupMusicFolder() throws IOException {
        File gameDir = Minecraft.getInstance().gameDirectory;
        musicFolder = Paths.get(gameDir.getAbsolutePath(), "music");

        if (!Files.exists(musicFolder)) {
            Files.createDirectories(musicFolder);
            System.out.println("[RegionVisualizer] âœ… Carpeta de música creada: " + musicFolder);
            createHelpFile();
        } else {
            System.out.println("[RegionVisualizer] âœ… Carpeta de música encontrada: " + musicFolder);
        }
        
        // NUEVO: Inicializar sistema de cache híbrido
        setupCacheSystem();
    }
    
    private static void validateAudioSystem() {
        if (AudioSystem.getMixerInfo().length == 0) {
            System.err.println("[RegionVisualizer] âš ï¸ No se encontraron dispositivos de audio");
            sendMessageSync("âš ï¸ No se encontraron dispositivos de audio", ChatFormatting.RED);
        } else {
            System.out.println("[RegionVisualizer] âœ… Sistema de audio Java Sound inicializado");
        }
    }
    
    private static void initializeAudioFormats() {
        try {
            AudioFileFormat.Type[] supportedTypes = AudioSystem.getAudioFileTypes();
            System.out.println("[RegionVisualizer] ðŸŽµ Formatos de audio detectados: " + supportedTypes.length);
            
            boolean oggSupported = isFormatSupported(".ogg");
            
            System.out.println("[RegionVisualizer] ðŸ“Š Estado de compatibilidad:");
            System.out.println("[RegionVisualizer]   - WAV: âœ… (nativo)");
            System.out.println("[RegionVisualizer]   - OGG: " + (oggSupported ? "âœ…" : "âŒ"));
            
            if (!oggSupported) {
                sendMessageSync("âš ï¸ Soporte OGG no detectado. Revisa las dependencias.", ChatFormatting.YELLOW);
            }
            
        } catch (Exception e) {
            System.err.println("[RegionVisualizer] âš ï¸ Error verificando formatos: " + e.getMessage());
            sendMessageSync("âŒ Error verificando formatos de audio", ChatFormatting.RED);
        }
    }
    
    private static boolean isFormatSupported(String extension) {
        try {
            String targetExtension = extension.toLowerCase().substring(1);
            AudioFileFormat.Type[] supportedTypes = AudioSystem.getAudioFileTypes();
            
            for (AudioFileFormat.Type type : supportedTypes) {
                if (type.getExtension().toLowerCase().equals(targetExtension)) {
                    return true;
                }
            }
            
            // Prueba con archivo real si existe
            List<Path> existingFiles = Files.list(musicFolder)
                .filter(path -> path.toString().toLowerCase().endsWith(extension.toLowerCase()))
                .collect(Collectors.toList());
            
            if (!existingFiles.isEmpty()) {
                Path testPath = existingFiles.get(0);
                try (AudioInputStream testStream = AudioSystem.getAudioInputStream(testPath.toFile())) {
                    return testStream != null;
                } catch (UnsupportedAudioFileException e) {
                    return false;
                }
            }
            
            return false;
        } catch (Exception e) {
            System.err.println("[RegionVisualizer] âš ï¸ Error verificando soporte para " + extension);
            return false;
        }
    }
    
    private static void logSupportedFormats() {
        try {
            System.out.println("[RegionVisualizer] ðŸŽµ Formatos disponibles:");
            for (String format : SUPPORTED_FORMATS) {
                boolean supported = isFormatSupported(format);
                System.out.println("[RegionVisualizer]   - " + format.toUpperCase() + ": " + (supported ? "âœ…" : "âŒ"));
            }
        } catch (Exception e) {
            System.err.println("[RegionVisualizer] âš ï¸ Error verificando formatos: " + e.getMessage());
        }
    }
    
    // ========================================
    // UTILIDADES Y HELPERS
    // ========================================
    
    private static String getFileExtension(String filename) {
        int lastDotIndex = filename.lastIndexOf('.');
        if (lastDotIndex > 0) {
            return filename.substring(lastDotIndex);
        }
        return "";
    }
    
    private static String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static void sendMessageSync(String message, ChatFormatting formatting) {
        Minecraft.getInstance().execute(() -> {
            if (Minecraft.getInstance().player != null) {
                Minecraft.getInstance().player.sendSystemMessage(
                        Component.literal(message).withStyle(formatting)
                );
            }
        });
    }
    
    private static void createHelpFile() {
        try {
            Path helpFile = musicFolder.resolve("README.txt");
            if (!Files.exists(helpFile)) {
                Files.writeString(helpFile,
                        "RegionVisualizer Music Folder\n" +
                        "----------------------------\n" +
                        "Place your audio files in this folder to use them with RegionVisualizer.\n" +
                        "\n" +
                        "SUPPORTED FORMATS:\n" +
                        "- WAV (recommended): Best compatibility and quality\n" +
                        "- OGG: Requires vorbisspi library in classpath (Vorbis format only)\n" +
                        "\n" +
                        "USAGE:\n" +
                        "- Use /playmusic <filename> to test playback\n" +
                        "- Use /region add <name> <musicfile> <loop> <fade> to create musical regions\n" +
                        "- Example: /region add tavern_music background.ogg true true\n" +
                        "\n" +
                        "RECOMMENDATIONS:\n" +
                        "- For best compatibility, use WAV format (PCM, 44.1 kHz, 16-bit)\n" +
                        "- OGG Vorbis is recommended for smaller file sizes\n" +
                        "- Keep file names simple (no spaces or special characters)\n" +
                        "\n" +
                        "CONFIGURATION:\n" +
                        "Edit config/regionvisualizer.properties to adjust settings\n" +
                        "\n" +
                        "TROUBLESHOOTING:\n" +
                        "- If OGG files don't play: Ensure vorbisspi dependencies are installed\n" +
                        "- Use /music init to reinitialize the audio system\n" +
                        "- Use /music list to see which files are detected and supported\n" +
                        "- Only WAV and OGG formats are supported");
                System.out.println("[RegionVisualizer] âœ… Archivo de ayuda creado: " + helpFile);
            }
        } catch (IOException e) {
            System.err.println("[RegionVisualizer] âŒ Error creando archivo de ayuda: " + e.getMessage());
        }
    }
    
    // ========================================
    // SISTEMA DE DEBUG (MENOS IMPORTANTE)
    // ========================================
    
    public static void debugAudioSystem() {
        System.out.println("[RegionVisualizer] === DEBUG AUDIO SYSTEM ===");

        // Verificar tipos de archivos soportados
        AudioFileFormat.Type[] types = AudioSystem.getAudioFileTypes();
        System.out.println("[RegionVisualizer] AudioSystem tipos soportados: " + types.length);
        for (AudioFileFormat.Type type : types) {
            System.out.println("[RegionVisualizer]   - " + type.toString() + " (extensión: " + type.getExtension() + ")");
        }

        // Verificar proveedores de AudioFileReader
        System.out.println("[RegionVisualizer] === AUDIO FILE READERS ===");
        List<javax.sound.sampled.spi.AudioFileReader> readersList = new ArrayList<>();
        ServiceLoader<javax.sound.sampled.spi.AudioFileReader> loader =
                ServiceLoader.load(javax.sound.sampled.spi.AudioFileReader.class);

        for (javax.sound.sampled.spi.AudioFileReader reader : loader) {
            readersList.add(reader);
            System.out.println("[RegionVisualizer]   - " + reader.getClass().getName());
        }
        System.out.println("[RegionVisualizer] AudioFileReaders encontrados: " + readersList.size());

        // Verificar clases OGG específicas
        System.out.println("[RegionVisualizer] === VERIFICACIÓN DE CLASES OGG ===");
        String[] oggClasses = {
                "javazoom.spi.vorbis.sampled.file.VorbisAudioFileReader",
                "com.jcraft.jorbis.VorbisFile",
                "org.tritonus.share.sampled.file.AudioFileReader",
                "de.jarnbjo.vorbis.VorbisStream"
        };

        for (String className : oggClasses) {
            try {
                Class.forName(className);
                System.out.println("[RegionVisualizer] âœ… Clase encontrada: " + className);
            } catch (ClassNotFoundException e) {
                System.out.println("[RegionVisualizer] âŒ Clase NO encontrada: " + className);
            }
        }

        // Probar archivo OGG específico
        try {
            List<Path> oggFiles = Files.list(musicFolder)
                    .filter(path -> path.toString().toLowerCase().endsWith(".ogg"))
                    .limit(1)
                    .collect(Collectors.toList());

            if (!oggFiles.isEmpty()) {
                Path testFile = oggFiles.get(0);
                System.out.println("[RegionVisualizer] Probando archivo: " + testFile.getFileName());

                try (AudioInputStream stream = AudioSystem.getAudioInputStream(testFile.toFile())) {
                    System.out.println("[RegionVisualizer] âœ… Archivo OGG leído exitosamente");
                    System.out.println("[RegionVisualizer] Formato: " + stream.getFormat());
                    sendMessageSync("âœ… OGG soportado y funcional!", ChatFormatting.GREEN);
                } catch (UnsupportedAudioFileException e) {
                    System.err.println("[RegionVisualizer] âŒ Archivo OGG no soportado: " + e.getMessage());
                    sendMessageSync("âŒ OGG no soportado: " + e.getMessage(), ChatFormatting.RED);
                }
            } else {
                System.out.println("[RegionVisualizer] âš ï¸ No se encontraron archivos OGG para probar");
                sendMessageSync("âš ï¸ No hay archivos OGG para probar", ChatFormatting.YELLOW);
            }
        } catch (Exception e) {
            System.err.println("[RegionVisualizer] âŒ Error accediendo a archivos OGG: " + e.getMessage());
        }

        System.out.println("[RegionVisualizer] === FIN DEBUG ===");
    }
}