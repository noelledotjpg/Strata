package com.nikohalfkino.rubydung.level;

import com.nikohalfkino.rubydung.HitResult;
import com.nikohalfkino.rubydung.level.Tiles.BillboardTile;
import com.nikohalfkino.rubydung.level.Tiles.Tile;
import com.nikohalfkino.rubydung.player.Player;
import com.nikohalfkino.rubydung.phys.AABB;
import com.nikohalfkino.rubydung.render.Frustum;
import com.nikohalfkino.rubydung.render.Tessellator;
import com.nikohalfkino.rubydung.render.Textures;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.lwjgl.opengl.GL11.*;

public class LevelRenderer implements LevelListener {

    private static final int CHUNK_SIZE = Chunk.SIZE;

    private final Tessellator tessellator;
    private final Level level;
    private final Chunk[] chunks;

    private final int chunkAmountX;
    private final int chunkAmountY;
    private final int chunkAmountZ;

    public LevelRenderer(Level level) {
        level.addListener(this);

        this.tessellator = new Tessellator();
        this.level = level;

        this.chunkAmountX = level.width / CHUNK_SIZE;
        this.chunkAmountY = level.depth / CHUNK_SIZE;
        this.chunkAmountZ = level.height / CHUNK_SIZE;

        this.chunks = new Chunk[this.chunkAmountX * this.chunkAmountY * this.chunkAmountZ];

        for (int x = 0; x < this.chunkAmountX; x++) {
            for (int y = 0; y < this.chunkAmountY; y++) {
                for (int z = 0; z < this.chunkAmountZ; z++) {
                    int minChunkX = x * CHUNK_SIZE;
                    int minChunkY = y * CHUNK_SIZE;
                    int minChunkZ = z * CHUNK_SIZE;

                    int maxChunkX = (x + 1) * CHUNK_SIZE;
                    int maxChunkY = (y + 1) * CHUNK_SIZE;
                    int maxChunkZ = (z + 1) * CHUNK_SIZE;

                    maxChunkX = Math.min(level.width, maxChunkX);
                    maxChunkY = Math.min(level.depth, maxChunkY);
                    maxChunkZ = Math.min(level.height, maxChunkZ);

                    Chunk chunk = new Chunk(level, minChunkX, minChunkY, minChunkZ, maxChunkX, maxChunkY, maxChunkZ);
                    this.chunks[(x + y * this.chunkAmountX) * this.chunkAmountZ + z] = chunk;
                }
            }
        }
    }

    public void render(int layer) {
        Textures.enableAlphaTest();

        Frustum frustum = Frustum.getFrustum();

        Chunk.rebuiltThisFrame = 0;

        for (Chunk chunk : this.chunks) {

            if (frustum.cubeInFrustum(chunk.boundingBox)) {

                chunk.render(layer);
            }
        }
    }

    private int buildCursor = 0;

    public boolean buildNextChunks(int maxChunks) {
        int built = 0;
        while (built < maxChunks && this.buildCursor < this.chunks.length) {
            Chunk chunk = this.chunks[this.buildCursor++];
            if (chunk.isDirty()) {
                chunk.rebuildNow();
                built++;
            }
        }
        return this.buildCursor < this.chunks.length;
    }

    public float getBuildProgress() {
        if (this.chunks.length == 0) {
            return 1.0F;
        }
        return (float) this.buildCursor / (float) this.chunks.length;
    }

