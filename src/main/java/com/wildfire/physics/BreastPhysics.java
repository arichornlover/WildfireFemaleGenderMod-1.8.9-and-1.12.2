package com.wildfire.physics;

import com.wildfire.api.IGenderArmor;
import com.wildfire.main.WildfireHelper;
import com.wildfire.main.config.GenderConfig;
import com.wildfire.main.entitydata.EntityConfig;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;

/**
 * BreastPhysics for Forge 1.8.9
 *
 * - Restores original structure and step methods similar to the Fabric implementation.
 * - Preserves features: tightness reduction, rotation flop, momentum, uniboob sync, armor suppression,
 *   vehicle handling, arm swing influence, simplified mode support.
 * - Adds safety clamps and armor override support to avoid runaway values with the updated model.
 *
 * Compatibility:
 * - Keeps the public signature update(EntityLivingBase, IGenderArmor) so EntityConfig and other callers
 *   don't need to change.
 */
public class BreastPhysics {

    public static final float TIGHTNESS_REDUCTION_FACTOR = 0.15F;

    // X axis
    private float bounceVelX = 0f, targetBounceX = 0f, velocityX = 0f, positionX = 0f, prePositionX = 0f;
    // Y axis
    private float bounceVel = 0f, targetBounceY = 0f, velocity = 0f, positionY = 0f, prePositionY = 0f;
    // Rotation
    private float bounceRotVel = 0f, targetRotVel = 0f, rotVelocity = 0f, bounceRotation = 0f, preBounceRotation = 0f;

    private float breastSize = 0f, preBreastSize = 0f;

    private Vec3 prePos = null;
    private int lastSwingDuration = 6, lastSwingTick = 0;
    private int randomB = 1;
    private double lastVerticalMoveVelocity = 0.0;

    // Visual weight (how much visual size contributes to movement)
    private static final float VISUAL_BREAST_WEIGHT = 0.1f;

    // Rotation flop tuning
    private static final float ROTATION_FLOP_STRENGTH = 0.035f;
    private static final float ROTATION_FLOP_MAX = 0.65f;

    // Momentum tuning (baseline + scale)
    private static final float MOMENTUM_BASE = 0.25f;
    private static final float MOMENTUM_SCALE = 2.75f;

    // Optional reference to an owning EntityConfig — left null by default to remain compatible
    private EntityConfig entityConfig = null;

    public BreastPhysics() {
        resetPhysics();
    }

    // Optional constructor if you later want to pass EntityConfig in from EntityConfig
    public BreastPhysics(EntityConfig config) {
        this.entityConfig = config;
        resetPhysics();
    }

    public void resetPhysics() {
        this.prePositionX = this.positionX = 0f;
        this.prePositionY = this.positionY = 0f;
        this.velocityX = this.velocity = 0f;
        this.bounceVel = this.bounceVelX = this.bounceRotVel = this.targetBounceX = this.targetBounceY = this.targetRotVel = 0f;
        this.rotVelocity = 0f;
        this.preBounceRotation = this.bounceRotation = 0f;
        this.preBreastSize = this.breastSize = 0f;
        this.prePos = null;
        this.lastSwingDuration = 6;
        this.lastSwingTick = 0;
        this.randomB = 1;
        this.lastVerticalMoveVelocity = 0.0;
    }

