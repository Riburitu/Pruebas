package com.riburitu.regionvisualizer.client.sound;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class MusicConfigScreen extends Screen {
    private static final int SLIDER_WIDTH = 200;
    private static final int BUTTON_WIDTH = 100;
    private static final int BUTTON_HEIGHT = 20;
    private static final int SPACING = 10;

    private final Screen parent;

    public MusicConfigScreen(Screen parent) {
        super(Component.literal("Configuración de Música - RegionVisualizer"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        // Slider para el volumen del mod
        addRenderableWidget(new SliderButton(
            centerX - SLIDER_WIDTH / 2,
            centerY - BUTTON_HEIGHT - SPACING,
            SLIDER_WIDTH,
            BUTTON_HEIGHT,
            Component.literal("Volumen del Mod: " + Math.round(MusicManager.getCurrentVolume() * 100) + "%"),
            MusicManager.getCurrentVolume(),
            (slider, value) -> {
                MusicManager.setVolume((float) value);
                slider.setMessage(Component.literal("Volumen del Mod: " + Math.round(value * 100) + "%"));
            }
        ));

        // Mostrar volumen de Minecraft (solo informativo)
        float minecraftVolume = minecraft.options.getSoundSourceVolume(net.minecraft.sounds.SoundSource.MUSIC);
        Button minecraftVolumeButton = Button.builder(
            Component.literal("Volumen de Minecraft: " + Math.round(minecraftVolume * 100) + "%").withStyle(ChatFormatting.GRAY),
            b -> {}
        ).bounds(
            centerX - SLIDER_WIDTH / 2,
            centerY - BUTTON_HEIGHT + SPACING,
            SLIDER_WIDTH,
            BUTTON_HEIGHT
        ).build();
        minecraftVolumeButton.active = false;
        addRenderableWidget(minecraftVolumeButton);

        // Botón de volver
        addRenderableWidget(Button.builder(
            Component.literal("Volver"),
            button -> this.minecraft.setScreen(parent)
        ).bounds(
            centerX - BUTTON_WIDTH / 2,
            centerY + BUTTON_HEIGHT + 2 * SPACING,
            BUTTON_WIDTH,
            BUTTON_HEIGHT
        ).build());
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        this.renderBackground(guiGraphics);
        super.render(guiGraphics, mouseX, mouseY, partialTicks);

        // Título
        guiGraphics.drawCenteredString(
            this.font,
            this.title,
            this.width / 2,
            20,
            0xFFFFFF
        );
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}