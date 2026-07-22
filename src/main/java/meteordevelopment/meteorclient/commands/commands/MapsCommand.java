/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.commands.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.renderer.MapRenderer;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;

import java.io.File;
import java.util.List;

public class MapsCommand extends Command {
    public MapsCommand() {
        super("maps", "Opens the map viewer.", "map");
    }

    @Override
    public void build(LiteralArgumentBuilder<ClientSuggestionProvider> builder) {
        builder.executes(_ -> {
            List<File> maps = MapRenderer.getAvailableMaps();

            if (maps.isEmpty()) {
                File mapsDir = MapRenderer.getMapsDir();
                error("No maps found. Place .png files in " + mapsDir.getAbsolutePath());
                return SINGLE_SUCCESS;
            }

            // Open the first available map
            MapRenderer.openMapViewer(maps.get(0));

            if (maps.size() > 1) {
                info("Opened " + maps.get(0).getName() + ". (" + (maps.size() - 1) + " more maps available)");
            }

            return SINGLE_SUCCESS;
        });
    }
}