    public void setDirty(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        minX /= CHUNK_SIZE;
        minY /= CHUNK_SIZE;
        minZ /= CHUNK_SIZE;
        maxX /= CHUNK_SIZE;
        maxY /= CHUNK_SIZE;
        maxZ /= CHUNK_SIZE;

        minX = Math.max(minX, 0);
        minY = Math.max(minY, 0);
        minZ = Math.max(minZ, 0);

        maxX = Math.min(maxX, this.chunkAmountX - 1);
        maxY = Math.min(maxY, this.chunkAmountY - 1);
        maxZ = Math.min(maxZ, this.chunkAmountZ - 1);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Chunk chunk = this.chunks[(x + y * this.chunkAmountX) * this.chunkAmountZ + z];

                    chunk.setDirty();
                }
            }
        }
    }

    private static final float MAX_BILLBOARD_OVERHANG = 1.0F;

    public void renderBillboards(float cameraYaw, float cameraPitch) {
        renderBillboards(cameraYaw, cameraPitch, Double.NaN, Double.NaN, Double.NaN);
    }

    public void renderBillboards(float cameraYaw, float cameraPitch, double eyeX, double eyeY, double eyeZ) {
        boolean scaleByDistance = !Double.isNaN(eyeX);

        Frustum frustum = Frustum.getFrustum();

        List<int[]> visibleBillboards = new ArrayList<>();

        float maxBillboardHeight = 4.0F;
        float maxScaleFactor = 2.0F;
        float maxOverhang = Math.max(MAX_BILLBOARD_OVERHANG, (maxBillboardHeight * maxScaleFactor) / 2.0F);

        for (Chunk chunk : this.chunks) {
            AABB billboardCullBox = chunk.boundingBox.grow(maxOverhang, maxBillboardHeight * maxScaleFactor, maxOverhang);
            if (!frustum.cubeInFrustum(billboardCullBox)) {
                continue;
            }

            for (int[] pos : chunk.getBillboardPositions()) {
                int x = pos[0], y = pos[1], z = pos[2];
                Tile tile = Tile.byId(this.level.getTile(x, y, z));
                if (tile == null || !tile.isBillboard()) {
                    continue;
                }
                visibleBillboards.add(pos);
            }
        }

        if (visibleBillboards.isEmpty()) {
            return;
        }

        float yawRad = (float) Math.toRadians(cameraYaw);
        float pitchRad = (float) Math.toRadians(cameraPitch);
        final float forwardX = (float) (Math.sin(yawRad) * Math.cos(pitchRad));
        final float forwardY = (float) -Math.sin(pitchRad);
        final float forwardZ = (float) -(Math.cos(yawRad) * Math.cos(pitchRad));

        visibleBillboards.sort(new Comparator<int[]>() {
            @Override
            public int compare(int[] a, int[] b) {
                float depthA = (a[0] + 0.5F) * forwardX + (float) a[1] * forwardY + (a[2] + 0.5F) * forwardZ;
                float depthB = (b[0] + 0.5F) * forwardX + (float) b[1] * forwardY + (b[2] + 0.5F) * forwardZ;

                return Float.compare(depthB, depthA);
            }
        });

        glEnable(GL_TEXTURE_2D);
        glDisable(GL_FOG);

        glEnable(GL_ALPHA_TEST);
        glAlphaFunc(GL_GREATER, 0.01F);

        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        glDepthMask(false);

        Textures.bind(Chunk.getAtlasTexture());

        float rotRad = (float) Math.toRadians(-cameraYaw);
        float cosYaw = (float) Math.cos(rotRad);
        float sinYaw = (float) Math.sin(rotRad);

        this.tessellator.init();
        for (int[] pos : visibleBillboards) {
            int x = pos[0], y = pos[1], z = pos[2];
            Tile tile = Tile.byId(this.level.getTile(x, y, z));
            if (!(tile instanceof BillboardTile)) {
                continue;
            }
            BillboardTile billboardTile = (BillboardTile) tile;

            float worldX = x + 0.5F;
            float worldY = y;
            float worldZ = z + 0.5F;

            float scale = 1.0F;
            if (scaleByDistance) {
                double dx = eyeX - (x + 0.5);
                double dy = eyeY - y;
                double dz = eyeZ - (z + 0.5);
                float distance = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                scale = BillboardTile.getDistanceScale(distance);
            }

            billboardTile.renderBillboardBatched(this.tessellator, this.level, x, y, z, Chunk.getTotalAtlasSlots(),
                    worldX, worldY, worldZ, cosYaw, sinYaw, scale);
        }
        this.tessellator.flush();

        glDepthMask(true);
        glDisable(GL_BLEND);

        glEnable(GL_ALPHA_TEST);
        glAlphaFunc(GL_GREATER, 0.1F);

        glDisable(GL_FOG);
        glDisable(GL_TEXTURE_2D);
    }
    public void renderHit(HitResult hitResult) {
        Tile tile = Tile.byId(this.level.getTile(hitResult.x, hitResult.y, hitResult.z));
        if (tile == null || tile == Tile.AIR) {
            return;
        }

        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_CURRENT_BIT);
        glColor4f(1.0f, 1.0f, 1.0f, (float) Math.sin(System.currentTimeMillis() / 100.0) * 0.2f + 0.4f);

        this.tessellator.init();

        tile.renderFace(this.tessellator, hitResult.x, hitResult.y, hitResult.z, hitResult.face);

        this.tessellator.flush();

        glDisable(GL_BLEND);
    }

    public void dispose() {
        if (this.chunks != null) {
            for (Chunk chunk : this.chunks) {
                if (chunk != null) {
                    chunk.dispose();
                }
            }
        }
    }

    @Override
    public void lightColumnChanged(int x, int z, int minY, int maxY) {
        setDirty(x - 1, minY - 1, z - 1, x + 1, maxY + 1, z + 1);
    }

    @Override
    public void tileChanged(int x, int y, int z) {
        setDirty(x - 1, y - 1, z - 1, x + 1, y + 1, z + 1);
    }

    @Override
    public void allChanged() {
        setDirty(0, 0, 0, this.level.width, this.level.depth, this.level.height);
    }
}
