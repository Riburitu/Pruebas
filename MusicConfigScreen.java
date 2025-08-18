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

public class MusicConfigScreen extends Screen {
    private static final int PANEL_WIDTH = 200;
    private static final int BUTTON_WIDTH = 150;
    private static final int BUTTON_HEIGHT = 20;
    private static final int SLIDER_WIDTH = 180;
    private static final int SPACING = 8;
    private static final int PADDING = 10;

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
    }

    private void setupMusicFileList(int x) {
        int listHeight = this.height - 100;
        
        // Título de la lista
        Component listTitle = Component.literal("Archivos de Música").withStyle(ChatFormatting.GOLD);
        
        // Lista de archivos
        musicFileList = new MusicFileList(this.minecraft, PANEL_WIDTH, listHeight, 40, this.height - 50, 22);
        musicFileList.setX(x);
        addWidget(musicFileList);
        
        // Cargar archivos de música
        loadMusicFiles();
    }

    private void setupControlPanel(int x, int panelWidth) {
        int y = 50;
        int centerX = x + panelWidth / 2;

        // Slider de volumen del mod (usando constructor mejorado)
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

        // Slider de duración de fade (0-10 segundos)
        float currentFadeDuration = MusicManager.getFadeDuration();
        fadeSlider = SliderButton.forSeconds(
            centerX - SLIDER_WIDTH / 2,
            y,
            SLIDER_WIDTH,
            BUTTON_HEIGHT,
            "Fade Duration",
            currentFadeDuration,
            10.0, // máximo 10 segundos
            (slider, value) -> MusicManager.setFadeDuration((float) value)
        );
        addRenderableWidget(fadeSlider);
        y += BUTTON_HEIGHT + SPACING * 2;

        // Información del volumen de Minecraft (solo lectura)
        float minecraftVolume = minecraft.options.getSoundSourceVolume(net.minecraft.sounds.SoundSource.MUSIC);
        Button infoButton = Button.builder(
            Component.literal("MC Música: " + Math.round(minecraftVolume * 100) + "%").withStyle(ChatFormatting.GRAY),
            b -> {}
        ).bounds(
            centerX - BUTTON_WIDTH / 2,
            y,
            BUTTON_WIDTH,
            BUTTON_HEIGHT
        ).build();
        infoButton.active = false;
        addRenderableWidget(infoButton);
        y += BUTTON_HEIGHT + SPACING * 2;

        // Botones de control
        testButton = Button.builder(
            Component.literal("🎵 Probar"),
            button -> testSelectedMusic()
        ).bounds(
            centerX - BUTTON_WIDTH / 2,
            y,
            BUTTON_WIDTH / 2 - 2,
            BUTTON_HEIGHT
        ).build();
        addRenderableWidget(testButton);

        stopButton = Button.builder(
            Component.literal("⏹ Parar"),
            button -> MusicManager.stop(true)
        ).bounds(
            centerX + 2,
            y,
            BUTTON_WIDTH / 2 - 2,
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

        // Controles avanzados (inicialmente ocultos)
        setupAdvancedControls(centerX, y);
    }

    private void setupAdvancedControls(int centerX, int y) {
        // Slider de volumen máximo
        maxVolumeSlider = SliderButton.forRange(
            centerX - SLIDER_WIDTH / 2,
            y,
            SLIDER_WIDTH,
            BUTTON_HEIGHT,
            "Vol. Máximo",
            MusicManager.getMaxModVolume(),
            0.1, 1.0, // rango 10% - 100%
            (slider, value) -> MusicManager.setMaxModVolume((float) value)
        );
        maxVolumeSlider.visible = showAdvanced;
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
        fadeStartSlider.visible = showAdvanced;
        addRenderableWidget(fadeStartSlider);
        y += BUTTON_HEIGHT + SPACING;

        // Botones avanzados
        cacheStatsButton = Button.builder(
            Component.literal("📊 Cache Stats"),
            button -> {
                MusicManager.printCacheStats();
                // Mostrar notificación al jugador
                if (minecraft.player != null) {
                    minecraft.player.sendSystemMessage(
                        Component.literal("📊 Estadísticas mostradas en consola").withStyle(ChatFormatting.GREEN)
                    );
                }
            }
        ).bounds(
            centerX - BUTTON_WIDTH / 2,
            y,
            BUTTON_WIDTH,
            BUTTON_HEIGHT
        ).build();
        cacheStatsButton.visible = showAdvanced;
        addRenderableWidget(cacheStatsButton);
        y += BUTTON_HEIGHT + SPACING / 2;

        preloadButton = Button.builder(
            Component.literal("🚀 Precargar Todo"),
            button -> {
                MusicManager.forcePreload();
                if (minecraft.player != null) {
                    minecraft.player.sendSystemMessage(
                        Component.literal("🚀 Precarga iniciada en segundo plano").withStyle(ChatFormatting.YELLOW)
                    );
                }
            }
        ).bounds(
            centerX - BUTTON_WIDTH / 2,
            y,
            BUTTON_WIDTH,
            BUTTON_HEIGHT
        ).build();
        preloadButton.visible = showAdvanced;
        addRenderableWidget(preloadButton);
    }

    private void setupBottomButtons() {
        // Botón de volver
        addRenderableWidget(Button.builder(
            Component.literal("← Volver"),
            button -> this.minecraft.setScreen(parent)
        ).bounds(
            this.width / 2 - BUTTON_WIDTH / 2,
            this.height - 30,
            BUTTON_WIDTH,
            BUTTON_HEIGHT
        ).build());

        // Botón de recarga/reinit
        addRenderableWidget(Button.builder(
            Component.literal("🔄 Recargar"),
            button -> {
                MusicManager.forceInitialize();
                loadMusicFiles(); // Recargar la lista
                if (minecraft.player != null) {
                    minecraft.player.sendSystemMessage(
                        Component.literal("🔄 Sistema de música reinicializado").withStyle(ChatFormatting.GREEN)
                    );
                }
            }
        ).bounds(
            this.width / 2 + BUTTON_WIDTH / 2 + SPACING,
            this.height - 30,
            BUTTON_WIDTH,
            BUTTON_HEIGHT
        ).build());
    }

    private void toggleAdvancedMode() {
        showAdvanced = !showAdvanced;
        
        // Actualizar visibilidad de controles avanzados
        maxVolumeSlider.visible = showAdvanced;
        fadeStartSlider.visible = showAdvanced;
        cacheStatsButton.visible = showAdvanced;
        preloadButton.visible = showAdvanced;
        
        // Actualizar texto del botón
        advancedButton.setMessage(Component.literal(showAdvanced ? "🔽 Básico" : "🔼 Avanzado"));
    }

    private void loadMusicFiles() {
        // Método alternativo: recrear la lista si hay problemas con clearMusicFiles
        try {
            musicFileList.clearMusicFiles();
        } catch (Exception e) {
            // Si hay problemas, recrear la lista completamente
            int x = musicFileList.getLeft();
            musicFileList = new MusicFileList(this.minecraft, PANEL_WIDTH, this.height - 100, 40, this.height - 50, 22);
            musicFileList.setX(x);
            this.addWidget(musicFileList);
        }
        
        try {
            Path musicFolder = Paths.get(Minecraft.getInstance().gameDirectory.getAbsolutePath(), "music");
            
            if (!Files.exists(musicFolder)) {
                musicFileList.addMusicFile("⌐ Carpeta 'music' no encontrada", "", false);
                return;
            }
            
            List<Path> musicFiles = Files.list(musicFolder)
                .filter(path -> {
                    String name = path.getFileName().toString().toLowerCase();
                    return name.endsWith(".wav") || name.endsWith(".ogg");
                })
                .sorted()
                .collect(Collectors.toList());
            
            if (musicFiles.isEmpty()) {
                musicFileList.addMusicFile("ℹ️ No hay archivos de música", "Coloca archivos .wav o .ogg en la carpeta 'music'", false);
            } else {
                for (Path file : musicFiles) {
                    String filename = file.getFileName().toString();
                    String extension = getFileExtension(filename).toUpperCase();
                    long size = Files.size(file);
                    String sizeStr = formatFileSize(size);
                    
                    boolean supported = extension.equals("WAV") || extension.equals("OGG");
                    String displayName = (supported ? "🎵 " : "⚠️ ") + filename;
                    String info = extension + " • " + sizeStr;
                    
                    musicFileList.addMusicFile(displayName, info, supported);
                }
            }
        } catch (Exception e) {
            System.err.println("[MusicConfig] Error cargando archivos: " + e.getMessage());
            musicFileList.addMusicFile("⌐ Error cargando archivos", e.getMessage(), false);
        }
    }

    private void testSelectedMusic() {
        MusicFileList.Entry selected = musicFileList.getSelected();
        if (selected != null && selected.canPlay) {
            String filename = extractFilename(selected.name);
            if (filename != null) {
                MusicManager.play(filename, false, true); // No loop, con fade
                
                if (minecraft.player != null) {
                    minecraft.player.sendSystemMessage(
                        Component.literal("🎵 Reproduciendo: " + filename).withStyle(ChatFormatting.GREEN)
                    );
                }
            }
        } else {
            if (minecraft.player != null) {
                minecraft.player.sendSystemMessage(
                    Component.literal("⚠️ Selecciona un archivo de música válido").withStyle(ChatFormatting.YELLOW)
                );
            }
        }
    }

    private String extractFilename(String displayName) {
        // Remover emojis y espacios al inicio
        String cleaned = displayName.replaceAll("^[🎵⚠️⌐ℹ️]+\\s*", "");
        return cleaned.isEmpty() ? null : cleaned;
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
        
        // Información adicional en la parte inferior
        if (showAdvanced) {
            guiGraphics.drawCenteredString(
                this.font,
                Component.literal("Modo Avanzado - Para usuarios experimentados").withStyle(ChatFormatting.GRAY),
                this.width / 2,
                this.height - 50,
                ChatFormatting.GRAY.getColor()
            );
        }
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
        }
        
        // Método para establecer la posición X
        public void setX(int x) {
            this.x0 = x;
            this.x1 = x + this.width;
        }

        // Método público para agregar archivos de música
        public void addMusicFile(String name, String info, boolean canPlay) {
            this.addEntry(new Entry(name, info, canPlay));
        }

        // Método público para limpiar la lista - Simplificado
        public void clearMusicFiles() {
            // Limpiar directamente la lista interna usando children()
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
            private final String name;
            private final String info;
            public final boolean canPlay;
            private final Minecraft minecraft;

            public Entry(String name, String info, boolean canPlay) {
                this.name = name;
                this.info = info;
                this.canPlay = canPlay;
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
                if (isMouseOver) {
                    guiGraphics.fill(x, y, x + entryWidth, y + entryHeight, 0x80FFFFFF);
                }
                
                // Nombre del archivo
                int nameColor = canPlay ? 0xFFFFFF : 0xFFAA00;
                guiGraphics.drawString(minecraft.font, name, x + 5, y + 2, nameColor);
                
                // Información adicional (tamaño, formato)
                if (!info.isEmpty()) {
                    int infoColor = 0xAAAAAA;
                    guiGraphics.drawString(minecraft.font, info, x + 5, y + 13, infoColor);
                }
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                return true; // Permitir selección
            }
        }
    }
}