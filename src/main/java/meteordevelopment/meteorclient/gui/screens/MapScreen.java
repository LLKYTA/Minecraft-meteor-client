/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.gui.screens;

import com.mojang.blaze3d.platform.NativeImage;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WidgetScreen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class MapScreen extends WidgetScreen {
    private final File mapFile;
    private Identifier textureId;
    private DynamicTexture texture;
    private int mapWidth, mapHeight;
    private boolean loaded = false;

    // Pan and zoom state (in scaled screen coordinates)
    private double offsetX = 0, offsetY = 0;
    private double zoom = 1.0;
    private boolean dragging = false;
    private double lastMouseX, lastMouseY;

    public MapScreen(GuiTheme theme, File mapFile) {
        super(theme, "Map Viewer");
        this.mapFile = mapFile;
        loadMap();
    }

    private void loadMap() {
        if (mapFile == null || !mapFile.exists()) return;

        try (FileInputStream in = new FileInputStream(mapFile)) {
            NativeImage image = NativeImage.read(in);
            mapWidth = image.getWidth();
            mapHeight = image.getHeight();

            // Create identifier without extension and normalized (lowercase, spaces -> underscores)
            String name = mapFile.getName();
            int dotIndex = name.lastIndexOf('.');
            if (dotIndex > 0) name = name.substring(0, dotIndex);
            String normalizedName = name.toLowerCase().replace(' ', '_');
            textureId = MeteorClient.identifier("maps/" + normalizedName);

            // Create and register the dynamic texture
            texture = new DynamicTexture(() -> normalizedName, image);
            mc.getTextureManager().register(textureId, texture);

            loaded = true;
        } catch (IOException e) {
            loaded = false;
        }
    }

    @Override
    public void initWidgets() {
        // No widgets needed for full-screen map view
    }

    @Override
    protected void onClosed() {
        if (texture != null) {
            mc.getTextureManager().release(textureId);
            texture = null;
            textureId = null;
        }
    }

    @Override
    public void renderCustom(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.renderCustom(graphics, mouseX, mouseY, delta);

        if (!loaded || textureId == null) {
            // Draw placeholder text if no map loaded
            graphics.text(mc.font, "No map loaded. Place .png files in .minecraft/meteor-client/maps/",
                width / 2 - 150, height / 2, 0xFFAAAAAA, false);
            return;
        }

        // Draw map texture centered, with pan and zoom
        int screenCenterX = width / 2;
        int screenCenterY = height / 2;

        double mapW = mapWidth * zoom;
        double mapH = mapHeight * zoom;

        double mapLeft = screenCenterX - mapW / 2 + offsetX;
        double mapTop = screenCenterY - mapH / 2 + offsetY;

        // Draw the map texture
        graphics.blit(RenderPipelines.GUI_TEXTURED, textureId,
            (int) mapLeft, (int) mapTop, 0, 0, (int) mapW, (int) mapH, mapWidth, mapHeight);

        // Draw player position marker (center of screen with offset)
        if (mc.player != null) {
            int markerX = (int) (screenCenterX + offsetX);
            int markerY = (int) (screenCenterY + offsetY);
            graphics.fill(markerX - 3, markerY - 3, markerX + 3, markerY + 3, 0xFFFF0000);
        }

        // Draw calibration info
        graphics.text(mc.font, "Zoom: " + String.format("%.1f", zoom), 10, 10, 0xFFFFFFFF, false);
        graphics.text(mc.font, "Scroll to zoom, drag to pan", 10, 25, 0xFFAAAAAA, false);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            double s = mc.getWindow().getGuiScale();
            dragging = true;
            lastMouseX = click.x() * s;
            lastMouseY = click.y() * s;
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        if (click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            dragging = false;
        }
        return super.mouseReleased(click);
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        if (dragging) {
            double s = mc.getWindow().getGuiScale();
            double scaledX = mouseX * s;
            double scaledY = mouseY * s;
            offsetX += scaledX - lastMouseX;
            offsetY += scaledY - lastMouseY;
            lastMouseX = scaledX;
            lastMouseY = scaledY;
        }
        super.mouseMoved(mouseX, mouseY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        zoom *= (verticalAmount > 0) ? 1.1 : 0.9;
        zoom = Math.max(0.1, Math.min(zoom, 10.0));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent input) {
        if (input.key() == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        return super.keyPressed(input);
    }
}
