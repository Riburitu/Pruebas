package com.riburitu.regionvisualizer.client.sound;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

public class SliderButton extends AbstractSliderButton {
    private final OnValueChange onValueChange;

    @FunctionalInterface
    public interface OnValueChange {
        void onChange(SliderButton slider, double value);
    }

    public SliderButton(int x, int y, int width, int height, Component message, double value, OnValueChange onValueChange) {
        super(x, y, width, height, message, value);
        this.onValueChange = onValueChange;
    }

    @Override
    protected void updateMessage() {
        // Solo actualiza, no hace nada.
    }

    @Override
    protected void applyValue() {
        onValueChange.onChange(this, this.value);
    }
}