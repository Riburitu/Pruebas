// RegionSelection.java

package com.riburitu.regionvisualizer.util;

import net.minecraft.core.BlockPos;
import java.util.Objects;

public class RegionSelection {
    public BlockPos pos1;
    public BlockPos pos2;

    public void setPos1(BlockPos pos) {
        this.pos1 = pos;
    }

    public void setPos2(BlockPos pos) {
        this.pos2 = pos;
    }

    public boolean isComplete() {
        return pos1 != null && pos2 != null;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        
        RegionSelection that = (RegionSelection) obj;
        return Objects.equals(pos1, that.pos1) && Objects.equals(pos2, that.pos2);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pos1, pos2);
    }

    @Override
    public String toString() {
        return "RegionSelection{pos1=" + pos1 + ", pos2=" + pos2 + ", complete=" + isComplete() + "}";
    }
}