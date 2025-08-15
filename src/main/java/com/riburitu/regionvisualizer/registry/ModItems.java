// ModItems.java

package com.riburitu.regionvisualizer.registry;

import com.riburitu.regionvisualizer.RegionVisualizer;
import com.riburitu.regionvisualizer.item.RegionSelectorItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModItems {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, RegionVisualizer.MODID);

    public static final RegistryObject<Item> REGION_SELECTOR = ITEMS.register("region_selector",
            () -> new RegionSelectorItem(new Item.Properties().stacksTo(1)));

    public static void registerCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey().equals(CreativeModeTabs.TOOLS_AND_UTILITIES)) {
            event.accept(REGION_SELECTOR);
        }
    }
}