    /**
     * Main update method used by the rest of the mod.
     * Keeps the original API (EntityLivingBase, IGenderArmor).
     *
     * Behavior notes:
     *  - If armor.coversBreasts() && player.settings.overrideArmorPhysics == false => physics suppressed.
     *  - Otherwise physics steps run (movement, pose, ride, arm swing) and finishTick clamps results.
     */
    public void update(EntityLivingBase entity, IGenderArmor armor) {
        if (entity == null) return;

        // store previous values for interpolation
        this.prePositionX = this.positionX;
        this.prePositionY = this.positionY;
        this.preBounceRotation = this.bounceRotation;
        this.preBreastSize = this.breastSize;

        // initialization on first tick
        if (this.prePos == null) {
            this.prePos = new Vec3(entity.posX, entity.posY, entity.posZ);
            if (entity instanceof EntityPlayer) {
                GenderConfig.PlayerGenderSettings s = GenderConfig.getPlayerSettings((EntityPlayer) entity);
                if (s != null) {
                    this.breastSize = this.preBreastSize = s.breastSize;
                }
            }
            return;
        }

        // Acquire per-player settings if available
        GenderConfig.PlayerGenderSettings settings = null;
        boolean uniboob = false;
        float intensity = 1.0f;
        float momentumNormalized = 1.0f; // 0..1 default
        if (entity instanceof EntityPlayer) {
            settings = GenderConfig.getPlayerSettings((EntityPlayer) entity);
            if (settings != null) {
                uniboob = settings.breastsUniboob;
                intensity = clamp(settings.intensity / 100.0f, 0f, 2.0f);
                momentumNormalized = clamp(settings.momentum / 100.0f, 0f, 1.0f);
            }
        }

        // Armor suppression: if armor covers breasts and player did not enable override, suppress physics
        try {
            if (armor != null && armor.coversBreasts() && settings != null && !settings.overrideArmorPhysics) {
                // soften dynamic quantities to let breasts settle
                this.targetBounceX = 0f;
                this.targetBounceY = 0f;
                this.targetRotVel = 0f;
                this.velocity = 0f;
                this.velocityX = 0f;
                this.rotVelocity = 0f;
                this.bounceVel = 0f;
                this.bounceVelX = 0f;
                this.bounceRotVel = 0f;
                finishTick();
                return;
            }
        } catch (Throwable ignored) {
            // If armor misbehaves, continue with physics rather than crash.
        }

        // Compute motion from position delta (robust to client/server differences)
        Vec3 curPos = new Vec3(entity.posX, entity.posY, entity.posZ);
        Vec3 motion = curPos.subtract(this.prePos);
        this.prePos = curPos;

        // scale bounce intensity by intensity and armor resistance
        float bounceIntensity = intensity * 0.9f;
        float armorRes = armor != null ? clamp(armor.physicsResistance(), 0f, 1f) : 0f;
        bounceIntensity *= (1f - armorRes);

        // small variance for non-uniboob for a more organic look
        if (!uniboob) bounceIntensity *= WildfireHelper.randFloat(0.95f, 1.05f);

        // run physics steps (mirrors Fabric structure)
        tickMovement(entity, motion, bounceIntensity, VISUAL_BREAST_WEIGHT, momentumNormalized);
        tickPose(entity, bounceIntensity);
        tickRide(entity, bounceIntensity, VISUAL_BREAST_WEIGHT);
        tickArmSwing(entity, bounceIntensity, uniboob);
        finishTick();

        // final safety clamps tuned for new model sizes
        this.positionX = clamp(this.positionX, -1.5f, 1.5f);
        this.positionY = clamp(this.positionY, -1.5f, 2.0f);
        this.bounceRotation = clamp(this.bounceRotation, -ROTATION_FLOP_MAX, ROTATION_FLOP_MAX);
    }

    private void tickMovement(EntityLivingBase entity, Vec3 motion, float bounceIntensity, float breastWeight, float momentumNorm) {
        double vert = entity.motionY;
        if ((lastVerticalMoveVelocity <= 0 && vert > 0) ||
            (lastVerticalMoveVelocity < 0 && vert == 0)) {
            randomB = (entity.worldObj != null && entity.worldObj.rand != null && !entity.worldObj.rand.nextBoolean()) ? -1 : 1;
        }
        lastVerticalMoveVelocity = vert;

        // vertical target from motion (makes breasts bounce on jumps/falls)
        this.targetBounceY = (float) motion.yCoord * bounceIntensity;
        this.targetBounceY += breastWeight * 0.5f;

        // rotation-driven target
        this.targetRotVel = calcRotation(entity, bounceIntensity);
        this.targetRotVel += (float) motion.yCoord * bounceIntensity * randomB;

        // base lateral target from rotation
        this.targetBounceX = -calcRotation(entity, bounceIntensity) / 10f;

        // Momentum inertia: entity translational motion contributes to lateral offsets
        float momentumMultiplier = MOMENTUM_BASE + MOMENTUM_SCALE * momentumNorm;
        float inertiaX = (float) motion.xCoord * momentumMultiplier;
        float inertiaZ = (float) motion.zCoord * momentumMultiplier;

        // Apply inertia: lateral inertia and small forward/back bob
        this.targetBounceX += inertiaX * 0.65f;
        this.targetBounceY += Math.abs(inertiaZ) * 0.05f;

        // integrate simple damped spring for position
        float damping = 0.85f;
        float stiffness = 0.6f;
        this.velocity = this.velocity * damping + (this.targetBounceY - this.positionY) * stiffness;
        this.positionY += this.velocity;
        this.velocityX = this.velocityX * damping + (this.targetBounceX - this.positionX) * stiffness;
        this.positionX += this.velocityX;
    }

