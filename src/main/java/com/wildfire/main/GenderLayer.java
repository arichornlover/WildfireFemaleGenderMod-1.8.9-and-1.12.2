package com.wildfire.main.render;

import com.wildfire.main.uvs.UVLayout;
import com.wildfire.main.uvs.UVQuad;
import com.wildfire.main.uvs.UVDirection;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.opengl.GL11;

/**
 * Updated DynamicBreastBox:
 *  - Added robust UV validation and clamping to avoid UV-related crashes
 *  - Logs malformed UV cases instead of throwing
 *  - Keeps constants for TEX_W/TEX_H but defends against invalid quads
 */
public class DynamicBreastBox {
    private final float x;
    private final float y;
    private final float z;
    private final int dx;
    private final int dy;
    private final int dz;
    private final float delta;
    private UVLayout uvLayout;

    public float rotationPointX = 0f;
    public float rotationPointY = 0f;
    public float rotationPointZ = 0f;

    private static final float TEX_W = 64.0f;
    private static final float TEX_H = 64.0f;

    public DynamicBreastBox(float x, float y, float z, int dx, int dy, int dz, float delta, UVLayout uvLayout) {
        this.x = x - delta;
        this.y = y - delta;
        this.z = z - delta;
        this.dx = dx + (int) (delta * 2);
        this.dy = dy + (int) (delta * 2);
        this.dz = dz + (int) (delta * 2);
        this.delta = delta;
        this.uvLayout = uvLayout;
    }

    public void setUVLayout(UVLayout layout) {
        this.uvLayout = layout;
    }

    public void setRotationPoint(float rx, float ry, float rz) {
        this.rotationPointX = rx;
        this.rotationPointY = ry;
        this.rotationPointZ = rz;
    }

    public void render(float renderScale) {
        if (uvLayout == null) return;

        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();

        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_NORMAL);

        float x1 = x;
        float y1 = y;
        float z1 = z;
        float x2 = x + dx;
        float y2 = y + dy;
        float z2 = z + dz;

        // EAST
        drawFaceSafe(wr, UVDirection.EAST,
                x2, y1, z1,
                x2, y2, z1,
                x2, y2, z2,
                x2, y1, z2,
                1f, 0f, 0f, renderScale);

        // WEST
        drawFaceSafe(wr, UVDirection.WEST,
                x1, y1, z2,
                x1, y2, z2,
                x1, y2, z1,
                x1, y1, z1,
                -1f, 0f, 0f, renderScale);

        // DOWN
        drawFaceSafe(wr, UVDirection.DOWN,
                x1, y1, z1,
                x2, y1, z1,
                x2, y1, z2,
                x1, y1, z2,
                0f, -1f, 0f, renderScale);

        // UP
        drawFaceSafe(wr, UVDirection.UP,
                x1, y2, z2,
                x2, y2, z2,
                x2, y2, z1,
                x1, y2, z1,
                0f, 1f, 0f, renderScale);

        // NORTH (front)
        drawFaceSafe(wr, UVDirection.NORTH,
                x2, y1, z1,
                x1, y1, z1,
                x1, y2, z1,
                x2, y2, z1,
                0f, 0f, -1f, renderScale);

        tess.draw();
    }

    /**
     * Wrapper that validates UVQuad before delegating to drawFace.
     */
    private void drawFaceSafe(WorldRenderer wr, UVDirection dir,
                              double vx0, double vy0, double vz0,
                              double vx1, double vy1, double vz1,
                              double vx2, double vy2, double vz2,
                              double vx3, double vy3, double vz3,
                              float nx, float ny, float nz, float renderScale) {
        if (uvLayout == null) return;
        UVQuad quad = uvLayout.get(dir);
        if (quad == null) return;

        // Validate proper min/max
        if (quad.x2() < quad.x1() || quad.y2() < quad.y1()) {
            System.err.println("[WFG] Skipping face for " + dir + ": inverted UV quad (" + quad.x1() + "," + quad.y1() + " -> " + quad.x2() + "," + quad.y2() + ")");
            return;
        }

        // Ensure UVs in range [0, TEX_W-1] / [0, TEX_H-1]
        if (quad.x1() < 0 || quad.y1() < 0 || quad.x2() < 0 || quad.y2() < 0
                || quad.x1() > TEX_W - 1 || quad.x2() > TEX_W - 1 || quad.y1() > TEX_H - 1 || quad.y2() > TEX_H - 1) {
            System.err.println("[WFG] Skipping face for " + dir + ": out-of-range UV quad (" + quad.x1() + "," + quad.y1() + " -> " + quad.x2() + "," + quad.y2() + ")");
            return;
        }

        // If the quad is all zeros (possible default/malformed), skip
        if (quad.x1() == 0 && quad.y1() == 0 && quad.x2() == 0 && quad.y2() == 0) {
            // harmless, skip drawing this face to avoid texture artifacts/crashes
            return;
        }

        try {
            drawFace(wr, dir, vx0, vy0, vz0, vx1, vy1, vz1, vx2, vy2, vz2, vx3, vy3, vz3, nx, ny, nz, renderScale, quad);
        } catch (Throwable t) {
            System.err.println("[WFG] Exception drawing face " + dir + ": " + t.getMessage());
        }
    }

    /**
     * Core face drawing. Assumes validation is already done.
     */
    private void drawFace(WorldRenderer wr, UVDirection dir,
                          double vx0, double vy0, double vz0,
                          double vx1, double vy1, double vz1,
                          double vx2, double vy2, double vz2,
                          double vx3, double vy3, double vz3,
                          float nx, float ny, float nz, float renderScale, UVQuad quad) {

        // compute normalized u/v coordinates, include +1 pixel to match previous behavior
        double u1 = (double) quad.x1() / TEX_W;
        double v1 = (double) quad.y1() / TEX_H;
        double u2 = (double) (quad.x2() + 1) / TEX_W;
        double v2 = (double) (quad.y2() + 1) / TEX_H;

        wr.pos(vx0 * renderScale, vy0 * renderScale, vz0 * renderScale).tex(u2, v2).normal(nx, ny, nz).endVertex();
        wr.pos(vx1 * renderScale, vy1 * renderScale, vz1 * renderScale).tex(u1, v2).normal(nx, ny, nz).endVertex();
        wr.pos(vx2 * renderScale, vy2 * renderScale, vz2 * renderScale).tex(u1, v1).normal(nx, ny, nz).endVertex();
        wr.pos(vx3 * renderScale, vy3 * renderScale, vz3 * renderScale).tex(u2, v1).normal(nx, ny, nz).endVertex();
    }
}
