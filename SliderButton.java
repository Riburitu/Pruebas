package com.riburitu.regionvisualizer.client.sound;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

public class SliderButton extends AbstractSliderButton {
    private final OnValueChange onValueChange;
    private final float minValue;
    private final float maxValue;
    private final boolean showPercentage;
    private final String prefix;
    private final String suffix;

    @FunctionalInterface
    public interface OnValueChange {
        void onChange(SliderButton slider, double value);
    }

    // Constructor simple (compatibilidad con código existente)
    public SliderButton(int x, int y, int width, int height, Component message, double value, OnValueChange onValueChange) {
        this(x, y, width, height, message, value, onValueChange, 0.0f, 1.0f, true, "", "");
    }

    // Constructor completo para mayor control
    public SliderButton(int x, int y, int width, int height, Component message, double value, 
                       OnValueChange onValueChange, float minValue, float maxValue, 
                       boolean showPercentage, String prefix, String suffix) {
        super(x, y, width, height, message, Mth.clamp((value - minValue) / (maxValue - minValue), 0.0, 1.0));
        this.onValueChange = onValueChange;
        this.minValue = minValue;
        this.maxValue = maxValue;
        this.showPercentage = showPercentage;
        this.prefix = prefix;
        this.suffix = suffix;
        
        updateMessage();
    }

    // Constructores específicos para diferentes tipos de valores
    public static SliderButton forPercentage(int x, int y, int width, int height, String label, 
                                           double value, OnValueChange onValueChange) {
        Component message = Component.literal(label + ": " + Math.round(value * 100) + "%");
        return new SliderButton(x, y, width, height, message, value, onValueChange, 0.0f, 1.0f, true, label + ": ", "%");
    }

    public static SliderButton forSeconds(int x, int y, int width, int height, String label, 
                                        double value, double maxSeconds, OnValueChange onValueChange) {
        Component message = Component.literal(label + ": " + String.format("%.1f", value) + "s");
        return new SliderButton(x, y, width, height, message, value / maxSeconds, 
            (slider, normalizedVal) -> onValueChange.onChange(slider, normalizedVal * maxSeconds),
            0.0f, (float)maxSeconds, false, label + ": ", "s");
    }

    public static SliderButton forRange(int x, int y, int width, int height, String label, 
                                      double value, double min, double max, OnValueChange onValueChange) {
        Component message = Component.literal(label + ": " + String.format("%.2f", value));
        return new SliderButton(x, y, width, height, message, value, 
            onValueChange, (float)min, (float)max, false, label + ": ", "");
    }

    @Override
    protected void updateMessage() {
        if (!prefix.isEmpty() || !suffix.isEmpty()) {
            // Uso de constructor personalizado
            double actualValue = getActualValue();
            
            if (suffix.equals("s")) {
                // Formato para segundos
                setMessage(Component.literal(prefix + String.format("%.1f", actualValue) + suffix));
            } else if (suffix.equals("%")) {
                // Formato para porcentajes
                setMessage(Component.literal(prefix + Math.round(actualValue * 100) + suffix));
            } else {
                // Formato general
                setMessage(Component.literal(prefix + String.format("%.2f", actualValue) + suffix));
            }
        }
        // Si no hay prefix/suffix, el mensaje se mantiene como estaba
    }

    @Override
    protected void applyValue() {
        if (onValueChange != null) {
            double actualValue = getActualValue();
            onValueChange.onChange(this, actualValue);
        }
        updateMessage();
    }

    private double getActualValue() {
        if (minValue != 0.0f || maxValue != 1.0f) {
            // Convertir valor normalizado a rango real
            return minValue + this.value * (maxValue - minValue);
        } else {
            // Usar valor normalizado directamente
            return this.value;
        }
    }

    // Métodos públicos para control externo
    public void setValue(double value) {
        if (minValue != 0.0f || maxValue != 1.0f) {
            // Normalizar el valor al rango 0-1
            this.value = Mth.clamp((value - minValue) / (maxValue - minValue), 0.0, 1.0);
        } else {
            this.value = Mth.clamp(value, 0.0, 1.0);
        }
        updateMessage();
    }

    public double getValue() {
        return getActualValue();
    }

    public double getRawValue() {
        return this.value; // Valor normalizado 0-1
    }

    // Método para verificar si los controles son funcionales
    public boolean isWorking() {
        return onValueChange != null;
    }

    // Método para debug
    public void debugInfo() {
        System.out.println("[SliderButton] Value: " + this.value + 
                          ", Actual: " + getActualValue() + 
                          ", Range: " + minValue + "-" + maxValue);
    }
}