    private void tickPose(EntityLivingBase entity, float bounceIntensity) {
        // Influence from entity pose (sneaking/arm positions) could be added here
        if (entity.isSneaking()) {
            // simulate compression when sneaking
            this.positionY -= 0.08f * bounceIntensity;
        }
    }

    private void tickRide(EntityLivingBase entity, float bounceIntensity, float breastWeight) {
        // If mounted, secondary inertia occurs based on vehicle motion — simple approximation
        Entity mount = entity.ridingEntity;
        if (mount != null) {
            double dx = mount.posX - mount.prevPosX;
            double dz = mount.posZ - mount.prevPosZ;
            float mSpeed = (float)Math.sqrt(dx * dx + dz * dz);
            this.positionY += mSpeed * 0.02f * bounceIntensity;
            this.positionX += (float)dx * 0.02f;
            this.targetRotVel += mSpeed * 0.01f;
        }
    }

    private void tickArmSwing(EntityLivingBase entity, float bounceIntensity, boolean uniboob) {
        // Arm swing effect: add rotation/bounce when entity swings
        try {
            float swing = entity.getSwingProgress(0.0F);
            float swingInfluence = (float)Math.sin(Math.sqrt(swing) * Math.PI * 2.0) * 0.1f;
            this.targetRotVel += swingInfluence * bounceIntensity;
            if (!uniboob) {
                // small lateral bounce when swinging
                this.targetBounceX += swingInfluence * 0.04f;
            }
        } catch (Throwable ignored) {}
    }

    private void finishTick() {
        // Rotation integration with flop & damping
        float rotDamping = 0.85f;
        float rotSpring = 0.08f;
        this.rotVelocity = this.rotVelocity * rotDamping + (this.targetRotVel - this.bounceRotation) * rotSpring;
        this.bounceRotation += this.rotVelocity;

        // BounceVel smoothing (used in legacy callers)
        this.bounceVel = this.bounceVel * 0.7f + this.targetBounceY * 0.3f;
        this.bounceVelX = this.bounceVelX * 0.7f + this.targetBounceX * 0.3f;

        // subtle cosmetic breast size lerp towards target (if any)
        float targetSize = this.breastSize;
        this.breastSize += (targetSize - this.breastSize) * 0.1f;
    }

    private float calcRotation(EntityLivingBase entity, float bounceIntensity) {
        float swing = 0f;
        try {
            float swingProg = entity.getSwingProgress(0.0F);
            swing = (float)Math.sin(Math.sqrt(swingProg) * Math.PI * 2.0) * 0.1f;
        } catch (Throwable ignored) {}

        float velInfluence = (float)Math.sqrt(entity.motionX * entity.motionX + entity.motionZ * entity.motionZ) * 0.35f;
        return (swing * ROTATION_FLOP_STRENGTH * bounceIntensity) + velInfluence * 0.7f;
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

        this.preBounceRotation = source.preBounceRotation;
        this.bounceRotation = source.bounceRotation;
        this.rotVelocity = source.rotVelocity;

        this.preBreastSize = source.preBreastSize;
        this.breastSize = source.breastSize;

        this.lastSwingDuration = source.lastSwingDuration;
        this.lastSwingTick = source.lastSwingTick;
        this.randomB = source.randomB;
        this.lastVerticalMoveVelocity = source.lastVerticalMoveVelocity;
    }

    /* ------------------- GETTERS ------------------- */
    public float getPrePositionY()      { return prePositionY; }
    public float getPositionY()         { return positionY; }
    public float getPrePositionX()      { return prePositionX; }
    public float getPositionX()         { return positionX; }
    public float getBounceRotation()    { return bounceRotation; }
    public float getPreBounceRotation() { return preBounceRotation; }
    public float getBreastSize()        { return breastSize; }
    public float getPreBreastSize()     { return preBreastSize; }

    /* ------------------- UTIL ------------------- */
    private static float clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }
}
