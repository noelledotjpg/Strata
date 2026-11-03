package com.nikohalfkino.rubydung.level;

import com.nikohalfkino.rubydung.level.tiles.LiquidTile;

/**
 * how much liquid lies over a terrain face, packed into the vertex alpha for TerrainShader (keep the two in sync).
 *
 * the depth is the liquid above the cell in front of the face: its own fill plus every liquid cell stacked directly over it, in eighths
 * of a block (LiquidLayer.MAX_FILL per block). the liquid's own colour and opacity tint and darken the face, so the deeper the water, the darker the sea floor
 *
 * packed = (liquidIndex * 256 + eighths) / 2048, and exactly 1.0 means "no liquid", which is what every other vertex already writes
 */
public final class UnderwaterTint {

    /** the alpha of a face that isn't under liquid */
    public static final float DRY = 1.0F;

    private static final int MAX_EIGHTHS = 255;
    private static final float PACK_RANGE = 2048.0F;

    private UnderwaterTint() {
    }

    /** the alpha for a face whose open side is the cell (x, y, z) */
    public static float pack(Level level, int x, int y, int z) {
        LiquidLayer liquids = level.getLiquids();
        LiquidTile liquid = liquids.get(x, y, z);
        if (liquid == null) {
            return DRY;
        }
        int eighths = 0;
        for (int cy = y; cy < level.depth && eighths < MAX_EIGHTHS; cy++) {
            if (liquids.get(x, cy, z) != liquid) {
                break;
            }
            eighths += liquids.getFill(x, cy, z);
        }
        eighths = Math.max(1, Math.min(MAX_EIGHTHS, eighths));
        return (liquid.getLiquidIndex() * 256 + eighths) / PACK_RANGE;
    }
}
