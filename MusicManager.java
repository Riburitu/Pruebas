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
	private static final int MAX_DISK_CACHED_FILES = 100;
	private static final long MAX_FILE_SIZE_FOR_RAM_CACHE = 10 * 1024 * 1024; // 10MB
	private static final long MAX_FILE_SIZE_FOR_DISK_CACHE = 60 * 1024 * 1024; // 50MB
	private static final Path configFile = Paths.get(Minecraft.getInstance().gameDirectory.getAbsolutePath(), "config", "regionvisualizer.properties");
	private static volatile boolean mainMenuPreloadTriggered = false;
	private static volatile long lastMainMenuDetection = 0;
	private static final long MAIN_MENU_COOLDOWN = 30000; // 30 segundos entre detecciones
	
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
    private static final ExecutorService preloadExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MusicPreloader");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicBoolean preloadInProgress = new AtomicBoolean(false);
    private static final AtomicBoolean preloadCompleted = new AtomicBoolean(false);
    private static volatile PreloadStatus currentPreloadStatus = new PreloadStatus();
    
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
                System.out.println("[RegionVisualizer] ⏰ Sistema de música inicializado");
                
                // NUEVO: Iniciar precarga automática si estamos en menú principal
                if (Minecraft.getInstance().screen != null && 
                    Minecraft.getInstance().level == null) { // En menú principal
                    System.out.println("[Preload] 🎯 Detectado menú principal, iniciando precarga automática...");
                    startMainMenuPreload();
                }

            } catch (Exception e) {
                System.err.println("[RegionVisualizer] ❌ Error inicializando: " + e.getMessage());
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
    
    public static void printPreloadSystemStatus() {
        System.out.println("=== PRELOAD SYSTEM STATUS ===");
        System.out.println("📊 Estado del Executor:");
        System.out.println("  - Shutdown: " + preloadExecutor.isShutdown());
        System.out.println("  - Terminated: " + preloadExecutor.isTerminated());
        
        System.out.println("📊 Flags de Control:");
        System.out.println("  - mainMenuPreloadTriggered: " + mainMenuPreloadTriggered);
        System.out.println("  - preloadInProgress: " + preloadInProgress.get());
        System.out.println("  - preloadCompleted: " + preloadCompleted.get());
        System.out.println("  - lastMainMenuDetection: " + 
            (lastMainMenuDetection > 0 ? ((System.currentTimeMillis() - lastMainMenuDetection) / 1000) + "s ago" : "never"));
        
        PreloadStatus status = getPreloadStatus();
        System.out.println("📊 Estado de Precarga:");
        System.out.println("  - Total files: " + status.totalFiles);
        System.out.println("  - Processed: " + status.processedFiles);
        System.out.println("  - Cached: " + status.cachedFiles);
        System.out.println("  - Current file: " + status.currentFile);
    }
    
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
            System.out.println("[RegionVisualizer] 🔄 Iniciando shutdown completo...");
            
            // Detener precarga si está en progreso
            if (preloadInProgress.get()) {
                System.out.println("[Preload] ℹ️ Deteniendo precarga por shutdown...");
                preloadInProgress.set(false);
            }
            
            // Resetear flags de control
            mainMenuPreloadTriggered = false;
            lastMainMenuDetection = 0;
            preloadCompleted.set(false);
            
            // Shutdown del executor de precarga
            if (!preloadExecutor.isShutdown()) {
                preloadExecutor.shutdown();
                try {
                    if (!preloadExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                        System.out.println("[Preload] ⏰ Timeout esperando shutdown, forzando...");
                        preloadExecutor.shutdownNow();
                    }
                    System.out.println("[Preload] ✅ Executor cerrado correctamente");
                } catch (InterruptedException e) {
                    System.out.println("[Preload] ⚠️ Shutdown interrumpido, forzando cierre...");
                    preloadExecutor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
            
            isPreviousPlaying.set(false);
            stopInternal(true);
            cleanupPreviousResources();
            
            if (diskCache.size() > 0) {
                saveDiskCacheIndex();
                System.out.println("[Cache] 💾 Índice guardado para próxima sesión");
            }
            
            initialized = false;
            System.out.println("[RegionVisualizer] 🎵 Sistema completamente cerrado");
        }
    }
    
    public static void forceInitialize() {
        synchronized (audioLock) {
            System.out.println("[RegionVisualizer] 🔄 Reinicialización forzada iniciada...");
            
            // Shutdown completo primero
            shutdown();
            
            // Esperar un momento para asegurar cierre limpio
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            // Limpiar caches en RAM solamente
            System.out.println("[Cache] 🔄 Reinicialización forzada - manteniendo cache de disco");
            ramCache.clear(); // Solo limpiar RAM
            
            // Reinicializar completamente
            initialized = false;
            initialize();
            System.out.println("[RegionVisualizer] 🎵 Sistema de música reinicializado");
        }
    }
    
    public static void onPlayerLoggedOut() {
        synchronized (audioLock) {
            System.out.println("[RegionVisualizer] 🚪 Jugador salió del mundo - limpiando sistema de música");
            
            // Resetear flags de menú principal
            mainMenuPreloadTriggered = false;
            lastMainMenuDetection = 0;
            
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
    public static void onMainMenuOpened() {
        long currentTime = System.currentTimeMillis();
        
        // Protección anti-spam: solo procesar cada X segundos
        if (currentTime - lastMainMenuDetection < MAIN_MENU_COOLDOWN) {
            return; // Ignorar llamadas muy frecuentes
        }
        
        lastMainMenuDetection = currentTime;
        System.out.println("[Preload] 🏠 Menú principal detectado (cooldown aplicado)");
        
        if (!initialized) {
            initialize();
        }
        
        // Solo iniciar precarga si no se ha intentado ya
        if (!mainMenuPreloadTriggered && !preloadInProgress.get() && !preloadCompleted.get()) {
            System.out.println("[Preload] 🚀 Auto-iniciando precarga desde menú principal...");
            mainMenuPreloadTriggered = true; // Marcar como intentado
            startMainMenuPreload();
        } else if (preloadCompleted.get()) {
            System.out.println("[Preload] ✅ Precarga ya completada anteriormente");
        } else if (preloadInProgress.get()) {
            System.out.println("[Preload] ⏳ Precarga ya en progreso");
        } else {
            System.out.println("[Preload] ℹ️ Precarga ya se intentó en esta sesión");
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
                    
                    // Configuraciones existentes
                    modVolume = Float.parseFloat(props.getProperty("modVolume", "0.85"));
                    modVolume = Math.max(0.0f, Math.min(1.0f, modVolume));

                    maxModVolume = Float.parseFloat(props.getProperty("maxModVolume", "0.85"));
                    maxModVolume = Math.max(0.1f, Math.min(1.0f, maxModVolume));

                    fadeDuration = Float.parseFloat(props.getProperty("fadeDuration", "5.0"));
                    fadeDuration = Math.max(0.1f, Math.min(10.0f, fadeDuration)); // Límite actualizado

                    fadeInterval = Long.parseLong(props.getProperty("fadeInterval", "50"));
                    fadeInterval = Math.max(10L, Math.min(500L, fadeInterval));

                    fadeInStart = Float.parseFloat(props.getProperty("fadeInStart", "0.45"));
                    fadeInStart = Math.max(0.0f, Math.min(1.0f, fadeInStart));

                    // Ajustar modVolume si excede maxModVolume
                    if (modVolume > maxModVolume) {
                        modVolume = maxModVolume;
                    }

                    System.out.println("[RegionVisualizer] ✅ Configuración cargada correctamente");
                    System.out.println("[RegionVisualizer]   - Volumen mod: " + (modVolume * 100) + "%");
                    System.out.println("[RegionVisualizer]   - Volumen máximo: " + (maxModVolume * 100) + "%");
                    System.out.println("[RegionVisualizer]   - Duración fade: " + fadeDuration + "s");
                    System.out.println("[RegionVisualizer]   - Fade inicial: " + (fadeInStart * 100) + "%");
                    
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
            
            // Comentarios descriptivos
            props.setProperty("# Configuration for RegionVisualizer Music System", "");
            props.setProperty("# modVolume: Current volume level (0.0-1.0)", "");
            props.setProperty("# maxModVolume: Maximum allowed volume (0.1-1.0)", "");
            props.setProperty("# fadeDuration: Fade in/out duration in seconds (0.1-10.0)", "");
            props.setProperty("# fadeInterval: Fade update interval in milliseconds (10-500)", "");
            props.setProperty("# fadeInStart: Initial fade volume percentage (0.0-1.0)", "");
            
            try (FileOutputStream out = new FileOutputStream(configFile.toFile())) {
                props.store(out, "RegionVisualizer Music Configuration - Updated " + 
                    new java.util.Date().toString());
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
    private static class PreloadStatus {
        int totalFiles = 0;
        int processedFiles = 0;
        int cachedFiles = 0;
        int skippedFiles = 0;
        long startTime = 0;
        String currentFile = "";
        boolean completed = false;
        
        synchronized void reset() {
            totalFiles = 0;
            processedFiles = 0;
            cachedFiles = 0;
            skippedFiles = 0;
            startTime = System.currentTimeMillis();
            currentFile = "";
            completed = false;
        }
        
        synchronized PreloadStatus copy() {
            PreloadStatus copy = new PreloadStatus();
            copy.totalFiles = this.totalFiles;
            copy.processedFiles = this.processedFiles;
            copy.cachedFiles = this.cachedFiles;
            copy.skippedFiles = this.skippedFiles;
            copy.startTime = this.startTime;
            copy.currentFile = this.currentFile;
            copy.completed = this.completed;
            return copy;
        }
        
        synchronized int getProgressPercent() {
            if (totalFiles == 0) return 0;
            return (processedFiles * 100) / totalFiles;
        }
        
        synchronized long getElapsedTimeMs() {
            return System.currentTimeMillis() - startTime;
        }
    }
    /**
     * Inicia la precarga automática desde el menú principal
     * Se ejecuta en background sin bloquear la UI
     */
    public static void startMainMenuPreload() {
        if (!initialized) {
            System.out.println("[Preload] Sistema no inicializado, iniciando primero...");
            initialize();
        }
        
        if (preloadInProgress.get()) {
            System.out.println("[Preload] ⚠️ Precarga ya en progreso");
            return;
        }
        
        if (preloadCompleted.get()) {
            System.out.println("[Preload] ✅ Precarga ya completada en esta sesión");
            return;
        }
        
        // NUEVO: Verificar y recrear executor si es necesario
        if (preloadExecutor.isShutdown() || preloadExecutor.isTerminated()) {
            System.err.println("[Preload] ⚠️ Executor cerrado, no se puede iniciar precarga");
            System.err.println("[Preload] Usa 'PRELOAD_FORCE' o reinicia el sistema para forzar nueva precarga");
            return;
        }
        
        System.out.println("[Preload] 🚀 Iniciando precarga desde menú principal...");
        
        try {
            preloadExecutor.submit(() -> {
                try {
                    executePreloadProcess();
                } catch (Exception e) {
                    System.err.println("[Preload] ❌ Error en proceso de precarga: " + e.getMessage());
                    e.printStackTrace();
                    preloadInProgress.set(false);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            System.err.println("[Preload] ❌ No se pudo enviar tarea de precarga - Executor cerrado");
            System.err.println("[Preload] Usa 'PRELOAD_FORCE' para reiniciar el sistema");
            preloadInProgress.set(false);
        }
    }

    /**
     * Obtiene el estado actual de la precarga
     */
    public static PreloadStatus getPreloadStatus() {
        return currentPreloadStatus.copy();
    }

    /**
     * Verifica si la precarga está en progreso
     */
    public static boolean isPreloadInProgress() {
        return preloadInProgress.get();
    }

    /**
     * Verifica si la precarga está completada
     */
    public static boolean isPreloadCompleted() {
        return preloadCompleted.get();
    }

    /**
     * Fuerza una nueva precarga (limpia estado anterior)
     */
    public static void forcePreload() {
        synchronized (audioLock) {
            System.out.println("[Preload] 🔄 Forzando nueva precarga...");
            
            // Resetear flags de control
            preloadCompleted.set(false);
            preloadInProgress.set(false);
            mainMenuPreloadTriggered = false; // Permitir nuevo intento
            
            // Si el executor está cerrado, recrearlo NO es seguro aquí
            // En su lugar, advertir al usuario
            if (preloadExecutor.isShutdown() || preloadExecutor.isTerminated()) {
                System.err.println("[Preload] ⚠️ Executor cerrado - se requiere reinicio completo del sistema");
                System.err.println("[Preload] Usa '/music init' para reinicializar el sistema completamente");
                return;
            }
            
            startMainMenuPreload();
        }
    }
    
    private static void executePreloadProcess() {
        preloadInProgress.set(true);
        currentPreloadStatus.reset();
        
        try {
            System.out.println("[Preload] 🔍 Escaneando carpeta de música...");
            
            if (!Files.exists(musicFolder)) {
                System.out.println("[Preload] ⚠️ Carpeta de música no existe: " + musicFolder);
                return;
            }
            
            // PASO 1: Recopilar todos los archivos de audio
            List<Path> audioFiles;
            try {
                audioFiles = collectAudioFiles();
            } catch (IOException e) {
                System.err.println("[Preload] ❌ Error escaneando archivos: " + e.getMessage());
                e.printStackTrace();
                return;
            }
            
            if (audioFiles.isEmpty()) {
                System.out.println("[Preload] ℹ️ No se encontraron archivos de audio para precargar");
                currentPreloadStatus.completed = true;
                preloadCompleted.set(true);
                return;
            }
            
            currentPreloadStatus.totalFiles = audioFiles.size();
            System.out.println("[Preload] 📁 Encontrados " + audioFiles.size() + " archivos de audio");
            
            // PASO 2: Procesar cada archivo
            for (Path audioFile : audioFiles) {
                if (!preloadInProgress.get()) {
                    System.out.println("[Preload] ⏹️ Precarga cancelada por el usuario");
                    return;
                }
                
                try {
                    preloadSingleFile(audioFile);
                } catch (Exception e) {
                    System.err.println("[Preload] ❌ Error procesando " + audioFile.getFileName() + ": " + e.getMessage());
                    synchronized (currentPreloadStatus) {
                        currentPreloadStatus.skippedFiles++;
                    }
                }
                
                synchronized (currentPreloadStatus) {
                    currentPreloadStatus.processedFiles++;
                }
            }
            
            // PASO 3: Finalizar precarga
            synchronized (currentPreloadStatus) {
                currentPreloadStatus.completed = true;
                currentPreloadStatus.currentFile = "";
            }
            
            long totalTime = currentPreloadStatus.getElapsedTimeMs();
            System.out.println("[Preload] ✅ Precarga completada:");
            System.out.println("[Preload]   📊 Total: " + currentPreloadStatus.totalFiles + " archivos");
            System.out.println("[Preload]   💾 Cacheados: " + currentPreloadStatus.cachedFiles + " archivos");
            System.out.println("[Preload]   ⏭️ Omitidos: " + currentPreloadStatus.skippedFiles + " archivos");
            System.out.println("[Preload]   ⏱️ Tiempo: " + (totalTime / 1000.0) + " segundos");
            
            preloadCompleted.set(true);
            
            // Opcional: Notificar al jugador si está en el juego
            if (Minecraft.getInstance().player != null) {
                sendMessageSync("🎵 Precarga de música completada: " + currentPreloadStatus.cachedFiles + 
                    " archivos listos", ChatFormatting.GREEN);
            }
            
        } catch (Exception e) {
            // Capturar cualquier otra excepción inesperada
            System.err.println("[Preload] ❌ Error inesperado en precarga: " + e.getMessage());
            e.printStackTrace();
            
            // Notificar error al jugador si está disponible
            if (Minecraft.getInstance().player != null) {
                sendMessageSync("❌ Error en precarga de música: " + e.getMessage(), ChatFormatting.RED);
            }
        } finally {
            preloadInProgress.set(false);
        }
    }

    private static List<Path> collectAudioFiles() throws IOException {
        return Files.list(musicFolder)
            .filter(Files::isRegularFile)
            .filter(path -> {
                String filename = path.getFileName().toString().toLowerCase();
                for (String format : SUPPORTED_FORMATS) {
                    if (filename.endsWith(format)) {
                        return true;
                    }
                }
                return false;
            })
            .sorted() // Orden consistente
            .collect(Collectors.toList());
    }

    private static void preloadSingleFile(Path audioFile) throws Exception {
        String filename = audioFile.getFileName().toString();
        String cacheKey = audioFile.toString();
        
        synchronized (currentPreloadStatus) {
            currentPreloadStatus.currentFile = filename;
        }
        
        System.out.println("[Preload] 🎵 Procesando: " + filename);
        
        // OPTIMIZACIÓN: Verificar si ya está en cache de disco
        DiskCachedAudioInfo existingCache = diskCache.get(cacheKey);
        if (existingCache != null && isValidDiskCache(existingCache, audioFile)) {
            System.out.println("[Preload] ⚡ Ya existe en cache: " + filename);
            synchronized (currentPreloadStatus) {
                currentPreloadStatus.skippedFiles++;
            }
            return;
        }
        
        // Verificar tamaño del archivo
        long fileSize = Files.size(audioFile);
        if (fileSize > MAX_FILE_SIZE_FOR_DISK_CACHE) {
            System.out.println("[Preload] ⚠️ Archivo muy grande para cache: " + filename + 
                " (" + formatFileSize(fileSize) + ")");
            synchronized (currentPreloadStatus) {
                currentPreloadStatus.skippedFiles++;
            }
            return;
        }
        
        // PROCESAR Y CACHEAR
        try {
            AudioInputStream rawStream = AudioSystem.getAudioInputStream(audioFile.toFile());
            AudioInputStream processedStream;
            
            String extension = getFileExtension(filename).toLowerCase();
            if (extension.equals(".ogg")) {
                processedStream = OggOptimizer.optimizeOggStream(rawStream, audioFile);
            } else {
                processedStream = convertToPlayableFormatLazy(rawStream);
            }
            
            // Almacenar en cache de disco
            storeToDiskCacheForPreload(cacheKey, processedStream, filename, audioFile);
            
            synchronized (currentPreloadStatus) {
                currentPreloadStatus.cachedFiles++;
            }
            
            System.out.println("[Preload] ✅ Cacheado: " + filename + 
                " (" + formatFileSize(fileSize) + ")");
            
        } catch (UnsupportedAudioFileException e) {
            System.out.println("[Preload] ⚠️ Formato no soportado: " + filename);
            synchronized (currentPreloadStatus) {
                currentPreloadStatus.skippedFiles++;
            }
        }
    }
	private static void storeToDiskCacheForPreload(String cacheKey, AudioInputStream stream, String filename, Path originalPath) throws Exception {
	// Reutilizar la lógica existente pero con optimizaciones para precarga
	evictOldestDiskCacheIfNeeded();
	
	String originalHash = calculateFileHash(originalPath);
	String cacheFileName = generateCacheFileName(filename, originalHash);
	Path cacheFilePath = diskCacheFolder.resolve(cacheFileName + ".cache");
	Path metaFilePath = diskCacheFolder.resolve(cacheFileName + ".meta");
	
	// Verificar si ya existe (double-check por concurrencia)
	if (Files.exists(cacheFilePath) && Files.exists(metaFilePath)) {
		System.out.println("[Preload] ⚡ Cache ya existe (creado concurrentemente): " + filename);
		return;
	}
	
	// Guardar datos de audio procesados
	try (FileOutputStream fos = new FileOutputStream(cacheFilePath.toFile());
		BufferedOutputStream bos = new BufferedOutputStream(fos, 32768)) { // Buffer más grande para precarga
	
		byte[] buffer = new byte[16384]; // Buffer más grande
		int bytesRead;
	
		while ((bytesRead = stream.read(buffer)) != -1) {
			bos.write(buffer, 0, bytesRead);
			}
	}
	
	// Guardar metadatos
	long actualFileSize = Files.size(cacheFilePath);
	DiskCachedAudioInfo info = new DiskCachedAudioInfo(filename, cacheKey, cacheFileName, stream.getFormat(), actualFileSize, originalHash);
	
	saveAudioMetadata(metaFilePath, info);
	
	// Actualizar índice en memoria
	diskCache.put(cacheKey, info);
	
	// Actualizar access time para LRU
	updateAccessTime(cacheKey);
	}

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
 // INICIALIZACIÓN DEL CACHE HÍBRIDO
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

 /**
  * MODIFICACIÓN NECESARIA: Actualizar getCachedAudioStream para aprovechar precarga
  */
 private static AudioInputStream getCachedAudioStream(String filename, Path originalPath) throws Exception {
     String cacheKey = originalPath.toString();
     updateAccessTime(cacheKey);
     
     // 1. NIVEL 1: RAM Cache (más rápido - ~1ms)
     CachedAudioData ramData = ramCache.get(cacheKey);
     if (ramData != null) {
         System.out.println("[Cache] ⚡ RAM HIT: " + filename + " (instantáneo)");
         return ramData.createStream();
     }
     
     // 2. NIVEL 2: Disco Cache - MEJORADO con precarga
     DiskCachedAudioInfo diskInfo = diskCache.get(cacheKey);
     if (diskInfo != null && isValidDiskCache(diskInfo, originalPath)) {
         System.out.println("[Cache] ⚡ DISK HIT (precargado): " + filename + " (carga rápida)");
         AudioInputStream stream = loadFromDiskCache(diskInfo);
         
         // PROMOCIÓN: Si hay espacio en RAM, subir archivo frecuente
         promoteToRamIfSuitable(cacheKey, stream, diskInfo);
         
         return stream;
     }
     
     // 3. NIVEL 3: Procesar archivo original (lento - solo si no se precargó)
     System.out.println("[Cache] ⚠️ COMPLETE MISS: " + filename + " - Procesando (no precargado)...");
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
 // ESTADÍSTICAS Y COMANDOS PÚBLICOS
 // ========================================

 public static void printCacheStats() {
     long ramMemory = ramCache.values().stream()
         .mapToLong(data -> data.audioData.length)
         .sum();
     
     long diskMemory = diskCache.values().stream()
         .mapToLong(info -> info.fileSize)
         .sum();
         
     System.out.println("[Cache] Estadísticas detalladas:");
     System.out.println("💾 RAM Cache: " + ramCache.size() + "/" + MAX_RAM_CACHED_FILES + 
         " archivos (" + formatFileSize(ramMemory) + ")");
     System.out.println("💾 Disk Cache: " + diskCache.size() + "/" + MAX_DISK_CACHED_FILES + 
         " archivos (" + formatFileSize(diskMemory) + ")");
     
     if (!ramCache.isEmpty()) {
         System.out.println("💾 Archivos en RAM:");
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
                System.out.println("[OGG] 🔧 Convertido: " + sourceFormat + " →’ " + optimizedFormat);
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
    // MÉTODOS AUXILIARES DE COMANDOS
    // ========================================
    
    private static void handleVolumeCommand(String command) {
        try {
            String volumeStr = command.substring(7);
            float volume = Float.parseFloat(volumeStr);
            setVolume(volume);
            sendMessageSync(" Volumen del mod establecido: " + Math.round(volume * 100) + "%", ChatFormatting.AQUA);
        } catch (NumberFormatException e) {
            sendMessageSync("❌ Volumen inválido", ChatFormatting.RED);
        }
    }
    
    private static void handleGetVolumeCommand() {
        float currentVol = getCurrentVolume();
        sendMessageSync("Volumen actual del mod: " + Math.round(currentVol * 100) + "%", ChatFormatting.AQUA);
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
            System.out.println("  🎵 " + info.filename + " (" + 
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
        
        sendMessageSync("⇨ Ver consola para estado detallado del cache", ChatFormatting.AQUA);
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
            System.out.println("⇨ Disk Cache Folder: " + diskCacheFolder);
            System.out.println("⇨ Configuración:");
            System.out.println("  - Max RAM files: " + MAX_RAM_CACHED_FILES);
            System.out.println("  - Max DISK files: " + MAX_DISK_CACHED_FILES);
            System.out.println("  - Max RAM file size: " + formatFileSize(MAX_FILE_SIZE_FOR_RAM_CACHE));
            System.out.println("  - Max DISK file size: " + formatFileSize(MAX_FILE_SIZE_FOR_DISK_CACHE));
            System.out.println("⇨ Estado actual:");
            System.out.println("  - RAM memory used: " + formatFileSize(ramMemory));
            System.out.println("  - DISK files on filesystem: " + diskFiles);
            System.out.println("  - DISK space used: " + formatFileSize(diskFolderSize));
            
            sendMessageSync("ℹ¸ Cache Info - RAM: " + formatFileSize(ramMemory) + 
                ", DISK: " + diskFiles + " files (" + formatFileSize(diskFolderSize) + ")", 
                ChatFormatting.AQUA);
                
        } catch (Exception e) {
            System.err.println("[Cache] ❌ Error obteniendo info: " + e.getMessage());
            sendMessageSync("❌ Error obteniendo información del cache", ChatFormatting.RED);
        }
    }
    private static void handlePreloadCommands(String command) {
        switch (command.toUpperCase()) {
            case "PRELOAD_START":
                startMainMenuPreload();
                sendMessageSync("🚀 Precarga iniciada en background", ChatFormatting.GREEN);
                break;
                
            case "PRELOAD_STATUS":
                PreloadStatus status = getPreloadStatus();
                if (!preloadInProgress.get() && !preloadCompleted.get()) {
                    sendMessageSync("❌ Precarga no iniciada", ChatFormatting.RED);
                } else if (preloadInProgress.get()) {
                    sendMessageSync("🔄 Precargando: " + status.getProgressPercent() + "% (" + 
                        status.processedFiles + "/" + status.totalFiles + ") - " + 
                        status.currentFile, ChatFormatting.YELLOW);
                } else if (preloadCompleted.get()) {
                    sendMessageSync("✅ Precarga completada: " + status.cachedFiles + " archivos listos", 
                        ChatFormatting.GREEN);
                }
                break;
                
            case "PRELOAD_FORCE":
                forcePreload();
                sendMessageSync("🔄 Forzando nueva precarga...", ChatFormatting.YELLOW);
                break;
                
            case "PRELOAD_STOP":
                if (preloadInProgress.get()) {
                    preloadInProgress.set(false);
                    sendMessageSync("⏹️ Precarga detenida", ChatFormatting.YELLOW);
                } else {
                    sendMessageSync("❌ No hay precarga en progreso", ChatFormatting.RED);
                }
                break;
                
            default:
                sendMessageSync("❌ Comando de precarga desconocido: " + command, ChatFormatting.RED);
                sendMessageSync("Comandos disponibles: PRELOAD_START, PRELOAD_STATUS, PRELOAD_FORCE, PRELOAD_STOP", 
                    ChatFormatting.YELLOW);
                break;
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
            case "PRELOAD_START":
            case "PRELOAD_STATUS": 
            case "PRELOAD_FORCE":
            case "PRELOAD_STOP":
                handlePreloadCommands(command);
                break;
            default:
                if (!command.trim().isEmpty()) {
                    play(command, false, false);
                } else {
                    sendMessageSync("❌ Comando vacío", ChatFormatting.RED);
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
                sendMessageSync("✅ Cache completamente limpiado", ChatFormatting.GREEN);
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
                    System.out.println("[Cache] ✅ RAM cache limpiado: " + ramCleared + " archivos");
                    sendMessageSync("✅  RAM Cache limpiado: " + ramCleared + " archivos", ChatFormatting.GREEN);
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
                                    System.err.println("[Cache] ❌ Error eliminando: " + path);
                                }
                            });
                    } catch (Exception e) {
                        System.err.println("[Cache] ❌ Error limpiando disco: " + e.getMessage());
                    }
                    System.out.println("[Cache] ✅ Disk cache limpiado: " + diskCleared + " archivos");
                    sendMessageSync("✅ Disk Cache limpiado: " + diskCleared + " archivos", ChatFormatting.GREEN);
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
                                    System.out.println("[Cache] ✅ Optimización - promovido: " + info.filename);
                                } catch (Exception e) {
                                    System.err.println("[Cache] ❌ Error promoviendo: " + info.filename);
                                }
                            }
                        }
                    }
                    sendMessageSync("✅ Cache optimizado: " + promoted + " archivos promovidos a RAM", 
                        ChatFormatting.GREEN);
                }
                break;
            default:
                sendMessageSync("❌ Comando de cache desconocido: " + command, ChatFormatting.RED);
                sendMessageSync("Comandos disponibles: CACHE_STATS, CACHE_CLEAR, CACHE_STATUS, " +
                    "CACHE_INFO, CACHE_RAM_CLEAR, CACHE_DISK_CLEAR, CACHE_OPTIMIZE", ChatFormatting.YELLOW);
                break;
        }
    }
    
    // ========================================
    // MÉTODOS AUXILIARES DE REPRODUCCIÓN
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
            sendMessageSync("❌ Nombre de archivo de música vacío", ChatFormatting.RED);
            return false;
        }

        Path filePath = musicFolder.resolve(filename);
        if (!Files.exists(filePath)) {
            sendMessageSync("❌ Archivo de música no encontrado: " + filename, ChatFormatting.RED);
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
            sendMessageSync("❌ Formato no soportado: " + filename + " (" + fileExtension + ")", ChatFormatting.RED);
            return false;
        }
        
        return true;
    }
    
    private static void setupVolumeControl(String filename, boolean fade) {
        if (currentClip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
            volumeControl = (FloatControl) currentClip.getControl(FloatControl.Type.MASTER_GAIN);
        } else {
            sendMessageSync("⚠️ El archivo " + filename + " no soporta control de volumen", ChatFormatting.YELLOW);
            fade = false;
        }
    }
    
    private static void handlePlaybackError(Exception e, String filename) {
        if (e instanceof UnsupportedAudioFileException) {
            String fileExt = getFileExtension(filename).toLowerCase();
            sendMessageSync("❌ Formato no soportado: " + filename + " (" + fileExt + ")", ChatFormatting.RED);
            
            if (fileExt.equals(".mp3")) {
                sendMessageSync("⚠️ Intenta convertir el MP3 a WAV u OGG", ChatFormatting.YELLOW);
            } else if (fileExt.equals(".ogg")) {
                sendMessageSync("⚠️ Asegurate de que el archivo OGG esté en formato Vorbis", ChatFormatting.YELLOW);
            }
        } else if (e instanceof IOException) {
            sendMessageSync("❌ Error al reproducir: " + filename, ChatFormatting.RED);
        } else if (e instanceof LineUnavailableException) {
            sendMessageSync("❌ No se pudo reproducir: Línea de audio no disponible", ChatFormatting.RED);
        } else {
            sendMessageSync("❌ Error inesperado: " + e.getMessage(), ChatFormatting.RED);
        }
        
        System.err.println("[RegionVisualizer] ❌ Error reproduciendo " + filename + ": " + e.getMessage());
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
            System.out.println("[RegionVisualizer] ✅ Carpeta de música creada: " + musicFolder);
            createHelpFile();
        } else {
            System.out.println("[RegionVisualizer] 🔍 Carpeta de música encontrada: " + musicFolder);
        }
        
        // NUEVO: Inicializar sistema de cache híbrido
        setupCacheSystem();
    }
    
    private static void validateAudioSystem() {
        if (AudioSystem.getMixerInfo().length == 0) {
            System.err.println("[RegionVisualizer] ⚠️ No se encontraron dispositivos de audio");
            sendMessageSync("⚠️ No se encontraron dispositivos de audio", ChatFormatting.YELLOW);
        } else {
            System.out.println("[RegionVisualizer] ✅ Sistema de audio Java Sound inicializado");
        }
    }
    
    private static void initializeAudioFormats() {
        try {
            AudioFileFormat.Type[] supportedTypes = AudioSystem.getAudioFileTypes();
            System.out.println("[RegionVisualizer] ðŸŽµ Formatos de audio detectados: " + supportedTypes.length);
            
            boolean oggSupported = isFormatSupported(".ogg");
            
            System.out.println("[RegionVisualizer] 🔎 Estado de compatibilidad:");
            System.out.println("[RegionVisualizer]   - WAV: ✅ (nativo)");
            System.out.println("[RegionVisualizer]   - OGG: " + (oggSupported ? "✅" : "❌"));
            
            if (!oggSupported) {
                sendMessageSync("⚠️ Soporte OGG no detectado. Revisa las dependencias.", ChatFormatting.YELLOW);
            }
            
        } catch (Exception e) {
            System.err.println("[RegionVisualizer] ❌ Error verificando formatos: " + e.getMessage());
            sendMessageSync("❌ Error verificando formatos de audio", ChatFormatting.RED);
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
            System.err.println("[RegionVisualizer] ❌ Error verificando soporte para " + extension);
            return false;
        }
    }
    
    private static void logSupportedFormats() {
        try {
            System.out.println("[RegionVisualizer] Formatos disponibles:");
            for (String format : SUPPORTED_FORMATS) {
                boolean supported = isFormatSupported(format);
                System.out.println("[RegionVisualizer]   - " + format.toUpperCase() + ": " + (supported ? "✅" : "❌"));
            }
        } catch (Exception e) {
            System.err.println("[RegionVisualizer] ❌ Error verificando formatos: " + e.getMessage());
        }
    }
    
    // ========================================
    // UTILIDADES Y HELPERS
    // ========================================
    
    public static String getFileExtension(String filename) {
        int lastDotIndex = filename.lastIndexOf('.');
        if (lastDotIndex > 0) {
            return filename.substring(lastDotIndex);
        }
        return "";
    }
    
    public static String formatFileSize(long bytes) {
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
                System.out.println("[RegionVisualizer] ✅ Archivo de ayuda creado: " + helpFile);
            }
        } catch (IOException e) {
            System.err.println("[RegionVisualizer] ❌ Error creando archivo de ayuda: " + e.getMessage());
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
                System.out.println("[RegionVisualizer] ✅ Clase encontrada: " + className);
            } catch (ClassNotFoundException e) {
                System.out.println("[RegionVisualizer] ❌ Clase NO encontrada: " + className);
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
                    System.out.println("[RegionVisualizer] ✅ Archivo OGG leído exitosamente");
                    System.out.println("[RegionVisualizer] Formato: " + stream.getFormat());
                    sendMessageSync("[RegionVisualizer] ✅ ¡OGG soportado y funcional!", ChatFormatting.GREEN);
                } catch (UnsupportedAudioFileException e) {
                    System.err.println("[RegionVisualizer] ❌ Archivo OGG no soportado: " + e.getMessage());
                    sendMessageSync("[RegionVisualizer] ❌ OGG no soportado: " + e.getMessage(), ChatFormatting.RED);
                }
            } else {
                System.out.println("[RegionVisualizer] ⚠️ No se encontraron archivos OGG para probar");
                sendMessageSync("⚠️ No hay archivos OGG para probar", ChatFormatting.YELLOW);
            }
        } catch (Exception e) {
            System.err.println("[RegionVisualizer] ❌ Error accediendo a archivos OGG: " + e.getMessage());
        }

        System.out.println("[RegionVisualizer] === FIN DEBUG ===");
    }

	//========================================
	//MÉTODOS PÚBLICOS PARA LA CONFIGURACIÓN
	//========================================
	
	/**
	* Obtiene la duración actual del fade en segundos
	*/
	public static float getFadeDuration() {
	 return fadeDuration;
	}
	
	/**
	* Establece la duración del fade (0.1 - 10.0 segundos)
	*/
	public static void setFadeDuration(float duration) {
	 synchronized (audioLock) {
	     fadeDuration = Math.max(0.1f, Math.min(10.0f, duration));
	     saveConfig();
	     System.out.println("[RegionVisualizer] 🎚️ Duración de fade establecida a: " + fadeDuration + "s");
	 }
	}
	
	/**
	* Obtiene el volumen máximo del mod
	*/
	public static float getMaxModVolume() {
	 return maxModVolume;
	}
	
	/**
	* Establece el volumen máximo del mod (0.1 - 1.0)
	*/
	public static void setMaxModVolume(float maxVolume) {
	 synchronized (audioLock) {
	     maxModVolume = Math.max(0.1f, Math.min(1.0f, maxVolume));
	     
	     // Ajustar el volumen actual si excede el nuevo máximo
	     if (modVolume > maxModVolume) {
	         modVolume = maxModVolume;
	         setClipVolume(modVolume);
	     }
	     
	     saveConfig();
	     System.out.println("[RegionVisualizer] 🔊 Volumen máximo establecido a: " + (maxModVolume * 100) + "%");
	 }
	}
	
	/**
	* Obtiene el valor de fade inicial
	*/
	public static float getFadeInStart() {
	 return fadeInStart;
	}
	
	/**
	* Establece el valor de fade inicial (0.0 - 1.0)
	*/
	public static void setFadeInStart(float fadeStart) {
	 synchronized (audioLock) {
	     fadeInStart = Math.max(0.0f, Math.min(maxModVolume, fadeStart));
	     saveConfig();
	     System.out.println("[RegionVisualizer] 🎵 Fade inicial establecido a: " + (fadeInStart * 100) + "%");
	 }
	}
	
	/**
	* Obtiene información del estado actual del sistema
	*/
	public static String getSystemStatus() {
	 synchronized (audioLock) {
	     StringBuilder status = new StringBuilder();
	     status.append("Sistema inicializado: ").append(initialized ? "✅" : "❌").append("\n");
	     status.append("Reproduciendo: ").append(isPlaying.get() ? "✅" : "❌").append("\n");
	     status.append("Volumen actual: ").append(Math.round(modVolume * 100)).append("%\n");
	     status.append("Fade duration: ").append(fadeDuration).append("s\n");
	     status.append("Cache RAM: ").append(ramCache.size()).append("/").append(MAX_RAM_CACHED_FILES).append("\n");
	     status.append("Cache DISK: ").append(diskCache.size()).append("/").append(MAX_DISK_CACHED_FILES);
	     return status.toString();
	 }
	}
	
	/**
	* Verifica si un archivo específico está en cache
	*/
	public static boolean isFileCached(String filename) {
	 synchronized (audioLock) {
	     if (!initialized || musicFolder == null) return false;
	     
	     String cacheKey = musicFolder.resolve(filename).toString();
	     return ramCache.containsKey(cacheKey) || diskCache.containsKey(cacheKey);
	 }
	}
	
	/**
	* Obtiene estadísticas rápidas del cache para mostrar en UI
	*/
	public static String getCacheQuickStats() {
	 synchronized (audioLock) {
	     long ramMemory = ramCache.values().stream()
	         .mapToLong(data -> data.audioData.length)
	         .sum();
	     
	     long diskMemory = diskCache.values().stream()
	         .mapToLong(info -> info.fileSize)
	         .sum();
	         
	     return String.format("RAM: %d files (%s) | DISK: %d files (%s)", 
	         ramCache.size(), formatFileSize(ramMemory),
	         diskCache.size(), formatFileSize(diskMemory));
	 }
	}
	
	//========================================
	//MÉTODOS DE VALIDACIÓN Y TESTING
	//========================================
	
	/**
	* Prueba si un archivo es reproducible sin añadirlo al cache
	*/
	public static boolean testFile(String filename) {
	 if (!initialized) {
	     initialize();
	 }
	 
	 synchronized (audioLock) {
	     try {
	         Path filePath = musicFolder.resolve(filename);
	         if (!Files.exists(filePath)) {
	             return false;
	         }
	         
	         // Probar lectura básica
	         try (AudioInputStream testStream = AudioSystem.getAudioInputStream(filePath.toFile())) {
	             AudioFormat format = testStream.getFormat();
	             
	             // Verificar si es reproducible directamente o necesita conversión
	             if (isPlayableFormat(format)) {
	                 return true;
	             }
	             
	             // Probar conversión
	             AudioFormat targetFormat = new AudioFormat(
	                 AudioFormat.Encoding.PCM_SIGNED,
	                 format.getSampleRate(),
	                 16,
	                 format.getChannels(),
	                 format.getChannels() * 2,
	                 format.getSampleRate(),
	                 false
	             );
	             
	             return AudioSystem.isConversionSupported(targetFormat, format);
	         }
	         
	     } catch (Exception e) {
	         System.err.println("[RegionVisualizer] ❌ Error probando archivo " + filename + ": " + e.getMessage());
	         return false;
	     }
	 }
	}
	
	/**
	* Obtiene información detallada de un archivo
	*/
	public static String getFileInfo(String filename) {
	 if (!initialized) {
	     return "Sistema no inicializado";
	 }
	 
	 synchronized (audioLock) {
	     try {
	         Path filePath = musicFolder.resolve(filename);
	         if (!Files.exists(filePath)) {
	             return "Archivo no encontrado";
	         }
	         
	         long fileSize = Files.size(filePath);
	         String extension = getFileExtension(filename).toUpperCase();
	         
	         try (AudioInputStream stream = AudioSystem.getAudioInputStream(filePath.toFile())) {
	             AudioFormat format = stream.getFormat();
	             
	             StringBuilder info = new StringBuilder();
	             info.append("Tamaño: ").append(formatFileSize(fileSize)).append("\n");
	             info.append("Formato: ").append(extension).append("\n");
	             info.append("Encoding: ").append(format.getEncoding()).append("\n");
	             info.append("Sample Rate: ").append((int)format.getSampleRate()).append(" Hz\n");
	             info.append("Bits: ").append(format.getSampleSizeInBits()).append("\n");
	             info.append("Canales: ").append(format.getChannels()).append("\n");
	             info.append("Reproducible: ").append(testFile(filename) ? "✅" : "❌");
	             
	             return info.toString();
	         }
	         
	     } catch (Exception e) {
	         return "Error leyendo archivo: " + e.getMessage();
	     }
	 }
	}
	
	//========================================
	//MÉTODOS AUXILIARES PARA UI
	//========================================
	
	/**
	* Lista todos los archivos de música disponibles con información básica
	*/
	public static List<MusicFileInfo> getMusicFilesList() {
	 List<MusicFileInfo> musicFiles = new ArrayList<>();
	 
	 if (!initialized) {
	     initialize();
	 }
	 
	 synchronized (audioLock) {
	     try {
	         if (!Files.exists(musicFolder)) {
	             return musicFiles; // Lista vacía
	         }
	         
	         Files.list(musicFolder)
	             .filter(path -> {
	                 String name = path.getFileName().toString().toLowerCase();
	                 return name.endsWith(".wav") || name.endsWith(".ogg");
	             })
	             .sorted()
	             .forEach(path -> {
	                 try {
	                     String filename = path.getFileName().toString();
	                     long size = Files.size(path);
	                     String extension = getFileExtension(filename);
	                     boolean supported = testFile(filename);
	                     boolean cached = isFileCached(filename);
	                     
	                     musicFiles.add(new MusicFileInfo(filename, extension, size, supported, cached));
	                     
	                 } catch (Exception e) {
	                     System.err.println("[RegionVisualizer] ❌ Error procesando archivo: " + e.getMessage());
	                 }
	             });
	             
	     } catch (Exception e) {
	         System.err.println("[RegionVisualizer] ❌ Error listando archivos: " + e.getMessage());
	     }
	 }
	 
	 return musicFiles;
	}
	
	/**
	* Clase para información de archivos de música
	*/
	public static class MusicFileInfo {
	 public final String filename;
	 public final String extension;
	 public final long size;
	 public final boolean supported;
	 public final boolean cached;
	 
	 public MusicFileInfo(String filename, String extension, long size, boolean supported, boolean cached) {
	     this.filename = filename;
	     this.extension = extension;
	     this.size = size;
	     this.supported = supported;
	     this.cached = cached;
	 }
	 
	 public String getFormattedSize() {
	     return formatFileSize(size);
	 }
	 
	 public String getDisplayName() {
	     String emoji = supported ? (cached ? "💾" : "🎵") : "⚠️";
	     return emoji + " " + filename;
	 }
	 
	 public String getDisplayInfo() {
	     return extension.toUpperCase() + " • " + getFormattedSize() + (cached ? " • Cached" : "");
	 		}
		}
	}