package com.wildfire.physics;

import com.wildfire.api.IGenderArmor;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.Vec3;

/**
 * Reworked BreastPhysics:
 *  - stable integration using entity motion + pose
 *  - proper pre/post values for interpolation used by render code
 *  - syncFrom for uniboob (reliable copy)
 *  - reasonable damping and limits for the Forge model size
 */
public class BreastPhysics {

    // X axis
    private float bounceVelX = 0f, targetBounceX = 0f, velocityX = 0f, positionX = 0f, prePositionX = 0f;
    // Y axis
    private float bounceVel = 0f, targetBounceY = 0f, velocity = 0f, positionY = 0f, prePositionY = 0f;
    // Rotation
    private float bounceRotVel = 0f, targetRotVel = 0f, rotVelocity = 0f,
            wfg_bounceRotation = 0f, wfg_preBounceRotation = 0f;
    // Visual size (cosmetic)
    private float breastSize = 0f, preBreastSize = 0f;

    private Vec3 prePos = null;
    private int lastSwingDuration = 6, lastSwingTick = 0;
    private int randomB = 1;
    private double lastVerticalMoveVelocity = 0.0;

    private static final float VISUAL_BREAST_WEIGHT = 0.1f;

    // Rotation-flop tuning
    private static final float ROTATION_FLOP_STRENGTH = 0.035f;
    private static final float ROTATION_FLOP_MAX = 0.65f;

    // Physics tuning
    private static final float POSITION_DAMPING = 0.85f;
    private static final float BOUNCE_DAMPING = 0.7f;
    private static final float ROTATION_DAMPING = 0.85f;
    private static final float POSITION_SPRING = 0.6f;
    private static final float ROTATION_SPRING = 0.8f;

    private static final float MOMENTUM_BASE = 0.25f;     // baseline fraction of motion always contributing
    private static final float MOMENTUM_SCALE = 2.75f;    // how much extra contribution at full momentum

    public BreastPhysics() {
        resetPhysics();
    }

    public void resetPhysics() {
        this.bounceVelX = this.targetBounceX = this.velocityX = this.positionX = this.prePositionX = 0f;
        this.bounceVel = this.targetBounceY = this.velocity = this.positionY = this.prePositionY = 0f;
        this.bounceRotVel = this.targetRotVel = this.rotVelocity = this.wfg_bounceRotation = this.wfg_preBounceRotation = 0f;
        this.breastSize = this.preBreastSize = 0f;
        this.prePos = null;
        this.lastSwingDuration = 6; this.lastSwingTick = 0;
        this.randomB = 1;
        this.lastVerticalMoveVelocity = 0.0;
    }

