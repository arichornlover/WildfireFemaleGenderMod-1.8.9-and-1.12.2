package com.wildfire.main;

import com.wildfire.api.IGenderArmor;
import com.wildfire.main.config.ClientConfig;
import com.wildfire.main.config.GenderConfig;
import com.wildfire.physics.BreastPhysics;
import com.wildfire.main.entitydata.EntityConfig;
import com.wildfire.main.uvs.UVLayout;
import com.wildfire.main.uvs.UVDirection;
import com.wildfire.main.uvs.UVQuad;
import com.wildfire.main.uvs.UVStorage;
import com.wildfire.render.armor.EmptyGenderArmor;
import com.wildfire.render.armor.SimpleGenderArmor;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.RenderPlayer;
import net.minecraft.client.renderer.entity.layers.LayerRenderer;
import net.minecraft.init.Items;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MathHelper;
import net.minecraft.util.ResourceLocation;
import net.minecraft.entity.player.EntityPlayer;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.RemovalListener;

/**
 * GenderLayer: now feeding physics with player-configurable tuning and preserving all features.
 */
public class GenderLayer implements LayerRenderer<AbstractClientPlayer> {

    private final RenderPlayer renderPlayer;
    private static final float MAX_PROTRUSION = 1.6f;

    private static final LoadingCache<UUID, BreastPhysics[]> PHYSICS_CACHE = CacheBuilder.newBuilder()
            .expireAfterAccess(5, TimeUnit.MINUTES)
            .removalListener((RemovalListener<UUID, BreastPhysics[]>) notification -> {
                if (notification.getValue() != null) {
                    System.out.println("[WFG] Physics cleaned up for player: " + notification.getKey());
                }
            })
            .build(new CacheLoader<UUID, BreastPhysics[]>() {
                @Override
                public BreastPhysics[] load(UUID key) {
                    return new BreastPhysics[]{new BreastPhysics(), new BreastPhysics()};
                }
            });

    // Cache model renderers keyed by UV layout hash + inflate
    private final ConcurrentHashMap<Integer, ModelRenderer> boxCache = new ConcurrentHashMap<>();

    public GenderLayer(RenderPlayer renderPlayer) {
        this.renderPlayer = renderPlayer;
    }

    public static void ensureRegisteredForPlayer(AbstractClientPlayer player) {
        if (player == null) return;
        getPhysicsForPlayer(player);
    }

    public static void unregister(UUID playerId) {
        if (playerId != null) {
            PHYSICS_CACHE.invalidate(playerId);
        }
    }

    @Override
    public void doRenderLayer(AbstractClientPlayer player, float limbSwing, float limbSwingAmount,
                              float partialTicks, float ageInTicks, float headYaw, float headPitch, float scale) {

        if (player == null) return;
        if (player.isInvisible()) return; // Respect invisibility

        if (!shouldRenderBreasts(player)) return;

        EntityConfig entityCfg = EntityConfig.getEntity(player);
        if (entityCfg == null) return;

        boolean isFake = player.getEntityData().getBoolean("WFG_FakeGUIPlayer");
        GenderConfig.PlayerGenderSettings cfg = isFake ? GenderConfig.getStaticFakeCreditsSettings() : GenderConfig.getPlayerSettings((EntityPlayer) player);

        if (cfg == null || !cfg.breastsEnabled || cfg.breastSize <= 0) return;

        float sizeFactor = MathHelper.clamp_float(cfg.breastSize / 100.0f, 0.0f, 1.0f);
        float zScale = 0.1f + (0.9f * sizeFactor);
        float torsoPush = (1.0f - sizeFactor) * 1.6f;

        float separationBase = 0.75F + (cfg.breastsCleavage / 60.0F);
        float userXOffset = cfg.breastsOffsetX;

        float baseY = 3.5F + cfg.breastsOffsetY - (cfg.height / 40.0F);
        float baseZ = cfg.breastsOffsetZ - (cfg.depth / 10.0F) - 1.5F + (MAX_PROTRUSION * sizeFactor) + torsoPush;

        BreastPhysics[] phys = isFake ? null : getPhysicsForPlayer(player);
        float renderScale = 0.0625F;
        ModelBiped model = (ModelBiped) renderPlayer.getMainModel();

        GlStateManager.pushMatrix();
        model.bipedBody.postRender(renderScale);
        if (player.isSneaking()) {
            GlStateManager.translate(0.0F, 0.12F, 0.0F);
        }

        GlStateManager.enableBlend();
        GlStateManager.enableAlpha();

        // Prepare armor config (map ItemStack -> IGenderArmor same as in WildfireEventHandler)
        ItemStack chest = null;
        try {
            chest = ((EntityPlayer) player).inventory.armorInventory[2];
        } catch (Throwable ignored) {}

        IGenderArmor armorCfg;
        if (chest == null || chest.getItem() == null) {
            armorCfg = EmptyGenderArmor.INSTANCE;
        } else {
            if (chest.getItem() == Items.leather_chestplate) {
                armorCfg = SimpleGenderArmor.LEATHER;
            } else if (chest.getItem() == Items.chainmail_chestplate) {
                armorCfg = SimpleGenderArmor.CHAINMAIL;
            } else if (chest.getItem() == Items.golden_chestplate) {
                armorCfg = SimpleGenderArmor.GOLD;
            } else if (chest.getItem() == Items.iron_chestplate) {
                armorCfg = SimpleGenderArmor.IRON;
            } else if (chest.getItem() == Items.diamond_chestplate) {
                armorCfg = SimpleGenderArmor.DIAMOND;
            } else {
                armorCfg = SimpleGenderArmor.FALLBACK;
            }
        }

        // Pass user config values into physics update
        float bounceMultiplier = cfg.bounceMultiplier;
        float stiffness = cfg.stiffness;
        float damping = cfg.damping;
        float intensity = cfg.intensity;
        float momentum = cfg.momentum / 100.0f; // normalize if stored as percentage
        boolean armorOverride = cfg.overrideArmorPhysics;

        if (phys != null) {
            if (cfg.breastsUniboob) {
                phys[0].update(player, armorCfg, bounceMultiplier, stiffness, damping, intensity, momentum, armorOverride);
                phys[1].syncFrom(phys[0]);
            } else {
                phys[0].update(player, armorCfg, bounceMultiplier, stiffness, damping, intensity, momentum, armorOverride);
                phys[1].update(player, armorCfg, bounceMultiplier, stiffness, damping, intensity, momentum, armorOverride);
            }
        }

        // LEFT side
        float lPosX = 0, lPosY = 0, lBounce = 0;
        if (phys != null) {
            lPosX = interp(phys[0].getPrePositionX(), phys[0].getPositionX(), partialTicks);
            lPosY = interp(phys[0].getPrePositionY(), phys[0].getPositionY(), partialTicks);
            lBounce = interp(phys[0].getPreBounceRotation(), phys[0].getBounceRotation(), partialTicks);
        }
        float leftX = (userXOffset - separationBase) - (lPosX * 0.34f);
        float leftY = baseY + lPosY;
        renderSide(player, entityCfg.getLeftBreastUVLayout(), leftX, leftY, baseZ, lBounce, renderScale, zScale, 0.0F);
        renderSide(player, entityCfg.getLeftBreastOverlayUVLayout(), leftX, leftY, baseZ, lBounce, renderScale, zScale, 0.25F);

        // RIGHT side
        float rPosX = 0, rPosY = 0, rBounce = 0;
        if (phys != null) {
            rPosX = interp(phys[1].getPrePositionX(), phys[1].getPositionX(), partialTicks);
            rPosY = interp(phys[1].getPrePositionY(), phys[1].getPositionY(), partialTicks);
            rBounce = -interp(phys[1].getPreBounceRotation(), phys[1].getBounceRotation(), partialTicks);
        }
        float rightX = (userXOffset + separationBase) + (rPosX * 0.34f);
        float rightY = baseY + rPosY;
        renderSide(player, entityCfg.getRightBreastUVLayout(), rightX, rightY, baseZ, rBounce, renderScale, zScale, 0.0F);
        renderSide(player, entityCfg.getRightBreastOverlayUVLayout(), rightX, rightY, baseZ, rBounce, renderScale, zScale, 0.25F);

        GlStateManager.disableAlpha();
        GlStateManager.disableBlend();
        GlStateManager.popMatrix();
    }

