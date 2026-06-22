package com.wildfire.physics;

import com.wildfire.api.IGenderArmor;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.Vec3;

/**
 * Reworked BreastPhysics that accepts configurable tuning parameters from the player settings.
 * Keeps full feature set (bounce multiplier, stiffness, damping, intensity, momentum, armor override)
 * while improving stability for the updated model sizes.
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

    // Rotation-flop tuning (base)
    private static final float ROTATION_FLOP_STRENGTH = 0.035f;
    private static final float ROTATION_FLOP_MAX = 0.65f;

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
     * Backwards-compatible update overload used by older code paths that call update(entity, armor).
     * This calls the full update with reasonable defaults so legacy callers compile and behave.
     */
    public void update(EntityLivingBase entity, IGenderArmor armor) {
        // Defaults chosen to approximate previous behavior if callers didn't provide tuning:
        float bounceMultiplier = 1.0f;
        float stiffness = 0.1f;
        float damping = 0.85f;
        float intensity = 100.0f;
        float momentum = 0.5f;
        boolean armorOverride = false;
        update(entity, armor, bounceMultiplier, stiffness, damping, intensity, momentum, armorOverride);
    }

    /**
     * Update the physics state.
     * @param entity the entity to base movement on
     * @param armor the armor config (can affect physics resistance)
     * @param bounceMultiplier user-configured bounce multiplier (typically around 0..1)
     * @param stiffness spring stiffness
     * @param damping damping factor
     * @param intensity overall intensity scalar
     * @param momentum momentum influence scalar (0..1+)
     * @param armorPhysicsOverride if true, armor physics override is enabled (ignore armor resistance)
     */
    public void update(EntityLivingBase entity, IGenderArmor armor,
                       float bounceMultiplier, float stiffness, float damping,
                       float intensity, float momentum, boolean armorPhysicsOverride) {
        // store previous values for interpolation
        this.prePositionX = this.positionX;
        this.prePositionY = this.positionY;
        this.wfg_preBounceRotation = this.wfg_bounceRotation;
        this.preBreastSize = this.breastSize;

        if (entity == null) return;

        Vec3 motion = new Vec3(entity.motionX, entity.motionY, entity.motionZ);

        float armorRes = 1.0f;
        if (armor != null && !armorPhysicsOverride) {
            armorRes = 1.0f - Math.min(0.95f, armor.physicsResistance());
        }

        float horizontalSpeed = (float)Math.sqrt(motion.xCoord * motion.xCoord + motion.zCoord * motion.zCoord);
        float verticalSpeed = (float) motion.yCoord;

        // X target influenced by lateral motion, scaled by bounceMultiplier/momentum
        this.targetBounceX = - (float)(motion.xCoord * 10.0f) * 0.02f * bounceMultiplier * armorRes * momentum;
        this.targetBounceX += - (float)(motion.zCoord * 10.0f) * 0.015f * bounceMultiplier * armorRes * momentum;

        // Y target influenced by vertical motion and intensity, with small sag bias
        this.targetBounceY = (float)(verticalSpeed * 25.0f) * 0.02f * bounceMultiplier * armorRes;
        this.targetBounceY += -0.02f * (0.5f + horizontalSpeed) * intensity * 0.002f;

        // Rotation target uses swing and horizontal motion
        this.targetRotVel = calcRotation(entity, 0.5f + momentum * 0.5f) * armorRes * bounceMultiplier;

        // Integrate position using spring-damper with provided stiffness/damping
        float posStiff = clamp(stiffness, 0.001f, 2.0f);
        float posDamp = clamp(damping, 0.01f, 0.99f);

        this.velocity = this.velocity * posDamp + (this.targetBounceY - this.positionY) * posStiff * 0.5f;
        this.positionY += this.velocity;

        this.velocityX = this.velocityX * posDamp + (this.targetBounceX - this.positionX) * posStiff * 0.5f;
        this.positionX += this.velocityX;

        // Rotation integration; intensity increases rotational responsiveness slightly
        float rotStiff = 0.6f * (1.0f + intensity * 0.01f);
        float rotDamp = 0.8f;
        this.rotVelocity = this.rotVelocity * rotDamp + (this.targetRotVel - this.wfg_bounceRotation) * (rotStiff * 0.04f);
        this.wfg_bounceRotation += this.rotVelocity;

        if (this.wfg_bounceRotation > ROTATION_FLOP_MAX) this.wfg_bounceRotation = ROTATION_FLOP_MAX;
        if (this.wfg_bounceRotation < -ROTATION_FLOP_MAX) this.wfg_bounceRotation = -ROTATION_FLOP_MAX;

        // Cosmetic breast size modulation
        float sizeTarget = VISUAL_BREAST_WEIGHT * (0.2f + horizontalSpeed * 0.6f) * (1.0f + intensity * 0.01f);
        this.breastSize += (sizeTarget - this.breastSize) * 0.08f;

        this.lastVerticalMoveVelocity = verticalSpeed;

        // Keep values within sane ranges
        this.positionX = clamp(this.positionX, -2.5f, 2.5f);
        this.positionY = clamp(this.positionY, -2.5f, 3.0f);
    }

    private float calcRotation(EntityLivingBase entity, float momentumFactor) {
        float swing = 0f;
        try {
            float swingProg = entity.getSwingProgress(0.0F);
            swing = (float)Math.sin(Math.sqrt(swingProg) * Math.PI * 2.0) * 0.1f;
        } catch (Throwable ignored) {}

        float velInfluence = (float)Math.sqrt(entity.motionX * entity.motionX + entity.motionZ * entity.motionZ) * 0.35f;
        return (swing * ROTATION_FLOP_STRENGTH * momentumFactor) + velInfluence * 0.7f;
    }

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

        this.preBreastSize = source.preBreastSize;
        this.breastSize = source.breastSize;

        this.lastSwingDuration = source.lastSwingDuration;
        this.lastSwingTick = source.lastSwingTick;
        this.randomB = source.randomB;
        this.lastVerticalMoveVelocity = source.lastVerticalMoveVelocity;
    }

    /* Getters for rendering interpolation */
    public float getPrePositionY() { return prePositionY; }
    public float getPositionY()    { return positionY; }
    public float getPrePositionX() { return prePositionX; }
    public float getPositionX()    { return positionX; }
    public float getBounceRotation() { return wfg_bounceRotation; }
    public float getPreBounceRotation() { return wfg_preBounceRotation; }
    public float getBreastSize() { return breastSize; }
    public float getPreBreastSize() { return preBreastSize; }

    private static float lerp(float t, float a, float b) { return a + t * (b - a); }
    private static float clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }
}
