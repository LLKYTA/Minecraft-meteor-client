/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.movement;

import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

public class ElytraPlus extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSpeed = settings.createGroup("Speed");
    private final SettingGroup sgAutopilot = settings.createGroup("Autopilot");

    // General
    public final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("Flight control mode.")
        .defaultValue(Mode.Cruise)
        .build()
    );

    public final Setting<Boolean> noDurability = sgGeneral.add(new BoolSetting.Builder()
        .name("no-durability")
        .description("Prevent elytra from losing durability.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> autoTakeOff = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-take-off")
        .description("Automatically start flying when holding jump.")
        .defaultValue(true)
        .build()
    );

    // Speed
    public final Setting<Double> minHeight = sgSpeed.add(new DoubleSetting.Builder()
        .name("min-height")
        .description("Minimum flight height.")
        .defaultValue(120)
        .min(0)
        .sliderMax(320)
        .build()
    );

    public final Setting<Double> maxHeight = sgSpeed.add(new DoubleSetting.Builder()
        .name("max-height")
        .description("Maximum flight height.")
        .defaultValue(260)
        .min(0)
        .sliderMax(320)
        .build()
    );

    public final Setting<Double> cruiseHeight = sgSpeed.add(new DoubleSetting.Builder()
        .name("cruise-height")
        .description("Target cruise height.")
        .defaultValue(200)
        .min(0)
        .sliderMax(320)
        .build()
    );

    // Autopilot
    public final Setting<Boolean> autoLand = sgAutopilot.add(new BoolSetting.Builder()
        .name("auto-land")
        .description("Automatically descend when near target.")
        .defaultValue(true)
        .visible(() -> mode.get() == Mode.FollowRoute)
        .build()
    );

    public final Setting<Double> landDistance = sgAutopilot.add(new DoubleSetting.Builder()
        .name("land-distance")
        .description("Distance to target at which to start landing.")
        .defaultValue(10)
        .min(1)
        .sliderMax(50)
        .visible(() -> autoLand.get() && mode.get() == Mode.FollowRoute)
        .build()
    );

    // Internal state
    private Vec3 currentTarget = Vec3.ZERO;
    private boolean hasTarget = false;

    public ElytraPlus() {
        super(Categories.Movement, "elytra-plus", "Enhanced elytra flight with autopilot and route following.");
    }

    @Override
    public void onActivate() {
        if (noDurability.get() && mc.player != null) {
            var stack = mc.player.getItemBySlot(EquipmentSlot.CHEST);
            if (stack.has(DataComponents.GLIDER) && stack.getItem() == Items.ELYTRA && stack.getDamageValue() > 0) {
                stack.setDamageValue(0);
            }
        }
    }

    @Override
    public void onDeactivate() {
        hasTarget = false;
    }

    /** Set the target position for route following mode */
    public void setTarget(Vec3 target) {
        this.currentTarget = target;
        this.hasTarget = true;
    }

    /** Clear current target */
    public void clearTarget() {
        this.hasTarget = false;
        this.currentTarget = Vec3.ZERO;
    }

    /** Check if module has an active target */
    public boolean hasTarget() {
        return hasTarget;
    }

    public Vec3 getTarget() {
        return currentTarget;
    }

    @EventHandler
    private void onPlayerMove(PlayerMoveEvent event) {
        if (mc.player == null) return;
        if (!(mc.player.getItemBySlot(EquipmentSlot.CHEST).has(DataComponents.GLIDER))) return;

        // Auto takeoff
        if (autoTakeOff.get() && !mc.player.isFallFlying() && mc.options.keyJump.isDown()) {
            mc.player.startFallFlying();
            return;
        }

        if (!mc.player.isFallFlying()) return;

        // No durability - repair every tick
        if (noDurability.get()) {
            var stack = mc.player.getItemBySlot(EquipmentSlot.CHEST);
            if (stack.getItem() == Items.ELYTRA && stack.getDamageValue() > 0) {
                stack.setDamageValue(0);
            }
        }

        // Height control - proportional with clamping
        double y = mc.player.getY();
        float pitchChange;

        if (y < minHeight.get()) {
            // Below min height - pitch up to gain height
            pitchChange = -3.0f;
        } else if (y > maxHeight.get()) {
            // Above max height - pitch down to lose height
            pitchChange = 3.0f;
        } else if (mode.get() == Mode.Cruise) {
            // Cruise - maintain height with proportional control
            double heightDiff = cruiseHeight.get() - y;
            pitchChange = (float) -(heightDiff * 0.05);
            pitchChange = Math.max(-3.0f, Math.min(3.0f, pitchChange)); // clamp per tick
        } else {
            pitchChange = 0;
        }

        // Apply pitch change smoothly
        float newPitch = mc.player.getXRot() + pitchChange;
        newPitch = Math.max(-30f, Math.min(30f, newPitch)); // Clamp to sensible elytra range
        mc.player.setXRot(newPitch);

        // Press forward key in Cruise mode too (elytra needs forward input to fly)
        if (mode.get() == Mode.Cruise) {
            mc.options.keyUp.setDown(true);
        }

        // Speed control - pitch based
        float pitch = mc.player.getXRot();
        if (pitch < -20) {
            event.movement = event.movement.scale(0.8);
        } else if (pitch > 20) {
            event.movement = event.movement.scale(1.2);
        }

        // Route following
        if (mode.get() == Mode.FollowRoute && hasTarget) {
            followTarget();
            mc.options.keyUp.setDown(true);
        }
    }

    private void followTarget() {
        Vec3 playerPos = mc.player.position();
        double dx = currentTarget.x - playerPos.x;
        double dz = currentTarget.z - playerPos.z;
        double distance = Math.sqrt(dx * dx + dz * dz);

        if (distance < landDistance.get() && autoLand.get()) {
            // Near target - start landing
            mc.player.setXRot(mc.player.getXRot() + 3.0f);
            if (mc.player.onGround()) {
                clearTarget();
                toggle();
            }
            return;
        }

        // Yaw towards target
        float targetYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        mc.player.setYRot(targetYaw);
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!isActive() || mc.player == null) return;
        // Keep forward key pressed while flying
        if (mc.player.isFallFlying()) {
            if (mode.get() == Mode.Cruise || (mode.get() == Mode.FollowRoute && hasTarget)) {
                mc.options.keyUp.setDown(true);
            }
        }
    }

    public enum Mode {
        Cruise,
        FollowRoute
    }
}
