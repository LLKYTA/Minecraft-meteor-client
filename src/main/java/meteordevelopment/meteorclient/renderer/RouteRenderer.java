/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.renderer;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class RouteRenderer {
    private final List<Vec3> waypoints = new ArrayList<>();
    private boolean active = false;
    private int lineColor = 0xFFFFFFFF;

    public void setWaypoints(List<Vec3> points) {
        waypoints.clear();
        waypoints.addAll(points);
    }

    public void addWaypoint(Vec3 point) {
        waypoints.add(point);
    }

    public void clearWaypoints() {
        waypoints.clear();
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public boolean isActive() {
        return active;
    }

    public void setLineColor(int color) {
        this.lineColor = color;
    }

    public List<Vec3> getWaypoints() {
        return Collections.unmodifiableList(waypoints);
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (!active || waypoints.size() < 2) return;

        // Draw line connecting waypoints in order
        // Skip waypoints too far from player (beyond 200 blocks)
        Vec3 cameraPos = new Vec3(event.offsetX, event.offsetY, event.offsetZ);

        for (int i = 0; i < waypoints.size() - 1; i++) {
            Vec3 p1 = waypoints.get(i);
            Vec3 p2 = waypoints.get(i + 1);

            // Skip if either point is too far
            if (p1.distanceToSqr(cameraPos) > 40000 || p2.distanceToSqr(cameraPos) > 40000) continue;

            // Draw line segment
            // Using the renderer's line drawing capability
            event.renderer.line(p1.x, p1.y, p1.z, p2.x, p2.y, p2.z, new Color(lineColor));
        }

        // Draw end marker (if the last waypoint is within range)
        if (!waypoints.isEmpty()) {
            Vec3 last = waypoints.get(waypoints.size() - 1);
            if (last.distanceToSqr(cameraPos) <= 40000) {
                // Draw a small box/circle at the end point
                float size = 0.5f;
                event.renderer.box(last.x - size, last.y - size, last.z - size,
                    last.x + size, last.y + size, last.z + size, new Color(lineColor), new Color(lineColor), ShapeMode.Lines, 0);
            }
        }
    }
}
