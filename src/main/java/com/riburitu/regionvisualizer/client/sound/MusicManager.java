package com.riburitu.regionvisualizer.client.sound;

import com.riburitu.regionvisualizer.RegionVisualizer;
import com.riburitu.regionvisualizer.util.Region;
import com.riburitu.regionvisualizer.util.RegionManager;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.world.level.Level;

import javax.sound.sampled.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class MusicManager {
    private static AudioInputStream currentAudioStream = null;
    private static Clip currentClip = null;
    private static AudioInputStream previousAudioStream = null;
    private static Clip previousClip = null;
    private static FloatControl previousVolumeControl = null;
    private static Path musicFolder = null;
    private static boolean initialized = false;
    private static final AtomicBoolean isPlaying = new AtomicBoolean(false);
    private static final AtomicBoolean isPreviousPlaying = new AtomicBoolean(false);
    private static volatile boolean shouldStopPrevious = false;
    private static final Object audioLock = new Object();
    private static FloatControl volumeControl = null;
    private static float lastLoggedVolume = -1.0f;
    private static float modVolume = 0.85f; // Volumen del mod, por defecto 85%
    private static float maxModVolume = 0.85f; // Máximo volumen permitido, por defecto 85%
    private static float fadeDuration = 5.0f; // Duración del fade en segundos, por defecto 5s
    private static long fadeInterval = 50; // Intervalo entre pasos en milisegundos, por defecto 50ms
    private static float fadeInStart = 0.45f; // Volumen inicial para fade-in, por defecto 45%
    private static final Path configFile = Paths.get(Minecraft.getInstance().gameDirectory.getAbsolutePath(), "config", "regionvisualizer.properties");
    private static final ExecutorService fadeExecutor = Executors.newFixedThreadPool(2); // Hilo para fade-in y fade-out simultáneos

    public static void initialize() {
        if (initialized) return;

        synchronized (audioLock) {
            try {
                File gameDir = Minecraft.getInstance().gameDirectory;
                musicFolder = Paths.get(gameDir.getAbsolutePath(), "music");

                if (!Files.exists(musicFolder)) {
                    Files.createDirectories(musicFolder);
                    System.out.println("[RegionVisualizer] ✅ Carpeta de música creada: " + musicFolder);
                    createHelpFile();
                } else {
                    System.out.println("[RegionVisualizer] ✅ Carpeta de música encontrada: " + musicFolder);
                }

                if (AudioSystem.getMixerInfo().length == 0) {
                    System.err.println("[RegionVisualizer] ⚠️ No se encontraron dispositivos de audio");
                    sendMessageSync("⚠️ No se encontraron dispositivos de audio", ChatFormatting.RED);
                } else {
                    System.out.println("[RegionVisualizer] ✅ Sistema de audio Java Sound inicializado");
                }

                loadConfig();
                initialized = true;
                listAvailableFiles();

            } catch (Exception e) {
                System.err.println("[RegionVisualizer] ❌ Error inicializando sistema de música: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    private static void loadConfig() {
        try {
            Properties props = new Properties();
            if (Files.exists(configFile)) {
                try (FileInputStream in = new FileInputStream(configFile.toFile())) {
                    props.load(in);
                    String volumeStr = props.getProperty("modVolume", "0.85");
                    modVolume = Float.parseFloat(volumeStr);
                    modVolume = Math.max(0.0f, Math.min(maxModVolume, modVolume));

                    String maxVolumeStr = props.getProperty("maxModVolume", "0.85");
                    maxModVolume = Float.parseFloat(maxVolumeStr);
                    maxModVolume = Math.max(0.0f, Math.min(1.0f, maxModVolume));

                    String fadeDurationStr = props.getProperty("fadeDuration", "5.0");
                    fadeDuration = Float.parseFloat(fadeDurationStr);
                    fadeDuration = Math.max(0.1f, fadeDuration);

                    String fadeIntervalStr = props.getProperty("fadeInterval", "50");
                    fadeInterval = Long.parseLong(fadeIntervalStr);
                    fadeInterval = Math.max(10L, Math.min(500L, fadeInterval));

                    String fadeInStartStr = props.getProperty("fadeInStart", "0.45");
                    fadeInStart = Float.parseFloat(fadeInStartStr);
                    fadeInStart = Math.max(0.0f, Math.min(maxModVolume, fadeInStart));

                    System.out.println("[RegionVisualizer] ✅ Configuración cargada: modVolume=" + (modVolume * 100) + "%, maxModVolume=" + (maxModVolume * 100) + "%, fadeDuration=" + fadeDuration + "s, fadeInterval=" + fadeInterval + "ms, fadeInStart=" + (fadeInStart * 100) + "%");
                }
            } else {
                Files.createDirectories(configFile.getParent());
                saveConfig();
            }
        } catch (NumberFormatException e) {
            System.err.println("[RegionVisualizer] ❌ Error parseando configuración: " + e.getMessage());
            sendMessageSync("❌ Error parseando configuración, usando valores por defecto", ChatFormatting.RED);
            resetToDefaults();
            saveConfig();
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("[RegionVisualizer] ❌ Error cargando configuración: " + e.getMessage());
            sendMessageSync("❌ Error cargando configuración: " + e.getMessage(), ChatFormatting.RED);
            resetToDefaults();
            saveConfig();
            e.printStackTrace();
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

    public static void saveConfig() {
        try {
            Properties props = new Properties();
            props.setProperty("modVolume", String.valueOf(modVolume));
            props.setProperty("maxModVolume", String.valueOf(maxModVolume));
            props.setProperty("fadeDuration", String.valueOf(fadeDuration));
            props.setProperty("fadeInterval", String.valueOf(fadeInterval));
            props.setProperty("fadeInStart", String.valueOf(fadeInStart));
            try (FileOutputStream out = new FileOutputStream(configFile.toFile())) {
                props.store(out, "RegionVisualizer Configuration\n" +
                        "modVolume: Current volume level (0.0 to maxModVolume)\n" +
                        "maxModVolume: Maximum volume level (0.0 to 1.0)\n" +
                        "fadeDuration: Fade-in/fade-out duration in seconds (minimum 0.1)\n" +
                        "fadeInterval: Interval between volume steps in milliseconds (10 to 500)\n" +
                        "fadeInStart: Starting volume for fade-in (0.0 to maxModVolume)");
                System.out.println("[RegionVisualizer] ✅ Configuración guardada: modVolume=" + (modVolume * 100) + "%, maxModVolume=" + (maxModVolume * 100) + "%, fadeDuration=" + fadeDuration + "s, fadeInterval=" + fadeInterval + "ms, fadeInStart=" + (fadeInStart * 100) + "%");
            }
        } catch (Exception e) {
            System.err.println("[RegionVisualizer] ❌ Error guardando configuración: " + e.getMessage());
            sendMessageSync("❌ Error guardando configuración: " + e.getMessage(), ChatFormatting.RED);
            e.printStackTrace();
        }
    }

    private static void fadeIn() {
        fadeExecutor.submit(() -> {
            float targetVolume = modVolume;
            float startVolume = fadeInStart * modVolume;
            long startTime = System.currentTimeMillis();
            long endTime = startTime + (long)(fadeDuration * 1000);

            synchronized (audioLock) {
                if (currentClip == null || !isPlaying.get()) {
                    System.out.println("[RegionVisualizer] ⚠️ Fade-in cancelado: clip no disponible o reproducción detenida");
                    cleanupResources();
                    return;
                }

                System.out.println("[RegionVisualizer] 🔍 Estado de fadeExecutor antes de fade-in: isShutdown=" + fadeExecutor.isShutdown() + ", isTerminated=" + fadeExecutor.isTerminated());
                setClipVolume(startVolume);
                System.out.println("[RegionVisualizer] 🔥 Fade-in iniciado: startVolume=" + (startVolume * 100) + "%, targetVolume=" + (targetVolume * 100) + "%");
                currentClip.setFramePosition(0);
                currentClip.start();
            }

            while (System.currentTimeMillis() < endTime && isPlaying.get()) {
                synchronized (audioLock) {
                    if (currentClip == null || !isPlaying.get()) {
                        System.out.println("[RegionVisualizer] ⚠️ Fade-in interrumpido: clip no disponible");
                        cleanupResources();
                        return;
                    }
                    float progress = (System.currentTimeMillis() - startTime) / (float)(fadeDuration * 1000);
                    progress = Math.min(1.0f, Math.max(0.0f, progress));
                    float currentVolume = startVolume + (targetVolume - startVolume) * progress;
                    currentVolume = Math.min(currentVolume, modVolume);
                    setClipVolume(currentVolume);
                    System.out.println("[RegionVisualizer] 🔊 Fade-in volumen: " + (currentVolume * 100) + "%");
                }
                try {
                    Thread.sleep(fadeInterval);
                } catch (InterruptedException e) {
                    System.err.println("[RegionVisualizer] ❌ Interrupción durante fade-in: " + e.getMessage());
                    cleanupResources();
                    return;
                }
            }

            synchronized (audioLock) {
                if (isPlaying.get()) {
                    setClipVolume(targetVolume);
                    System.out.println("[RegionVisualizer] ✅ Fade-in completado, volumen final: " + (targetVolume * 100) + "%");
                }
            }
        });
    }

    private static void fadeOutPrevious() {
        fadeExecutor.submit(() -> {
            float startVolume = modVolume;
            float targetVolume = 0.0f;
            long startTime = System.currentTimeMillis();
            long endTime = startTime + (long)(fadeDuration * 1000);

            synchronized (audioLock) {
                if (previousClip == null || !previousClip.isOpen() || !previousClip.isRunning()) {
                    System.out.println("[RegionVisualizer] ⚠️ Fade-out anterior cancelado: clip no disponible o no está reproduciendo");
                    cleanupPreviousResources();
                    return;
                }
                isPreviousPlaying.set(true); // Marcar que el clip anterior está en fade-out
                System.out.println("[RegionVisualizer] 🔍 Estado de fadeExecutor antes de fade-out: isShutdown=" + fadeExecutor.isShutdown() + ", isTerminated=" + fadeExecutor.isTerminated());
                System.out.println("[RegionVisualizer] 🔍 Estado de previousClip: isOpen=" + previousClip.isOpen() + ", isRunning=" + previousClip.isRunning() + ", isActive=" + previousClip.isActive());
                System.out.println("[RegionVisualizer] 🔥 Fade-out anterior iniciado: startVolume=" + (startVolume * 100) + "%, targetVolume=" + (targetVolume * 100) + "%");
                setPreviousClipVolume(startVolume);
            }

            while (System.currentTimeMillis() < endTime && isPreviousPlaying.get()) {
                synchronized (audioLock) {
                    if (previousClip == null || !previousClip.isOpen() || !previousClip.isRunning() || !isPreviousPlaying.get()) {
                        System.out.println("[RegionVisualizer] ⚠️ Fade-out anterior interrumpido: clip no disponible o no está reproduciendo");
                        break;
                    }
                    float progress = (System.currentTimeMillis() - startTime) / (float)(fadeDuration * 1000);
                    progress = Math.min(1.0f, Math.max(0.0f, progress));
                    float currentVolume = startVolume * (1.0f - progress); // Fade-out lineal hasta 0
                    setPreviousClipVolume(currentVolume);
                    System.out.println("[RegionVisualizer] 🔊 Fade-out anterior volumen: " + (currentVolume * 100) + "%");
                }
                try {
                    Thread.sleep(fadeInterval);
                } catch (InterruptedException e) {
                    System.err.println("[RegionVisualizer] ❌ Interrupción durante fade-out anterior: " + e.getMessage());
                    break;
                }
            }

            synchronized (audioLock) {
                if (previousClip != null && previousClip.isOpen()) {
                    setPreviousClipVolume(targetVolume);
                }
                isPreviousPlaying.set(false);
                cleanupPreviousResources();
                System.out.println("[RegionVisualizer] ✅ Fade-out anterior completado, recursos liberados");
            }
        });
    }

    public static void play(String filename, boolean loop, boolean fade) {
        synchronized (audioLock) {
            playInternal(filename, loop, fade);
        }
    }

    
    /**
     * Abre un archivo de audio (WAV/OGG/MP3 mediante SPI) y garantiza conversión a PCM_SIGNED
     * para compatibilidad con javax.sound.sampled y control de volumen.
     * Asigna currentAudioStream, currentClip y volumeControl.
     */
    private static void openClipFromFile(java.nio.file.Path filePath) throws Exception {
        // 1) Obtener AudioInputStream con los SPIs (vorbisspi/mp3spi) en el classpath
        AudioInputStream srcStream = AudioSystem.getAudioInputStream(filePath.toFile());

        // 2) Forzar a PCM_SIGNED si fuera necesario (algunos SPIs devuelven ULAW/ALAW o formatos comprimidos)
        AudioFormat baseFormat = srcStream.getFormat();
        AudioFormat decodedFormat;
        if (baseFormat.getEncoding() != AudioFormat.Encoding.PCM_SIGNED) {
            decodedFormat = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    baseFormat.getSampleRate(),
                    16, // 16-bit para mayoría de casos
                    baseFormat.getChannels(),
                    baseFormat.getChannels() * 2, // frame size = 16-bit * channels
                    baseFormat.getSampleRate(),
                    false // little-endian
            );
            srcStream = AudioSystem.getAudioInputStream(decodedFormat, srcStream);
        }

        // 3) Guardar referencia para cierre posterior
        currentAudioStream = srcStream;

        // 4) Crear y abrir el Clip con el stream ya decodificado
        currentClip = AudioSystem.getClip();
        currentClip.open(currentAudioStream);

        // 5) Intentar obtener control de volumen
        if (currentClip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
            volumeControl = (FloatControl) currentClip.getControl(FloatControl.Type.MASTER_GAIN);
        } else {
            volumeControl = null;
        }
    }
private static void playInternal(String filename, boolean loop, boolean fade) {
        synchronized (audioLock) {
            System.out.println("[RegionVisualizer] 🔍 Iniciando playInternal: filename=" + filename + ", loop=" + loop + ", fade=" + fade);
            System.out.println("[RegionVisualizer] 🔍 Estado inicial: isPlaying=" + isPlaying.get() + ", currentClip=" + (currentClip != null ? "existe, isRunning=" + currentClip.isRunning() : "null") + ", previousClip=" + (previousClip != null ? "existe, isRunning=" + previousClip.isRunning() : "null"));

            // Limpiar cualquier clip anterior que aún esté en fade-out
            if (isPreviousPlaying.get()) {
                isPreviousPlaying.set(false); // Cancelar el fade-out anterior
                cleanupPreviousResources();
                System.out.println("[RegionVisualizer] 🔍 Cancelando fade-out anterior y limpiando recursos");
            }

            if (currentClip != null && isPlaying.get()) {
                previousClip = currentClip;
                previousAudioStream = currentAudioStream;
                previousVolumeControl = volumeControl;
                currentClip = null;
                currentAudioStream = null;
                volumeControl = null;
                System.out.println("[RegionVisualizer] 🔍 Moviendo currentClip a previousClip para fade-out");
                if (fade && previousVolumeControl != null && previousClip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                    shouldStopPrevious = true;
                    System.out.println("[RegionVisualizer] 🔍 Enviando tarea de fade-out a fadeExecutor");
                    fadeOutPrevious();
                } else {
                    cleanupPreviousResources();
                    System.out.println("[RegionVisualizer] 🔍 Fade-out no requerido o no soportado, limpiando recursos anteriores");
                }
            } else {
                cleanupPreviousResources();
                System.out.println("[RegionVisualizer] 🔍 No hay clip actual, limpiando recursos anteriores");
            }

            if (filename == null || filename.trim().isEmpty()) {
                System.err.println("[RegionVisualizer] ⚠️ Nombre de archivo vacío");
                sendMessageSync("❌ Nombre de archivo de música vacío", ChatFormatting.RED);
                return;
            }

            if (filename.equals("a.wav")) {
                System.err.println("[RegionVisualizer] ⚠️ Intentando reproducir a.wav");
                new Exception("Rastreo de a.wav").printStackTrace();
            }

            Path filePath = musicFolder.resolve(filename);
            if (!Files.exists(filePath)) {
                System.err.println("[RegionVisualizer] ❌ Archivo no encontrado: " + filePath);
                sendMessageSync("❌ Archivo de música no encontrado: " + filename, ChatFormatting.RED);
                return;
            }

            try {
                System.out.println("[RegionVisualizer] 🔍 Intentando cargar archivo: " + filePath);
                openClipFromFile(filePath);


                if (currentClip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                    volumeControl = (FloatControl) currentClip.getControl(FloatControl.Type.MASTER_GAIN);
                    System.out.println("[RegionVisualizer] ✅ Control de volumen soportado para: " + filename);
                } else {
                    System.err.println("[RegionVisualizer] ⚠️ Control de volumen no soportado para: " + filename);
                    sendMessageSync("⚠️ El archivo " + filename + " no soporta control de volumen. Fade desactivado.", ChatFormatting.YELLOW);
                    fade = false;
                }

                if (loop) {
                    currentClip.loop(Clip.LOOP_CONTINUOUSLY);
                    System.out.println("[RegionVisualizer] 🔄 Bucle activado para: " + filename);
                }

                isPlaying.set(true);
                shouldStopPrevious = false;

                System.out.println("[RegionVisualizer] 🎵 Preparando reproducción: " + filename + ", loop=" + loop + ", fade=" + fade);

                if (fade && volumeControl != null) {
                    System.out.println("[RegionVisualizer] 🔍 Enviando tarea de fade-in a fadeExecutor");
                    fadeIn();
                } else {
                    setClipVolume(modVolume);
                    System.out.println("[RegionVisualizer] 🔊 Volumen inicial sin fade: " + (modVolume * 100) + "%");
                    currentClip.setFramePosition(0);
                    currentClip.start();
                }

            } catch (UnsupportedAudioFileException e) {
                System.err.println("[RegionVisualizer] ❌ Formato de audio no soportado: " + filename);
                sendMessageSync("❌ Formato de audio no soportado: " + filename, ChatFormatting.RED);
                cleanupResources();
            } catch (IOException e) {
                System.err.println("[RegionVisualizer] ❌ Error de I/O al reproducir: " + filename);
                sendMessageSync("❌ Error al reproducir: " + filename, ChatFormatting.RED);
                cleanupResources();
            } catch (LineUnavailableException e) {
                System.err.println("[RegionVisualizer] ❌ Línea de audio no disponible");
                sendMessageSync("❌ No se pudo reproducir: Línea de audio no disponible", ChatFormatting.RED);
                cleanupResources();
            } catch (Exception e) {
                System.err.println("[RegionVisualizer] ❌ Error inesperado reproduciendo: " + e.getMessage());
                sendMessageSync("❌ Error inesperado: " + e.getMessage(), ChatFormatting.RED);
                e.printStackTrace();
                cleanupResources();
            }
        }
    }

    public static void stop(boolean fade) {
        synchronized (audioLock) {
            stopInternal(fade);
        }
    }

    private static void stopInternal(boolean fade) {
        synchronized (audioLock) {
            // Primero, cancelar cualquier fade-out anterior
            if (isPreviousPlaying.get()) {
                isPreviousPlaying.set(false);
                cleanupPreviousResources();
                System.out.println("[RegionVisualizer] 🔍 Cancelando fade-out anterior en stopInternal");
            }

            if (currentClip == null || !isPlaying.get()) {
                System.out.println("[RegionVisualizer] ⚠️ No hay música reproduciéndose para detener");
                cleanupPreviousResources();
                return;
            }
            shouldStopPrevious = true;

            if (fade && volumeControl != null && currentClip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                previousClip = currentClip;
                previousAudioStream = currentAudioStream;
                previousVolumeControl = volumeControl;
                currentClip = null;
                currentAudioStream = null;
                volumeControl = null;
                isPlaying.set(false); // Marcar como no reproduciendo inmediatamente
                System.out.println("[RegionVisualizer] 🔍 Moviendo currentClip a previousClip para fade-out en stopInternal");
                System.out.println("[RegionVisualizer] 🔍 Enviando tarea de fade-out a fadeExecutor en stopInternal");
                fadeOutPrevious();
            } else {
                cleanupResources();
                System.out.println("[RegionVisualizer] 🛑 Música detenida, fade=" + fade);
            }
        }
    }

    public static void pauseMusic(boolean fade) {
        if (!Minecraft.getInstance().isSingleplayer()) {
            System.out.println("[RegionVisualizer] 🔍 Pausa de música ignorada: no está en singleplayer");
            return;
        }
        synchronized (audioLock) {
            if (currentClip == null || !isPlaying.get()) {
                System.out.println("[RegionVisualizer] ⚠️ No hay música reproduciéndose para pausar");
                return;
            }
            stopInternal(fade);
            System.out.println("[RegionVisualizer] 🛑 Música pausada en singleplayer, fade=" + fade);
        }
    }

    public static void resumeMusic(LocalPlayer player) {
        System.out.println("[RegionVisualizer] 🔍 Iniciando resumeMusic para jugador: " + (player != null ? player.getName().getString() : "null"));
        if (!Minecraft.getInstance().isSingleplayer()) {
            System.out.println("[RegionVisualizer] 🔍 Reanudación de música ignorada: no está en singleplayer");
            return;
        }
        synchronized (audioLock) {
            System.out.println("[RegionVisualizer] 🔍 Estado de isPlaying: " + isPlaying.get());
            System.out.println("[RegionVisualizer] 🔍 Estado del sistema de audio: initialized=" + initialized + ", currentClip=" + (currentClip != null ? "existe" : "null") + ", previousClip=" + (previousClip != null ? "existe" : "null"));
            // Permitir reanudación si no hay clip activo, incluso si isPlaying es true
            if (isPlaying.get() && currentClip != null) {
                System.out.println("[RegionVisualizer] ⚠️ No se puede reanudar música: ya está reproduciendo con clip activo");
                return;
            }
            if (player == null) {
                System.out.println("[RegionVisualizer] ⚠️ Jugador es null, no se puede reanudar música");
                return;
            }
            Level level = player.level();
            System.out.println("[RegionVisualizer] 🔍 Nivel del jugador: " + (level != null ? level.dimension().location() : "null"));
            System.out.println("[RegionVisualizer] 🔍 Posición del jugador: " + player.blockPosition());
            String regionName = RegionVisualizer.getCurrentRegion(level, player.blockPosition());
            System.out.println("[RegionVisualizer] 🔍 Región encontrada: " + (regionName != null ? regionName : "ninguna"));
            if (regionName != null) {
                RegionManager regionManager = RegionVisualizer.INSTANCE.getRegionManager();
                System.out.println("[RegionVisualizer] 🔍 RegionManager obtenido: " + (regionManager != null ? "válido" : "null"));
                System.out.println("[RegionVisualizer] 🔍 Regiones disponibles: " + regionManager.getRegions().stream().map(Region::getName).collect(Collectors.joining(", ")));
                Optional<Region> regionOpt = regionManager.getRegionByName(regionName);
                if (regionOpt.isPresent()) {
                    Region region = regionOpt.get();
                    System.out.println("[RegionVisualizer] 🔥 Reanudando música para región: " + regionName + ", música: " + region.getMusicFile() + ", loop=" + region.isLoopEnabled() + ", fade=" + region.isFadeEnabled());
                    // Forzar limpieza de recursos antes de reproducir
                    cleanupResources();
                    cleanupPreviousResources();
                    // Forzar reinicialización del sistema de audio
                    initialized = false;
                    initialize();
                    playInternal(region.getMusicFile(), region.isLoopEnabled(), region.isFadeEnabled());
                } else {
                    System.out.println("[RegionVisualizer] ⚠️ No se encontró región para reanudar música: " + regionName);
                    sendMessageSync("⚠️ Región no encontrada: " + regionName, ChatFormatting.YELLOW);
                }
            } else {
                System.out.println("[RegionVisualizer] ⚠️ No se puede reanudar música: jugador no está en una región");
                sendMessageSync("⚠️ No estás en una región con música", ChatFormatting.YELLOW);
            }
        }
    }

    private static void cleanupResources() {
        synchronized (audioLock) {
            if (currentClip != null) {
                try {
                    currentClip.stop();
                    currentClip.flush();
                    currentClip.close();
                    System.out.println("[RegionVisualizer] ✅ Clip actual detenido y cerrado");
                } catch (Exception e) {
                    System.err.println("[RegionVisualizer] ⚠️ Error cerrando clip actual: " + e.getMessage());
                    e.printStackTrace();
                }
                currentClip = null;
            }
            if (currentAudioStream != null) {
                try {
                    currentAudioStream.close();
                    System.out.println("[RegionVisualizer] ✅ Audio stream actual cerrado");
                } catch (IOException e) {
                    System.err.println("[RegionVisualizer] ⚠️ Error cerrando audio stream actual: " + e.getMessage());
                    e.printStackTrace();
                }
                currentAudioStream = null;
            }
            isPlaying.set(false);
            volumeControl = null;
            lastLoggedVolume = -1.0f;
            System.out.println("[RegionVisualizer] 🛑 Recursos de audio actuales liberados, isPlaying=false");
        }
    }

    private static void cleanupPreviousResources() {
        synchronized (audioLock) {
            isPreviousPlaying.set(false); // Cancelar cualquier fade-out en progreso
            if (previousClip != null) {
                try {
                    previousClip.stop();
                    previousClip.flush();
                    previousClip.close();
                    System.out.println("[RegionVisualizer] ✅ Clip anterior detenido y cerrado");
                } catch (Exception e) {
                    System.err.println("[RegionVisualizer] ⚠️ Error cerrando clip anterior: " + e.getMessage());
                    e.printStackTrace();
                }
                previousClip = null;
            }
            if (previousAudioStream != null) {
                try {
                    previousAudioStream.close();
                    System.out.println("[RegionVisualizer] ✅ Audio stream anterior cerrado");
                } catch (IOException e) {
                    System.err.println("[RegionVisualizer] ⚠️ Error cerrando audio stream anterior: " + e.getMessage());
                    e.printStackTrace();
                }
                previousAudioStream = null;
            }
            previousVolumeControl = null;
            shouldStopPrevious = false;
            System.out.println("[RegionVisualizer] 🛑 Recursos de audio anteriores liberados");
        }
    }

    private static void setClipVolume(float volume) {
        synchronized (audioLock) {
            if (volumeControl == null || currentClip == null || !currentClip.isOpen()) {
                System.out.println("[RegionVisualizer] ⚠️ No se puede ajustar volumen actual: volumeControl o clip no disponible");
                return;
            }
            volume = Math.min(volume, maxModVolume);
            float min = volumeControl.getMinimum();
            float max = volumeControl.getMaximum();
            float gain = min + (max - min) * volume;
            gain = Math.max(min, Math.min(max, gain));
            try {
                volumeControl.setValue(gain);
                lastLoggedVolume = gain;
                System.out.println("[RegionVisualizer] 🔊 Volumen actual aplicado: " + (volume * 100) + "% (gain=" + gain + "), clip activo: " + (currentClip != null && currentClip.isActive()));
            } catch (Exception e) {
                System.err.println("[RegionVisualizer] ⚠️ Error ajustando volumen actual: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    private static void setPreviousClipVolume(float volume) {
        synchronized (audioLock) {
            if (previousVolumeControl == null || previousClip == null || !previousClip.isOpen()) {
                System.out.println("[RegionVisualizer] ⚠️ No se puede ajustar volumen anterior: volumeControl o clip no disponible");
                return;
            }
            volume = Math.min(volume, maxModVolume);
            float min = previousVolumeControl.getMinimum();
            float max = previousVolumeControl.getMaximum();
            float gain = min + (max - min) * volume;
            gain = Math.max(min, Math.min(max, gain));
            try {
                previousVolumeControl.setValue(gain);
                System.out.println("[RegionVisualizer] 🔊 Volumen anterior aplicado: " + (volume * 100) + "% (gain=" + gain + "), clip anterior activo: " + (previousClip != null && previousClip.isActive()));
            } catch (Exception e) {
                System.err.println("[RegionVisualizer] ⚠️ Error ajustando volumen anterior: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

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
            System.out.println("[RegionVisualizer] 🎚️ Volumen del mod establecido a: " + (modVolume * 100) + "%");
        }
    }

    public static void listAvailableFiles() {
        try {
            if (!Files.exists(musicFolder)) {
                sendMessageSync("⚠️ Carpeta de música no encontrada: " + musicFolder, ChatFormatting.YELLOW);
                return;
            }
            sendMessageSync("📜 Archivos de música disponibles:", ChatFormatting.GOLD);
            List<Path> files = Files.list(musicFolder)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase();
                        return name.endsWith(".wav") || name.endsWith(".mp3") || name.endsWith(".ogg");
                    })
                    .collect(Collectors.toList());

            if (files.isEmpty()) {
                sendMessageSync("  • No se encontraron archivos de música.", ChatFormatting.YELLOW);
            } else {
                for (Path file : files) {
                    String filename = file.getFileName().toString();
                    long size = Files.size(file);
                    String sizeStr = formatFileSize(size);
                    sendMessageSync("  • " + filename + " (" + sizeStr + ")", ChatFormatting.GRAY);
                }
            }

            sendMessageSync("💡 Usa el comando /playmusic o configura regiones para reproducir música.", ChatFormatting.AQUA);
        } catch (Exception e) {
            System.err.println("[RegionVisualizer] ❌ Error listando archivos: " + e.getMessage());
            sendMessageSync("❌ Error listando archivos: " + e.getMessage(), ChatFormatting.RED);
            e.printStackTrace();
        }
    }

    private static void createHelpFile() {
        try {
            Path helpFile = musicFolder.resolve("README.txt");
            if (!Files.exists(helpFile)) {
                Files.writeString(helpFile,
                        "RegionVisualizer Music Folder\n" +
                        "----------------------------\n" +
                        "Place your .wav, .mp3, or .ogg audio files in this folder.\n" +
                        "Use the /playmusic command or region system to play them.\n" +
                        "Example: /region add my_region music.wav true true\n" +
                        "Supported formats: WAV, MP3, OGG\n" +
                        "Note: For best fade effects, use WAV or OGG (PCM, 44.1 kHz, 16 bits).\n" +
                        "Configuration: Edit config/regionvisualizer.properties to adjust settings:\n" +
                        "- modVolume: Current volume (0.0 to maxModVolume)\n" +
                        "- maxModVolume: Maximum volume (0.0 to 1.0)\n" +
                        "- fadeDuration: Fade-in/fade-out duration in seconds (minimum 0.1)\n" +
                        "- fadeInterval: Interval between volume steps in milliseconds (10 to 500)\n" +
                        "- fadeInStart: Starting volume for fade-in (0.0 to maxModVolume)");
                System.out.println("[RegionVisualizer] ✅ Archivo de ayuda creado: " + helpFile);
            }
        } catch (IOException e) {
            System.err.println("[RegionVisualizer] ❌ Error creando archivo de ayuda: " + e.getMessage());
            e.printStackTrace();
        }
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

    public static void shutdown() {
        synchronized (audioLock) {
            isPreviousPlaying.set(false); // Cancelar cualquier fade-out
            stopInternal(true);
            cleanupPreviousResources();
            initialized = false;
            System.out.println("[RegionVisualizer] 🔥 Sistema de música cerrado");
        }
    }

    public static void forceInitialize() {
        synchronized (audioLock) {
            isPreviousPlaying.set(false); // Cancelar cualquier fade-out
            stopInternal(true);
            cleanupPreviousResources();
            initialized = false;
            initialize();
            System.out.println("[RegionVisualizer] 🔥 Sistema de música reinicializado");
        }
    }

    public static void handleCommand(String command) {
        synchronized (audioLock) {
            try {
                System.out.println("[RegionVisualizer] 📦 Comando recibido: " + command);

                if (command.contains("a.wav")) {
                    System.err.println("[RegionVisualizer] ⚠️ Detectada reproducción de a.wav, comando: " + command);
                    new Exception("Rastreo de a.wav").printStackTrace();
                }

                if (command.startsWith("VOLUME:")) {
                    String volumeStr = command.substring(7);
                    float volume = Float.parseFloat(volumeStr);
                    setVolume(volume);
                    sendMessageSync("🎚️ Volumen del mod establecido: " + Math.round(volume * 100) + "%", ChatFormatting.AQUA);
                } else if (command.equals("GET_VOLUME")) {
                    float currentVol = getCurrentVolume();
                    sendMessageSync("🎚️ Volumen actual del mod: " + Math.round(currentVol * 100) + "%", ChatFormatting.AQUA);
                } else if (command.equals("CONFIG")) {
                    Minecraft.getInstance().execute(() -> {
                        Minecraft.getInstance().setScreen(new MusicConfigScreen(Minecraft.getInstance().screen));
                    });
                    System.out.println("[RegionVisualizer] 🔍 Abriendo MusicConfigScreen");
                } else if (command.startsWith("MUSIC:")) {
                    String[] parts = command.split(":", 4);
                    if (parts.length < 4) {
                        System.err.println("[RegionVisualizer] ❌ Comando de música inválido: " + command);
                        sendMessageSync("❌ Comando de música inválido: " + command, ChatFormatting.RED);
                        return;
                    }
                    String filename = parts[1];
                    boolean loop = Boolean.parseBoolean(parts[2]);
                    boolean fade = Boolean.parseBoolean(parts[3]);
                    play(filename, loop, fade);
                } else if (command.startsWith("STOP:")) {
                    boolean fade = Boolean.parseBoolean(command.substring(5));
                    stop(fade);
                } else {
                    handleNormalCommands(command);
                }
            } catch (NumberFormatException e) {
                System.err.println("[RegionVisualizer] ❌ Error parseando volumen: " + e.getMessage());
                sendMessageSync("❌ Volumen inválido", ChatFormatting.RED);
                e.printStackTrace();
            } catch (Exception e) {
                System.err.println("[RegionVisualizer] ❌ Error manejando comando: " + e.getMessage());
                sendMessageSync("❌ Error manejando comando: " + e.getMessage(), ChatFormatting.RED);
                e.printStackTrace();
            }
        }
    }

    private static void handleNormalCommands(String command) {
        switch (command.toUpperCase()) {
            case "STOP":
                stop(false);
                System.out.println("[RegionVisualizer] 🛑 Música detenida por comando del servidor");
                break;
            case "INIT":
                forceInitialize();
                listAvailableFiles();
                System.out.println("[RegionVisualizer] 🔥 Sistema de música reinicializado");
                break;
            case "LIST":
                listAvailableFiles();
                System.out.println("[RegionVisualizer] 🔍 Lista de música solicitada");
                break;
            case "LOGOUT":
                shutdown();
                System.out.println("[RegionVisualizer] 👋 Comando de desconexión procesado");
                break;
            case "SHUTDOWN":
                shutdown();
                System.out.println("[RegionVisualizer] 🛑 Comando de cierre procesado");
                break;
            default:
                if (!command.trim().isEmpty()) {
                    play(command, false, false);
                    System.out.println("[RegionVisualizer] 🎵 Reproduciendo: " + command);
                } else {
                    System.err.println("[RegionVisualizer] ⚠️ Comando vacío recibido");
                    sendMessageSync("❌ Comando vacío", ChatFormatting.RED);
                }
                break;
        }
    }

    public static void onPlayerLoggedOut() {
        synchronized (audioLock) {
            System.out.println("[RegionVisualizer] 👋 Jugador salió del mundo - limpiando sistema de música");
            if (Minecraft.getInstance().isSingleplayer()) {
                stopInternal(true);
            } else {
                stopInternal(false);
            }
            cleanupPreviousResources();
            shutdown();	
            System.out.println("[RegionVisualizer] ✅ Sistema de música limpiado tras salir del mundo");
        }
    }
}