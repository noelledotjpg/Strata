package com.nikohalfkino.rubydung.level;

import com.nikohalfkino.rubydung.player.HitResult;
import com.nikohalfkino.rubydung.level.tiles.BillboardTile;
import com.nikohalfkino.rubydung.level.tiles.LiquidTile;
import com.nikohalfkino.rubydung.level.tiles.Tile;
import com.nikohalfkino.rubydung.phys.AABB;
import com.nikohalfkino.rubydung.render.Frustum;
import com.nikohalfkino.rubydung.render.LiquidShader;
import com.nikohalfkino.rubydung.render.Tessellator;
import com.nikohalfkino.rubydung.render.TerrainShader;
import com.nikohalfkino.rubydung.render.Textures;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

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

        // After the chunks exist: creating the first Chunk builds the texture atlas, and billboard
        // sprite sizes come from the atlas textures.
        this.maxBillboardSize = findMaxBillboardSize();
    }

    public void render(int layer) {
        Textures.enableAlphaTest();

        Frustum frustum = Frustum.getFrustum();

        Chunk.rebuiltThisFrame = 0;

        // Time of day is applied here, at draw time, so chunk meshes never go stale when it changes.
        TerrainShader.bind(this.level.getSkyAmbient());
        try {
            for (Chunk chunk : this.chunks) {
                if (frustum.cubeInFrustum(chunk.boundingBox)) {
                    chunk.render(layer);
                }
            }
        } finally {
            TerrainShader.unbind();
        }
    }

    /**
     * draws the liquids of every visible chunk, after the opaque terrain (and before the billboards)
     *
     * blended and double sided (you see the surface from below too). liquid faces are translucent and come in no
     * particular order, so it is done in two passes: first only the depth of all liquid faces, then the colour
     * where a face is the nearest one. every pixel is blended exactly once, with the surface you actually see,
     * whatever the draw order was (no faces vanishing behind others, no darker double layers).
     * state is put back as it was found
     */
    public void renderLiquids() {
        Frustum frustum = Frustum.getFrustum();

        boolean blend = glIsEnabled(GL_BLEND);
        boolean alphaTest = glIsEnabled(GL_ALPHA_TEST);
        boolean cull = glIsEnabled(GL_CULL_FACE);
        boolean depthMask = glGetBoolean(GL_DEPTH_WRITEMASK);
        int depthFunc = glGetInteger(GL_DEPTH_FUNC);

        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDisable(GL_ALPHA_TEST);
        glDisable(GL_CULL_FACE);

        LiquidShader.bind(this.level.getSkyAmbient());
        try {
            // pass 1: depth only
            glDepthMask(true);
            glColorMask(false, false, false, false);
            drawLiquidChunks(frustum);

            // pass 2: colour, only where a liquid face is the nearest one
            glColorMask(true, true, true, true);
            glDepthMask(false);
            glDepthFunc(GL_LEQUAL);
            drawLiquidChunks(frustum);
        } finally {
            glColorMask(true, true, true, true);
            glDepthFunc(depthFunc);
            glDepthMask(depthMask);
            LiquidShader.unbind();
            if (cull) {
                glEnable(GL_CULL_FACE);
            }
            if (alphaTest) {
                glEnable(GL_ALPHA_TEST);
            }
            if (!blend) {
                glDisable(GL_BLEND);
            }
        }
    }

    private void drawLiquidChunks(Frustum frustum) {
        for (Chunk chunk : this.chunks) {
            if (chunk.hasLiquid() && frustum.cubeInFrustum(chunk.boundingBox)) {
                chunk.render(Chunk.LAYER_LIQUID);
            }
        }
    }

    private int buildCursor = 0;

    // single threaded build, still used when nothing started the parallel one
    public boolean buildNextChunks(int maxChunks) {
        if (this.parallel) {
            return uploadBuiltChunks(maxChunks, Long.MAX_VALUE);
        }
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

    // ---- parallel initial build ----
    // workers make the vertex data (no GL involved), the GL thread only compiles the display lists from it

    private static final int MAX_FINISHED_WAITING = 256;   // caps how many finished meshes wait for the GL thread (memory)

    private static final class Result {
        final Chunk chunk;
        final Chunk.Built built;   // null if the worker failed, the GL thread then builds it itself

        Result(Chunk chunk, Chunk.Built built) {
            this.chunk = chunk;
            this.built = built;
        }
    }

    private boolean parallel = false;
    private volatile boolean cancelled = false;
    private int[] pending;                     // indices of the chunks that need building
    private int uploaded = 0;
    private final AtomicInteger claimed = new AtomicInteger();
    private final Semaphore waitingSlots = new Semaphore(MAX_FINISHED_WAITING);
    private final ConcurrentLinkedQueue<Result> finished = new ConcurrentLinkedQueue<>();

    /**
     * starts building every dirty chunk on worker threads. keep calling {@link #uploadBuiltChunks} on the GL thread until it returns false.
     * the level must not change until then
     */
    public void startParallelBuild() {
        int count = 0;
        int[] list = new int[this.chunks.length];
        for (int i = 0; i < this.chunks.length; i++) {
            if (this.chunks[i].isDirty()) {
                list[count++] = i;
            }
        }
        this.pending = java.util.Arrays.copyOf(list, count);
        this.uploaded = 0;
        this.claimed.set(0);
        this.cancelled = false;
        this.parallel = true;

        int threads = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        for (int t = 0; t < threads; t++) {
            Thread worker = new Thread(this::buildWorker, "chunk-build-" + t);
            worker.setDaemon(true);
            worker.start();
        }
    }

    private void buildWorker() {
        Tessellator tessellator = new Tessellator();   // each worker needs its own, they hold state
        try {
            while (!this.cancelled) {
                this.waitingSlots.acquire();
                int slot = this.claimed.getAndIncrement();
                if (slot >= this.pending.length) {
                    this.waitingSlots.release();
                    return;
                }
                Chunk chunk = this.chunks[this.pending[slot]];
                Chunk.Built built = null;
                try {
                    built = chunk.computeAll(tessellator);
                } catch (Throwable t) {
                    System.err.println("[loading] worker failed on a chunk, building it on the main thread: " + t);
                    tessellator = new Tessellator();   // it may have been left mid-capture
                }
                this.finished.add(new Result(chunk, built));
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * GL thread: compiles display lists for chunks the workers finished, for at most maxChunks chunks or until the time is up.
     * returns true while there is more to do
     */
    public boolean uploadBuiltChunks(int maxChunks, long deadlineNanos) {
        int done = 0;
        while (done < maxChunks && System.nanoTime() < deadlineNanos) {
            Result result = this.finished.poll();
            if (result == null) {
                break;
            }
            if (result.built != null) {
                result.chunk.upload(result.built);
            } else {
                result.chunk.rebuildNow();
            }
            this.waitingSlots.release();
            this.uploaded++;
            done++;
        }
        boolean more = this.uploaded < this.pending.length;
        if (!more) {
            this.parallel = false;
            this.buildCursor = this.chunks.length;
        }
        return more;
    }

    /** stops the workers early (loading abandoned) */
    public void cancelParallelBuild() {
        this.cancelled = true;
        this.waitingSlots.release(MAX_FINISHED_WAITING);
    }

    public float getBuildProgress() {
        if (this.parallel) {
            return this.pending.length == 0 ? 1.0F : (float) this.uploaded / (float) this.pending.length;
        }
        if (this.chunks.length == 0) {
            return 1.0F;
        }
        return (float) this.buildCursor / (float) this.chunks.length;
    }

    public void setDirty(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        setDirty(minX, minY, minZ, maxX, maxY, maxZ, false);
    }

    // urgent = rebuild these chunks at the next draw instead of waiting for the per-frame budget
    private void setDirty(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, boolean urgent) {
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

                    if (urgent) {
                        chunk.setDirtyNow();
                    } else {
                        chunk.setDirty();
                    }
                }
            }
        }
    }

    private static final float MAX_BILLBOARD_OVERHANG = 1.0F;

    // largest edge of any registered billboard sprite in blocks, grows the chunk cull box so a billboard whose origin chunk is off-screen still draws. computed from the registered tiles
    private final float maxBillboardSize;

    private static float findMaxBillboardSize() {
        float max = 1.0F;
        for (Tile tile : Tile.getTiles()) {
            if (tile instanceof BillboardTile) {
                BillboardTile billboard = (BillboardTile) tile;
                max = Math.max(max, Math.max(billboard.getSpriteSize(), billboard.getHeight()));
            }
        }
        return max;
    }

    public void renderBillboards(float cameraYaw, float cameraPitch) {
        renderBillboards(cameraYaw, cameraPitch, Double.NaN, Double.NaN, Double.NaN);
    }

    public void renderBillboards(float cameraYaw, float cameraPitch, double eyeX, double eyeY, double eyeZ) {
        boolean scaleByDistance = !Double.isNaN(eyeX);

        // Remembered for the selection code (exact hit points) and the billboard blink.
        if (scaleByDistance) {
            CameraRay.set(eyeX, eyeY, eyeZ, cameraYaw, cameraPitch);
        } else {
            CameraRay.setAngles(cameraYaw, cameraPitch);
        }

        Frustum frustum = Frustum.getFrustum();

        List<int[]> visibleBillboards = new ArrayList<>();

        float maxBillboardHeight = this.maxBillboardSize;
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

            // Centre of the tile's whole footprint (the offset is 0 for ordinary 1x1 billboards).
            // It is added here, in world space, so it doesn't swing around with the camera yaw.
            float worldX = x + 0.5F + billboardTile.getPivotOffsetX();
            float worldY = y - this.level.getBillboardDrop(x, y, z);   // lowered onto a slab, say
            float worldZ = z + 0.5F + billboardTile.getPivotOffsetZ();

            float scale = 1.0F;
            if (scaleByDistance) {
                double dx = eyeX - worldX;
                double dy = eyeY - worldY;
                double dz = eyeZ - worldZ;
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
        renderHit(hitResult, CameraRay.yaw());
    }

    /**
     * draws the selection overlay, following the shape that was hit: one face of a slab or stair step, the whole face of a multi-tile object, or a blink of the billboard sprite itself
     *
     * the blink needs the camera yaw, without it the billboard's box is outlined
     */
    public void renderHit(HitResult hitResult, float cameraYaw) {
        if (hitResult.liquid) {
            renderLiquidHit(hitResult);
            return;
        }
        TilePicker.refine(this.level, hitResult);

        Tile tile = Tile.byId(this.level.getTile(hitResult.x, hitResult.y, hitResult.z));
        if (hitResult.overlayId >= 0) {
            // The struck part is a tile sharing the cell, not the cell's normal tile.
            SubTile shared = this.level.getSubTiles().find(hitResult.x, hitResult.y, hitResult.z, hitResult.overlayId);
            if (shared != null) {
                tile = Tile.byId(shared.tileId);
            }
        }
        if (tile == null || tile == Tile.AIR) {
            return;
        }

        float pulse = (float) Math.sin(System.currentTimeMillis() / 100.0) * 0.2f + 0.4f;

        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_CURRENT_BIT);
        glColor4f(1.0f, 1.0f, 1.0f, pulse);

        // The overlay lies exactly in the plane of the block's own face. Pull it slightly towards the
        // camera (polygon offset, plus a tiny nudge along the face normal) and don't let it write
        // depth, so the two surfaces never fight, whatever path the chunk mesh took to the screen.
        glEnable(GL_POLYGON_OFFSET_FILL);
        glPolygonOffset(-2.0F, -4.0F);
        glDepthMask(false);

        if (tile instanceof BillboardTile && !Float.isNaN(cameraYaw)) {
            renderBillboardBlink((BillboardTile) tile, hitResult, cameraYaw, pulse);
        } else {
            renderFaceOverlay(tile, hitResult);
        }

        glDepthMask(true);
        glDisable(GL_POLYGON_OFFSET_FILL);

        glDisable(GL_BLEND);
    }

    // the targeted liquid: the hit face of its cell, up to the liquid's surface
    private void renderLiquidHit(HitResult hit) {
        LiquidTile liquid = this.level.getLiquids().get(hit.x, hit.y, hit.z);
        if (liquid == null) {
            return;
        }
        float top = liquid.getSurfaceY(this.level, hit.x, hit.y, hit.z);
        float pulse = (float) Math.sin(System.currentTimeMillis() / 100.0) * 0.2f + 0.4f;

        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_CURRENT_BIT);
        glColor4f(1.0f, 1.0f, 1.0f, pulse);
        glEnable(GL_POLYGON_OFFSET_FILL);
        glPolygonOffset(-2.0F, -4.0F);
        glDepthMask(false);

        int[] normal = HIT_FACE_NORMALS[hit.face];
        glPushMatrix();
        glTranslatef(normal[0] * HIT_FACE_NUDGE, normal[1] * HIT_FACE_NUDGE, normal[2] * HIT_FACE_NUDGE);
        this.tessellator.init();
        renderBoxFace(hit.x, hit.y, hit.z, hit.x + 1.0F, top, hit.z + 1.0F, hit.face);
        this.tessellator.flush();
        glPopMatrix();

        glDepthMask(true);
        glDisable(GL_POLYGON_OFFSET_FILL);
        glDisable(GL_BLEND);
    }

    // the hit face of whatever part of the tile was hit
    private void renderFaceOverlay(Tile tile, HitResult hitResult) {
        int[] normal = HIT_FACE_NORMALS[hitResult.face];
        glPushMatrix();
        glTranslatef(normal[0] * HIT_FACE_NUDGE, normal[1] * HIT_FACE_NUDGE, normal[2] * HIT_FACE_NUDGE);

        this.tessellator.init();

        // A multi-tile object is outlined as a whole: the hit face of its full bounds, not of one cell.
        MultiTileInstance object = this.level.getMultiTile(hitResult.x, hitResult.y, hitResult.z);
        if (object != null && !tile.hasCustomOutline()) {
            renderBoxFace(object.x, object.y, object.z,
                    object.x + object.sizeX, object.y + object.sizeY, object.z + object.sizeZ, hitResult.face);
        } else {
            // The box the ray struck (one step of a staircase, a slab), else the tile's outline box.
            // (A tile with a custom outline, like a door, ignores the single struck box: it wraps the whole shape.)
            AABB box = tile.hasCustomOutline() ? null : hitResult.hitBox;
            if (box == null) {
                box = tile.getOutlineAABB(this.level, hitResult.x, hitResult.y, hitResult.z);
            }
            if (box != null) {
                renderBoxFace((float) box.minX, (float) box.minY, (float) box.minZ,
                        (float) box.maxX, (float) box.maxY, (float) box.maxZ, hitResult.face);
            } else {
                tile.renderFace(this.tessellator, hitResult.x, hitResult.y, hitResult.z, hitResult.face);
            }
        }

        this.tessellator.flush();
        glPopMatrix();
    }

    // redraws the sprite (same quad, same place) additively so it blinks in its own shape
    private void renderBillboardBlink(BillboardTile billboard, HitResult hitResult, float cameraYaw, float pulse) {
        glEnable(GL_TEXTURE_2D);
        glEnable(GL_ALPHA_TEST);
        glAlphaFunc(GL_GREATER, 0.01F);
        Textures.bind(Chunk.getAtlasTexture());

        float rotRad = (float) Math.toRadians(-cameraYaw);
        float cosYaw = (float) Math.cos(rotRad);
        float sinYaw = (float) Math.sin(rotRad);

        // Same placement as renderBillboards (the hit position is the object's origin cell).
        float worldX = hitResult.x + 0.5F + billboard.getPivotOffsetX();
        float worldY = hitResult.y - this.level.getBillboardDrop(hitResult.x, hitResult.y, hitResult.z);
        float worldZ = hitResult.z + 0.5F + billboard.getPivotOffsetZ();

        this.tessellator.init();
        billboard.renderBillboardBatched(this.tessellator, this.level, hitResult.x, hitResult.y, hitResult.z,
                Chunk.getTotalAtlasSlots(), worldX, worldY, worldZ, cosYaw, sinYaw, 1.0F, 1.0F, pulse);
        this.tessellator.flush();

        glAlphaFunc(GL_GREATER, 0.1F);
        glDisable(GL_TEXTURE_2D);
    }

    // outward normal per face, numbered like Tile.FACE_*
    private static final int[][] HIT_FACE_NORMALS = {
            {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}
    };

    // how far (in blocks) the selection overlay floats off the face it highlights
    private static final float HIT_FACE_NUDGE = 0.002F;

    // one face of an axis-aligned box, wound like Tile.renderFace
    private void renderBoxFace(float minX, float minY, float minZ, float maxX, float maxY, float maxZ, int face) {
        if (face == Tile.FACE_BOTTOM) {
            this.tessellator.vertex(minX, minY, maxZ);
            this.tessellator.vertex(minX, minY, minZ);
            this.tessellator.vertex(maxX, minY, minZ);
            this.tessellator.vertex(maxX, minY, maxZ);
        } else if (face == Tile.FACE_TOP) {
            this.tessellator.vertex(maxX, maxY, maxZ);
            this.tessellator.vertex(maxX, maxY, minZ);
            this.tessellator.vertex(minX, maxY, minZ);
            this.tessellator.vertex(minX, maxY, maxZ);
        } else if (face == Tile.FACE_NORTH) {
            this.tessellator.vertex(minX, maxY, minZ);
            this.tessellator.vertex(maxX, maxY, minZ);
            this.tessellator.vertex(maxX, minY, minZ);
            this.tessellator.vertex(minX, minY, minZ);
        } else if (face == Tile.FACE_SOUTH) {
            this.tessellator.vertex(minX, maxY, maxZ);
            this.tessellator.vertex(minX, minY, maxZ);
            this.tessellator.vertex(maxX, minY, maxZ);
            this.tessellator.vertex(maxX, maxY, maxZ);
        } else if (face == Tile.FACE_WEST) {
            this.tessellator.vertex(minX, maxY, maxZ);
            this.tessellator.vertex(minX, maxY, minZ);
            this.tessellator.vertex(minX, minY, minZ);
            this.tessellator.vertex(minX, minY, maxZ);
        } else if (face == Tile.FACE_EAST) {
            this.tessellator.vertex(maxX, minY, maxZ);
            this.tessellator.vertex(maxX, minY, minZ);
            this.tessellator.vertex(maxX, maxY, minZ);
            this.tessellator.vertex(maxX, maxY, maxZ);
        }
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
        // the block itself and the faces next to it change right now, only light is allowed to lag
        // two cells vertically: a stack of metal bars picks its texture slice from up to two neighbours (see PaneTile), so a change two cells away can alter this cell's look
        setDirty(x - 1, y - 2, z - 1, x + 1, y + 2, z + 1, true);
    }

    @Override
    public void allChanged() {
        setDirty(0, 0, 0, this.level.width, this.level.depth, this.level.height);
    }
}