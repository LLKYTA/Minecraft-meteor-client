/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.renderer;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.screens.MapScreen;
import net.minecraft.client.Minecraft;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MapRenderer {
    private static final File MAPS_DIR = new File(MeteorClient.FOLDER, "maps");

    static {
        MAPS_DIR.mkdirs();
    }

    /** Get list of available map PNG files */
    public static List<File> getAvailableMaps() {
        List<File> maps = new ArrayList<>();
        File[] files = MAPS_DIR.listFiles((dir, name) -> name.toLowerCase().endsWith(".png"));
        if (files != null) {
            for (File f : files) {
                maps.add(f);
            }
        }
        return maps;
    }

    /** Open the map viewer screen for a given map file */
    public static void openMapViewer(File mapFile) {
        if (mapFile.exists() && mapFile.getName().toLowerCase().endsWith(".png")) {
            Minecraft.getInstance().setScreen(new MapScreen(GuiThemes.get(), mapFile));
        }
    }

    /** Get the maps directory */
    public static File getMapsDir() {
        return MAPS_DIR;
    }
}