    private void renderSide(AbstractClientPlayer player, UVLayout layout, float x, float y, float z, float bounce, float renderScale, float zScale, float inflate) {
        if (layout == null) return;
        UVQuad north = layout.get(UVDirection.NORTH);
        if (north == null) return;

        int key = layout.hashCode() * 31 + Float.valueOf(inflate).hashCode();
        ModelRenderer box = boxCache.computeIfAbsent(key, k -> {
            try {
                ModelRenderer r = new ModelRenderer((ModelBiped) renderPlayer.getMainModel(), north.x1(), north.y1());
                r.setTextureSize(64, 64);
                r.addBox(-2.0F, -2.5F, -2.0F, 4, 5, 4, inflate);
                return r;
            } catch (Throwable t) {
                System.err.println("[WFG] Failed create cached ModelRenderer: " + t.getMessage());
                return null;
            }
        });

        if (box == null) return;

        ResourceLocation armorTex = ArmorTextureHelper.getArmorTextureForPlayerUUID(player.getUniqueID());
        boolean isWearingArmor = (armorTex != null && inflate > 0);

        if (isWearingArmor) {
            renderPlayer.bindTexture(armorTex);
        } else {
            ResourceLocation tex = UVStorage.getBreastTexture(player.getUniqueID(), inflate > 0);
            renderPlayer.bindTexture(tex != null ? tex : player.getLocationSkin());
        }

        GlStateManager.pushMatrix();
        box.setRotationPoint(x, y, z);
        GlStateManager.translate(box.rotationPointX * renderScale, box.rotationPointY * renderScale, box.rotationPointZ * renderScale);
        GlStateManager.scale(1.0F, 1.0F, zScale);
        box.rotateAngleX = (float) Math.toRadians(-26.92) + bounce;
        box.render(renderScale);
        GlStateManager.popMatrix();
    }

    public static BreastPhysics[] getPhysicsForPlayer(AbstractClientPlayer player) {
        if (player == null) return null;
        return PHYSICS_CACHE.getUnchecked(player.getUniqueID());
    }

    private boolean shouldRenderBreasts(AbstractClientPlayer player) {
        if (!ClientConfig.RENDER_BREASTS) return false;
        GenderConfig.PlayerGenderSettings s = GenderConfig.getPlayerSettings((EntityPlayer) player);
        if (s == null) return false;
        if (s.hideInArmor) {
            ItemStack chest = ((EntityPlayer) player).inventory.armorInventory[2];
            if (chest != null && chest.getItem() instanceof ItemArmor) return false;
        }
        return s.breastsEnabled && !"Male".equals(s.gender);
    }

    private static float interp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    @Override
    public boolean shouldCombineTextures() { return false; }
}