    /**
     * Update physics given the entity and the currently worn armor config.
     */
    public void update(EntityLivingBase entity, IGenderArmor armor) {
        // Store previous values for interpolation
        this.prePositionX = this.positionX;
        this.prePositionY = this.positionY;
        this.wfg_preBounceRotation = this.wfg_bounceRotation;
        this.preBreastSize = this.breastSize;

        // Capture current motion / pose data
        Vec3 motion = new Vec3(entity.motionX, entity.motionY, entity.motionZ);

        // Determine armor resistance (scale down motion response)
        float armorResistance = 1.0f;
        if (armor != null) {
            armorResistance = 1.0f - Math.min(0.9f, armor.physicsResistance());
        }

        // Movement-based target: use horizontal momentum and vertical motion for up/down
        float horizontalSpeed = (float)Math.sqrt(motion.xCoord * motion.xCoord + motion.zCoord * motion.zCoord);
        float verticalSpeed = (float)motion.yCoord;
        float momentumFactor = MOMENTUM_BASE + MOMENTUM_SCALE * Math.min(1.0f, horizontalSpeed / 0.6f);

        // X position target is influenced by lateral motion and swing
        this.targetBounceX = - (float)(motion.xCoord * 10.0f) * armorResistance * 0.02f;
        this.targetBounceX += - (float)(motion.zCoord * 10.0f) * armorResistance * 0.015f;

        // Y position target influenced by vertical motion and jumps/falls
        this.targetBounceY = (float)(verticalSpeed * 25.0f) * armorResistance * 0.02f;
        // small upward bias to simulate sag
        this.targetBounceY += -0.02f * momentumFactor;

        // Rotation target responds to lateral acceleration and arm swing
        this.targetRotVel = calcRotation(entity, momentumFactor) * armorResistance;

        // Integrate simple spring-damper for position (Y)
        this.velocity = this.velocity * POSITION_DAMPING + (this.targetBounceY - this.positionY) * POSITION_SPRING;
        this.positionY += this.velocity;

        // Integrate spring-damper for X
        this.velocityX = this.velocityX * POSITION_DAMPING + (this.targetBounceX - this.positionX) * POSITION_SPRING;
        this.positionX += this.velocityX;

        // Rotation integration
        this.rotVelocity = this.rotVelocity * ROTATION_DAMPING + (this.targetRotVel - this.wfg_bounceRotation) * ROTATION_SPRING;
        this.wfg_bounceRotation += this.rotVelocity;

        // Bounce rotation clamping
        if (this.wfg_bounceRotation > ROTATION_FLOP_MAX) this.wfg_bounceRotation = ROTATION_FLOP_MAX;
        if (this.wfg_bounceRotation < -ROTATION_FLOP_MAX) this.wfg_bounceRotation = -ROTATION_FLOP_MAX;

        // Cosmetic breast size adjustments (subtle)
        float sizeTarget = VISUAL_BREAST_WEIGHT * (0.2f + horizontalSpeed * 0.6f);
        this.breastSize += (sizeTarget - this.breastSize) * 0.08f;

        // remember last vertical move velocity for inertia calculations
        this.lastVerticalMoveVelocity = verticalSpeed;

        // Keep values in sane ranges
        this.positionX = clamp(this.positionX, -1.5f, 1.5f);
        this.positionY = clamp(this.positionY, -1.5f, 2.0f);
    }

    /* ------------------- PHYSICS HELPERS ------------------- */

    private float calcRotation(EntityLivingBase entity, float momentumFactor) {
        // Use swing progress + horizontal movement to create rotation target
        float swing = 0f;
        try {
            float swingProg = entity.getSwingProgress(0.0F);
            swing = (float)Math.sin(Math.sqrt(swingProg) * Math.PI * 2.0) * 0.1f;
        } catch (Throwable ignored) {}

        float velInfluence = (float)Math.sqrt(entity.motionX * entity.motionX + entity.motionZ * entity.motionZ) * 0.35f;
        return (swing * ROTATION_FLOP_STRENGTH * momentumFactor) + velInfluence * 0.7f;
    }

    /* ------------------- SYNC (UNIBOOB) ------------------- */
    public void syncFrom(BreastPhysics source) {
        if (source == null) return;

        this.prePositionX = source.prePositionX;
        this.positionX = source.positionX;
        this.velocityX = source.velocityX;
        this.bounceVelX = source.bounceVelX;
        this.targetBounceX = source.targetBounceX;

        this.prePositionY = source.prePositionY;
        this.positionY = source.positionY;
        this.velocity = source.velocity;
        this.bounceVel = source.bounceVel;
        this.targetBounceY = source.targetBounceY;

        this.wfg_preBounceRotation = source.wfg_preBounceRotation;
        this.wfg_bounceRotation = source.wfg_bounceRotation;
        this.rotVelocity = source.rotVelocity;
        this.rotVelocity = source.rotVelocity;

        this.preBreastSize = source.preBreastSize;
        this.breastSize = source.breastSize;

        this.lastSwingDuration = source.lastSwingDuration;
        this.lastSwingTick = source.lastSwingTick;
        this.randomB = source.randomB;
        this.lastVerticalMoveVelocity = source.lastVerticalMoveVelocity;
    }

    /* ------------------- GETTERS (for render interpolation) ------------------- */
    public float getPrePositionY()      { return prePositionY; }
    public float getPositionY()         { return positionY; }
    public float getPrePositionX()      { return prePositionX; }
    public float getPositionX()         { return positionX; }
    public float getBounceRotation()    { return wfg_bounceRotation; }
    public float getPreBounceRotation() { return wfg_preBounceRotation; }
    public float getBreastSize()        { return breastSize; }
    public float getPreBreastSize()     { return preBreastSize; }

    /* ------------------- UTILS ------------------- */
    private static float lerp(float t, float a, float b) { return a + t * (b - a); }
    private static float clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }
}
