package com.riburitu.regionvisualizer.client.sound;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.client.Minecraft;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.ArrayList;
import java.util.stream.Collectors;
import java.io.File;

public class MusicConfigScreen extends Screen {
    private static final int PANEL_WIDTH = 300;
    private static final int BUTTON_WIDTH = 150;
    private static final int BUTTON_HEIGHT = 20;
    private static final int SLIDER_WIDTH = 180;
    private static final int SPACING = 8;
    private static final int PADDING = 10;
    private static final int SMALL_BUTTON_HEIGHT = 18;

    private final Screen parent;
    private MusicFileList musicFileList;
    private SliderButton volumeSlider;
    private SliderButton fadeSlider;
    private Button testButton;
    private Button stopButton;
    private Button advancedButton;
    private boolean showAdvanced = false;

    // Variables para configuración avanzada
    private SliderButton maxVolumeSlider;
    private SliderButton fadeStartSlider;
    private Button cacheStatsButton;
    private Button preloadButton;
    private Button infoDetailButton;

    public MusicConfigScreen(Screen parent) {
        super(Component.literal("Configuración de Música - RegionVisualizer"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int leftPanelX = PADDING;
        int rightPanelX = leftPanelX + PANEL_WIDTH + PADDING * 2;
        int rightPanelWidth = this.width - rightPanelX - PADDING;
        
        // Panel izquierdo - Lista de archivos de música
        setupMusicFileList(leftPanelX);
        
        // Panel derecho - Controles
        setupControlPanel(rightPanelX, rightPanelWidth);
        
        // Botones inferiores
        setupBottomButtons();
        
        // Cargar archivos al inicializar
        loadMusicFiles();
    }

    private void setupMusicFileList(int x) {
        int listHeight = this.height - 100;
        int listY = 40;
        
        // Lista de archivos mejorada
        musicFileList = new MusicFileList(
            this.minecraft, 
            PANEL_WIDTH, 
            listHeight, 
            listY, 
            this.height - 60, // Ajustado para dar espacio a los botones
            22
        );
        musicFileList.setX(x);
        addWidget(musicFileList);
        
        // CORRECCIÓN: Botones de control de la lista - Mejor posicionamiento
        int buttonY = this.height - 55; // Más arriba para que no se salgan
        int buttonWidth = (PANEL_WIDTH - 10) / 3; // 3 botones con espaciado
        
        // Botón de refrescar
        Button refreshButton = Button.builder(
            Component.literal("🔄 Refresh"),
            button -> {
                System.out.println("[MusicConfig] Botón refresh presionado");
                loadMusicFiles();
                if (minecraft.player != null) {
                    minecraft.player.sendSystemMessage(
                        Component.literal("🔄 Lista actualizada").withStyle(ChatFormatting.GREEN)
                    );
                }
            }
        ).bounds(x, buttonY, buttonWidth, SMALL_BUTTON_HEIGHT).build();
        addRenderableWidget(refreshButton);
        
        // Botón de información
        Button infoButton = Button.builder(
            Component.literal("📁 Info"),
            button -> {
                if (minecraft.player != null) {
                    minecraft.player.sendSystemMessage(
                        Component.literal("📁 Carpeta: .minecraft/music/").withStyle(ChatFormatting.AQUA)
                    );
                    minecraft.player.sendSystemMessage(
                        Component.literal("💡 Formatos: .wav, .ogg").withStyle(ChatFormatting.YELLOW)
                    );
                }
            }
        ).bounds(x + buttonWidth + 5, buttonY, buttonWidth, SMALL_BUTTON_HEIGHT).build();
        addRenderableWidget(infoButton);
        
        // Botón de debug
        Button debugButton = Button.builder(
            Component.literal("🔍 Debug"),
            button -> {
                debugMusicPath();
                if (minecraft.player != null) {
                    minecraft.player.sendSystemMessage(
                        Component.literal("🔍 Debug ejecutado - revisar consola").withStyle(ChatFormatting.AQUA)
                    );
                }
            }
        ).bounds(x + buttonWidth * 2 + 10, buttonY, buttonWidth, SMALL_BUTTON_HEIGHT).build();
        addRenderableWidget(debugButton);
    }

    private void setupControlPanel(int x, int panelWidth) {
        int y = 50;
        int centerX = x + panelWidth / 2;

        // Slider de volumen del mod
        volumeSlider = SliderButton.forPercentage(
            centerX - SLIDER_WIDTH / 2,
            y,
            SLIDER_WIDTH,
            BUTTON_HEIGHT,
            "Volumen Mod",
            MusicManager.getCurrentVolume(),
            (slider, value) -> MusicManager.setVolume((float) value)
        );
        addRenderableWidget(volumeSlider);
        y += BUTTON_HEIGHT + SPACING;

        // Slider de duración de fade
        float currentFadeDuration = MusicManager.getFadeDuration();
        fadeSlider = SliderButton.forSeconds(
            centerX - SLIDER_WIDTH / 2,
            y,
            SLIDER_WIDTH,
            BUTTON_HEIGHT,
            "Fade Duration",
            currentFadeDuration,
            10.0,
            (slider, value) -> MusicManager.setFadeDuration((float) value)
        );
        addRenderableWidget(fadeSlider);
        y += BUTTON_HEIGHT + SPACING * 2;

        // Información del volumen de Minecraft
        float minecraftVolume = minecraft.options.getSoundSourceVolume(net.minecraft.sounds.SoundSource.MUSIC);
        Button mcVolumeInfo = Button.builder(
            Component.literal("MC Música: " + Math.round(minecraftVolume * 100) + "%").withStyle(ChatFormatting.GRAY),
            b -> {}
        ).bounds(
            centerX - BUTTON_WIDTH / 2,
            y,
            BUTTON_WIDTH,
            BUTTON_HEIGHT
        ).build();
        mcVolumeInfo.active = false;
        addRenderableWidget(mcVolumeInfo);
        y += BUTTON_HEIGHT + SPACING * 2;

        // Botones de control de música
        testButton = Button.builder(
            Component.literal("🎵 Probar Selección"),
            button -> testSelectedMusic()
        ).bounds(
            centerX - BUTTON_WIDTH / 2,
            y,
            BUTTON_WIDTH,
            BUTTON_HEIGHT
        ).build();
        addRenderableWidget(testButton);
        y += BUTTON_HEIGHT + SPACING / 2;

        stopButton = Button.builder(
            Component.literal("⏹ Parar Música"),
            button -> {
                MusicManager.stop(true);
                if (minecraft.player != null) {
                    minecraft.player.sendSystemMessage(
                        Component.literal("⏹ Música detenida").withStyle(ChatFormatting.YELLOW)
                    );
                }
            }
        ).bounds(
            centerX - BUTTON_WIDTH / 2,
            y,
            BUTTON_WIDTH,
            BUTTON_HEIGHT
        ).build();
        addRenderableWidget(stopButton);
        y += BUTTON_HEIGHT + SPACING;

        // Botón de configuración avanzada
        advancedButton = Button.builder(
            Component.literal(showAdvanced ? "🔽 Básico" : "🔼 Avanzado"),
            button -> toggleAdvancedMode()
        ).bounds(
            centerX - BUTTON_WIDTH / 2,
            y,
            BUTTON_WIDTH,
            BUTTON_HEIGHT
        ).build();
        addRenderableWidget(advancedButton);
        y += BUTTON_HEIGHT + SPACING;

        // Controles avanzados
        setupAdvancedControls(centerX, y);
    }

    private void setupAdvancedControls(int centerX, int y) {
        // CORRECCIÓN: Inicializar controles avanzados pero ocultos por defecto
        
        // Slider de volumen máximo
        maxVolumeSlider = SliderButton.forRange(
            centerX - SLIDER_WIDTH / 2,
            y,
            SLIDER_WIDTH,
            BUTTON_HEIGHT,
            "Vol. Máximo",
            MusicManager.getMaxModVolume(),
            0.1, 1.0,
            (slider, value) -> MusicManager.setMaxModVolume((float) value)
        );
        maxVolumeSlider.visible = false; // Oculto inicialmente
        addRenderableWidget(maxVolumeSlider);
        y += BUTTON_HEIGHT + SPACING;

        // Slider de fade inicial
        fadeStartSlider = SliderButton.forPercentage(
            centerX - SLIDER_WIDTH / 2,
            y,
            SLIDER_WIDTH,
            BUTTON_HEIGHT,
            "Fade Inicial",
            MusicManager.getFadeInStart(),
            (slider, value) -> MusicManager.setFadeInStart((float) value)
        );
        fadeStartSlider.visible = false; // Oculto inicialmente
        addRenderableWidget(fadeStartSlider);
        y += BUTTON_HEIGHT + SPACING;

        // Botón de información detallada
        infoDetailButton = Button.builder(
            Component.literal("ℹ️ Info Detallada"),
            button -> showDetailedFileInfo()
        ).bounds(
            centerX - BUTTON_WIDTH / 2,
            y,
            BUTTON_WIDTH,
            BUTTON_HEIGHT
        ).build();
        infoDetailButton.visible = false; // Oculto inicialmente
        addRenderableWidget(infoDetailButton);
        y += BUTTON_HEIGHT + SPACING / 2;

        // Botones de cache
        cacheStatsButton = Button.builder(
            Component.literal("📊 Cache Stats"),
            button -> {
                MusicManager.printCacheStats();
                if (minecraft.player != null) {
                    minecraft.player.sendSystemMessage(
                        Component.literal("📊 Estadísticas en consola").withStyle(ChatFormatting.GREEN)
                    );
                }
            }
        ).bounds(
            centerX - BUTTON_WIDTH / 2,
            y,
            BUTTON_WIDTH,
            BUTTON_HEIGHT
        ).build();
        cacheStatsButton.visible = false; // Oculto inicialmente
        addRenderableWidget(cacheStatsButton);
        y += BUTTON_HEIGHT + SPACING / 2;

        preloadButton = Button.builder(
            Component.literal("🚀 Precargar Todo"),
            button -> {
                MusicManager.forcePreload();
                if (minecraft.player != null) {
                    minecraft.player.sendSystemMessage(
                        Component.literal("🚀 Precarga iniciada").withStyle(ChatFormatting.YELLOW)
                    );
                }
            }
        ).bounds(
            centerX - BUTTON_WIDTH / 2,
            y,
            BUTTON_WIDTH,
            BUTTON_HEIGHT
        ).build();
        preloadButton.visible = false; // Oculto inicialmente
        addRenderableWidget(preloadButton);
    }

    private void setupBottomButtons() {
        // Botón de volver
        addRenderableWidget(Button.builder(
            Component.literal("← Volver"),
            button -> {
                // Parar música al salir del menú
                MusicManager.stop(false);
                this.minecraft.setScreen(parent);
            }
        ).bounds(
            this.width / 2 - BUTTON_WIDTH - 5,
            this.height - 30,
            BUTTON_WIDTH,
            BUTTON_HEIGHT
        ).build());

        // Botón de reinicializar
        addRenderableWidget(Button.builder(
            Component.literal("🔄 Reinicializar"),
            button -> {
                System.out.println("[MusicConfig] Reinicializando sistema...");
                MusicManager.forceInitialize();
                loadMusicFiles();
                if (minecraft.player != null) {
                    minecraft.player.sendSystemMessage(
                        Component.literal("🔄 Sistema reinicializado").withStyle(ChatFormatting.GREEN)
                    );
                }
            }
        ).bounds(
            this.width / 2 + 5,
            this.height - 30,
            BUTTON_WIDTH,
            BUTTON_HEIGHT
        ).build());
    }

    private void toggleAdvancedMode() {
        showAdvanced = !showAdvanced;
        
        // CORRECCIÓN: Actualizar visibilidad de controles avanzados correctamente
        if (maxVolumeSlider != null) {
            maxVolumeSlider.visible = showAdvanced;
        }
        if (fadeStartSlider != null) {
            fadeStartSlider.visible = showAdvanced;
        }
        if (infoDetailButton != null) {
            infoDetailButton.visible = showAdvanced;
        }
        if (cacheStatsButton != null) {
            cacheStatsButton.visible = showAdvanced;
        }
        if (preloadButton != null) {
            preloadButton.visible = showAdvanced;
        }
        
        // Actualizar texto del botón
        if (advancedButton != null) {
            advancedButton.setMessage(Component.literal(showAdvanced ? "🔽 Básico" : "🔼 Avanzado"));
        }
        
        System.out.println("[MusicConfig] Modo avanzado: " + (showAdvanced ? "ACTIVADO" : "DESACTIVADO"));
    }

    private void loadMusicFiles() {
        System.out.println("[MusicConfig] === INICIANDO CARGA DE ARCHIVOS ===");
        
        // Limpiar lista actual
        if (musicFileList != null) {
            try {
                musicFileList.clearMusicFiles();
                System.out.println("[MusicConfig] Lista limpiada");
            } catch (Exception e) {
                System.err.println("[MusicConfig] Error limpiando lista: " + e.getMessage());
                recreateMusicFileList();
            }
        }
        
        try {
            // Verificar que MusicManager esté inicializado
            if (!MusicManager.isSystemHealthy()) {
                System.out.println("[MusicConfig] Sistema no saludable, inicializando...");
                MusicManager.forceInitialize();
                Thread.sleep(200); // Esperar inicialización
            }
            
            // Obtener la carpeta de música
            File gameDir = Minecraft.getInstance().gameDirectory;
            Path musicFolder = Paths.get(gameDir.getAbsolutePath(), "music");
            
            System.out.println("[MusicConfig] Carpeta de música: " + musicFolder.toAbsolutePath());
            System.out.println("[MusicConfig] Carpeta existe: " + Files.exists(musicFolder));
            
            if (!Files.exists(musicFolder)) {
                System.out.println("[MusicConfig] Creando carpeta de música...");
                Files.createDirectories(musicFolder);
                
                musicFileList.addMusicFile(
                    "📁 Carpeta creada", 
                    "Coloca archivos .wav/.ogg aquí", 
                    false, 
                    "info"
                );
                return;
            }
            
            // Lógica de listado mejorada
            List<Path> allFiles = new ArrayList<>();
            List<Path> audioFiles = new ArrayList<>();
            
            System.out.println("[MusicConfig] === LISTANDO CONTENIDO DE LA CARPETA ===");
            
            try {
                Files.list(musicFolder).forEach(path -> {
                    try {
                        String name = path.getFileName().toString();
                        boolean isFile = Files.isRegularFile(path);
                        long size = isFile ? Files.size(path) : 0;
                        
                        System.out.println("[MusicConfig] Encontrado: " + name + 
                            " (file: " + isFile + ", size: " + size + ")");
                        
                        allFiles.add(path);
                        
                        // Verificar si es archivo de audio
                        if (isFile) {
                            String lowerName = name.toLowerCase();
                            if (lowerName.endsWith(".wav") || lowerName.endsWith(".ogg")) {
                                System.out.println("[MusicConfig] ✅ Es archivo de audio: " + name);
                                audioFiles.add(path);
                            } else {
                                System.out.println("[MusicConfig] ❌ No es archivo de audio: " + name);
                            }
                        }
                        
                    } catch (Exception e) {
                        System.err.println("[MusicConfig] Error procesando archivo: " + path.getFileName() + " - " + e.getMessage());
                    }
                });
                
            } catch (Exception e) {
                System.err.println("[MusicConfig] ERROR crítico listando carpeta: " + e.getMessage());
                e.printStackTrace();
                
                musicFileList.addMusicFile(
                    "❌ Error accediendo carpeta", 
                    e.getMessage(), 
                    false, 
                    "error"
                );
                return;
            }
            
            System.out.println("[MusicConfig] === RESUMEN ===");
            System.out.println("[MusicConfig] Total archivos encontrados: " + allFiles.size());
            System.out.println("[MusicConfig] Archivos de audio: " + audioFiles.size());
            
            if (audioFiles.isEmpty()) {
                musicFileList.addMusicFile(
                    "📂 Sin archivos de música", 
                    "Se encontraron " + allFiles.size() + " archivos, pero ninguno es .wav/.ogg", 
                    false, 
                    "empty"
                );
                
                musicFileList.addMusicFile(
                    "💡 Ayuda", 
                    "Coloca archivos .wav o .ogg en la carpeta music", 
                    false, 
                    "info"
                );
            } else {
                // Ordenar archivos por nombre
                audioFiles.sort((p1, p2) -> 
                    p1.getFileName().toString().compareToIgnoreCase(p2.getFileName().toString()));
                
                // Procesar cada archivo de audio
                for (Path file : audioFiles) {
                    String filename = file.getFileName().toString();
                    System.out.println("[MusicConfig] Procesando: " + filename);
                    
                    try {
                        // Obtener información básica
                        long size = Files.size(file);
                        String extension = getFileExtension(filename).toUpperCase();
                        String sizeStr = formatFileSize(size);
                        
                        // Verificar compatibilidad usando testFile
                        boolean supported = false;
                        try {
                            supported = MusicManager.testFile(filename);
                            System.out.println("[MusicConfig] " + filename + " compatible: " + supported);
                        } catch (Exception e) {
                            System.err.println("[MusicConfig] Error testando " + filename + ": " + e.getMessage());
                        }
                        
                        String info = extension + " • " + sizeStr + (supported ? " • ✅" : " • ❌");
                        
                        musicFileList.addMusicFile(filename, info, supported, extension.toLowerCase());
                        
                    } catch (Exception e) {
                        System.err.println("[MusicConfig] Error procesando " + filename + ": " + e.getMessage());
                        musicFileList.addMusicFile(
                            filename, 
                            "Error: " + e.getMessage(), 
                            false, 
                            "error"
                        );
                    }
                }
                
                System.out.println("[MusicConfig] ✅ Carga completada: " + audioFiles.size() + " archivos añadidos a la lista");
            }
            
        } catch (Exception e) {
            System.err.println("[MusicConfig] ERROR CRÍTICO en loadMusicFiles: " + e.getMessage());
            e.printStackTrace();
            
            if (musicFileList != null) {
                musicFileList.addMusicFile(
                    "❌ Error crítico", 
                    "Ver consola para detalles", 
                    false, 
                    "error"
                );
            }
        }
    }

    private void debugMusicPath() {
        System.out.println("=== DEBUG MUSIC PATH ===");
        
        // Información básica
        File gameDir = Minecraft.getInstance().gameDirectory;
        System.out.println("Game Directory: " + gameDir.getAbsolutePath());
        System.out.println("Game Directory exists: " + gameDir.exists());
        
        Path musicFolder = Paths.get(gameDir.getAbsolutePath(), "music");
        System.out.println("Music Folder Path: " + musicFolder.toAbsolutePath());
        System.out.println("Music Folder exists: " + Files.exists(musicFolder));
        
        if (Files.exists(musicFolder)) {
            try {
                System.out.println("=== CONTENIDO DETALLADO ===");
                
                Files.list(musicFolder).forEach(file -> {
                    try {
                        String name = file.getFileName().toString();
                        long size = Files.size(file);
                        boolean isFile = Files.isRegularFile(file);
                        boolean isDirectory = Files.isDirectory(file);
                        boolean canRead = Files.isReadable(file);
                        
                        System.out.println("📄 " + name);
                        System.out.println("   Size: " + size + " bytes (" + formatFileSize(size) + ")");
                        System.out.println("   Type: file=" + isFile + ", dir=" + isDirectory);
                        System.out.println("   Readable: " + canRead);
                        
                        if (isFile) {
                            String ext = getFileExtension(name).toLowerCase();
                            boolean isAudio = ext.equals(".wav") || ext.equals(".ogg");
                            System.out.println("   Extension: " + ext + " (audio: " + isAudio + ")");
                            
                            if (isAudio) {
                                try {
                                    boolean testResult = MusicManager.testFile(name);
                                    System.out.println("   Test result: " + testResult);
                                } catch (Exception e) {
                                    System.out.println("   Test error: " + e.getMessage());
                                }
                            }
                        }
                        System.out.println();
                        
                    } catch (Exception e) {
                        System.err.println("   Error: " + e.getMessage());
                    }
                });
                
            } catch (Exception e) {
                System.err.println("Error listando carpeta: " + e.getMessage());
                e.printStackTrace();
            }
        }
        
        System.out.println("=== ESTADO MUSICMANAGER ===");
        System.out.println("System healthy: " + MusicManager.isSystemHealthy());
        
        System.out.println("=== FIN DEBUG ===");
    }

    private void recreateMusicFileList() {
        int x = PADDING;
        int listHeight = this.height - 100;
        int listY = 40;
        
        musicFileList = new MusicFileList(
            this.minecraft, 
            PANEL_WIDTH, 
            listHeight, 
            listY, 
            this.height - 60, // Ajustado
            22
        );
        musicFileList.setX(x);
        this.addWidget(musicFileList);
        
        System.out.println("[MusicConfig] Lista de música recreada");
    }

    private void testSelectedMusic() {
        MusicFileList.Entry selected = musicFileList.getSelected();
        
        if (selected == null) {
            if (minecraft.player != null) {
                minecraft.player.sendSystemMessage(
                    Component.literal("⚠️ Selecciona un archivo de la lista").withStyle(ChatFormatting.YELLOW)
                );
            }
            return;
        }
        
        if (!selected.canPlay) {
            if (minecraft.player != null) {
                minecraft.player.sendSystemMessage(
                    Component.literal("⚠️ El archivo seleccionado no es compatible").withStyle(ChatFormatting.RED)
                );
            }
            return;
        }
        
        String filename = selected.name;
        
        try {
            System.out.println("[MusicConfig] Reproduciendo: " + filename);
            
            // Reproducir con fade pero sin bucle
            MusicManager.play(filename, false, true);
            
            if (minecraft.player != null) {
                minecraft.player.sendSystemMessage(
                    Component.literal("🎵 Reproduciendo: " + filename).withStyle(ChatFormatting.GREEN)
                );
            }
            
        } catch (Exception e) {
            System.err.println("[MusicConfig] Error reproduciendo " + filename + ": " + e.getMessage());
            
            if (minecraft.player != null) {
                minecraft.player.sendSystemMessage(
                    Component.literal("❌ Error: " + e.getMessage()).withStyle(ChatFormatting.RED)
                );
            }
        }
    }

    private void showDetailedFileInfo() {
        MusicFileList.Entry selected = musicFileList.getSelected();
        
        if (selected == null) {
            if (minecraft.player != null) {
                minecraft.player.sendSystemMessage(
                    Component.literal("⚠️ Selecciona un archivo primero").withStyle(ChatFormatting.YELLOW)
                );
            }
            return;
        }
        
        String filename = selected.name;
        
        try {
            String detailedInfo = MusicManager.getFileInfo(filename);
            System.out.println("[MusicConfig] === INFO DETALLADA: " + filename + " ===");
            System.out.println(detailedInfo);
            System.out.println("=== FIN INFO ===");
            
            if (minecraft.player != null) {
                minecraft.player.sendSystemMessage(
                    Component.literal("ℹ️ Info detallada de '" + filename + "' en consola").withStyle(ChatFormatting.AQUA)
                );
            }
            
        } catch (Exception e) {
            System.err.println("[MusicConfig] Error obteniendo info de " + filename + ": " + e.getMessage());
            
            if (minecraft.player != null) {
                minecraft.player.sendSystemMessage(
                    Component.literal("❌ Error: " + e.getMessage()).withStyle(ChatFormatting.RED)
                );
            }
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        this.renderBackground(guiGraphics);
        
        // Título principal
        guiGraphics.drawCenteredString(
            this.font,
            this.title,
            this.width / 2,
            15,
            0xFFFFFF
        );
        
        // Título del panel de archivos
        guiGraphics.drawString(
            this.font,
            Component.literal("Archivos Disponibles").withStyle(ChatFormatting.GOLD),
            PADDING,
            25,
            ChatFormatting.GOLD.getColor()
        );
        
        // Título del panel de controles
        int rightPanelX = PADDING + PANEL_WIDTH + PADDING * 2;
        guiGraphics.drawString(
            this.font,
            Component.literal("Configuración").withStyle(ChatFormatting.AQUA),
            rightPanelX,
            25,
            ChatFormatting.AQUA.getColor()
        );
        
        super.render(guiGraphics, mouseX, mouseY, partialTicks);
        
        // Información adicional
        if (showAdvanced) {
            guiGraphics.drawCenteredString(
                this.font,
                Component.literal("Modo Avanzado").withStyle(ChatFormatting.GRAY),
                this.width / 2,
                this.height - 50,
                ChatFormatting.GRAY.getColor()
            );
        }
    }

    @Override
    public void onClose() {
        // Parar música al cerrar el menú
        MusicManager.stop(false);
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // Utilidades
    private String getFileExtension(String filename) {
        int lastDotIndex = filename.lastIndexOf('.');
        return lastDotIndex > 0 ? filename.substring(lastDotIndex) : "";
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    // Clase para la lista de archivos de música
    public static class MusicFileList extends ObjectSelectionList<MusicFileList.Entry> {
        
        public MusicFileList(Minecraft minecraft, int width, int height, int y0, int y1, int itemHeight) {
            super(minecraft, width, height, y0, y1, itemHeight);
            this.setRenderBackground(true);
            this.setRenderTopAndBottom(true);
        }
        
        public void setX(int x) {
            this.x0 = x;
            this.x1 = x + this.width;
        }

        public void addMusicFile(String name, String info, boolean canPlay, String type) {
            this.addEntry(new Entry(name, info, canPlay, type));
        }

        public void clearMusicFiles() {
            this.children().clear();
            this.setSelected(null);
        }

        @Override
        public int getRowWidth() {
            return this.width - 10;
        }

        @Override
        protected int getScrollbarPosition() {
            return this.x1 - 6;
        }

        public static class Entry extends ObjectSelectionList.Entry<Entry> {
            public final String name;
            private final String info;
            public final boolean canPlay;
            private final String type;
            private final Minecraft minecraft;

            public Entry(String name, String info, boolean canPlay, String type) {
                this.name = name;
                this.info = info;
                this.canPlay = canPlay;
                this.type = type;
                this.minecraft = Minecraft.getInstance();
            }

            @Override
            public Component getNarration() {
                return Component.literal(name + " " + info);
            }

            @Override
            public void render(GuiGraphics guiGraphics, int index, int y, int x, int entryWidth, int entryHeight, 
                             int mouseX, int mouseY, boolean isMouseOver, float partialTicks) {
                
                // Fondo de selección
                if (isMouseOver && canPlay) {
                    guiGraphics.fill(x, y, x + entryWidth, y + entryHeight, 0x80FFFFFF);
                } else if (isMouseOver) {
                    guiGraphics.fill(x, y, x + entryWidth, y + entryHeight, 0x40FF0000);
                }
                
                // Color y emoji según tipo
                int nameColor;
                String prefix;
                
                switch (type) {
                    case "wav":
                        nameColor = 0xFFFFFF;
                        prefix = "🎵 ";
                        break;
                    case "ogg":
                        nameColor = 0xFFFFFF;
                        prefix = "🎶 ";
                        break;
                    case "info":
                        nameColor = 0xFFAA00;
                        prefix = "💡 ";
                        break;
                    case "empty":
                        nameColor = 0xFFAA00;
                        prefix = "📂 ";
                        break;
                    case "error":
                        nameColor = 0xFF5555;
                        prefix = "❌ ";
                        break;
                    default:
                        nameColor = 0xAAAAA;
                        prefix = "⚠️ ";
                        break;
                }
                
                // Nombre del archivo con emoji
                guiGraphics.drawString(minecraft.font, prefix + name, x + 5, y + 2, nameColor);
                
                // Información adicional
                if (!info.isEmpty()) {
                    int infoColor = canPlay ? 0xAAAAAA : 0xFF5555;
                    guiGraphics.drawString(minecraft.font, info, x + 5, y + 13, infoColor);
                }
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                if (canPlay) {
                    return true; // Solo permitir selección de archivos reproducibles
                }
                return false;
            }
        }
    }
}