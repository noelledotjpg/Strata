package com.nikohalfkino.rubydung.level;

import com.nikohalfkino.rubydung.player.HitResult;
import com.nikohalfkino.rubydung.level.tiles.BillboardTile;
import com.nikohalfkino.rubydung.level.tiles.LiquidTile;
import com.nikohalfkino.rubydung.level.tiles.Tile;
import com.nikohalfkino.rubydung.phys.AABB;
import com.nikohalfkino.rubydung.render.*;
import com.nikohalfkino.rubydung.util.GameSettings;

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

        // rounded up, so a world whose size isn't a multiple of CHUNK_SIZE still has a (smaller) chunk on its edge
        this.chunkAmountX = (level.width + CHUNK_SIZE - 1) / CHUNK_SIZE;
        this.chunkAmountY = (level.depth + CHUNK_SIZE - 1) / CHUNK_SIZE;
        this.chunkAmountZ = (level.height + CHUNK_SIZE - 1) / CHUNK_SIZE;

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

        this.maxBillboardSize = findMaxBillboardSize();
    }

    private double viewX = Double.NaN;
    private double viewZ = Double.NaN;

    public void setViewPosition(double x, double y, double z) {
        this.viewX = x;
        this.viewZ = z;
    }

    private final List<Chunk> visibleChunks = new ArrayList<>();
    private boolean visibleChunksValid = false;

    private boolean withinRenderDistance(AABB box, double extra) {
        int distance = GameSettings.getRenderDistance();
        if (distance <= 0 || Double.isNaN(this.viewX)) {
            return true;
        }
        double reach = distance * (double) CHUNK_SIZE + extra;
        double dx = Math.max(Math.max(box.minX - this.viewX, this.viewX - box.maxX), 0.0);
        double dz = Math.max(Math.max(box.minZ - this.viewZ, this.viewZ - box.maxZ), 0.0);
        return dx * dx + dz * dz <= reach * reach;
    }

    private void collectVisibleChunks() {
        Frustum frustum = Frustum.getFrustum();
        this.visibleChunks.clear();
        for (Chunk chunk : this.chunks) {
            if (!chunk.isDirty() && !chunk.hasGeometry()) {
                continue;
            }
            if (withinRenderDistance(chunk.boundingBox, 0.0) && frustum.cubeInFrustum(chunk.boundingBox)) {
                this.visibleChunks.add(chunk);
            }
        }
        this.visibleChunksValid = true;
    }

    public void render(int layer) {
        Textures.enableAlphaTest();

        Chunk.rebuiltThisFrame = 0;

        if (layer == 0 || !this.visibleChunksValid) {
            collectVisibleChunks();
        }

        TerrainShader.bind(this.level.getSkyAmbient());
        try {
            for (int i = 0; i < this.visibleChunks.size(); i++) {
                Chunk chunk = this.visibleChunks.get(i);
                chunk.render(layer);
            }
        } finally {
            TerrainShader.unbind();
        }
    }

    public void renderLiquids() {
        if (!this.visibleChunksValid) {
            collectVisibleChunks();
        }

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
            glDepthMask(true);
            glColorMask(false, false, false, false);
            drawLiquidChunks();

            glColorMask(true, true, true, true);
            glDepthMask(false);
            glDepthFunc(GL_LEQUAL);
            drawLiquidChunks();
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

    private void drawLiquidChunks() {
        for (int i = 0; i < this.visibleChunks.size(); i++) {
            Chunk chunk = this.visibleChunks.get(i);
            if (chunk.hasLiquid()) {
                chunk.render(Chunk.LAYER_LIQUID);
            }
        }
    }

    private int buildCursor = 0;

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

    private static final int MAX_FINISHED_WAITING = 256;

    private static final class Result {
        final Chunk chunk;
        final Chunk.Built built;

        Result(Chunk chunk, Chunk.Built built) {
            this.chunk = chunk;
            this.built = built;
        }
    }

    private boolean parallel = false;
    private volatile boolean cancelled = false;
    // bumped by every startParallelBuild: workers of an earlier build notice it and stop instead of carrying on into the new one
    private volatile int buildGeneration = 0;
    private int[] pending;
    private int uploaded = 0;
    private final AtomicInteger claimed = new AtomicInteger();
    private final Semaphore waitingSlots = new Semaphore(MAX_FINISHED_WAITING);
    private final ConcurrentLinkedQueue<Result> finished = new ConcurrentLinkedQueue<>();

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
        this.finished.clear();
        this.waitingSlots.drainPermits();
        this.waitingSlots.release(MAX_FINISHED_WAITING);
        int generation = ++this.buildGeneration;
        this.cancelled = false;
        this.parallel = true;

        // leave a core for the render thread (and one more for the OS / GC), otherwise frames stall while chunks build
        int threads = Math.max(1, Runtime.getRuntime().availableProcessors() - 2);
        for (int t = 0; t < threads; t++) {
            Thread worker = new Thread(() -> buildWorker(generation), "chunk-build-" + t);
            worker.setDaemon(true);
            worker.setPriority(Thread.MIN_PRIORITY);
            worker.start();
        }
    }

    private void buildWorker(int generation) {
        Tessellator tessellator = new Tessellator();
        try {
            while (!this.cancelled && generation == this.buildGeneration) {
                this.waitingSlots.acquire();
                if (this.cancelled || generation != this.buildGeneration) {
                    this.waitingSlots.release();
                    return;
                }
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
                    tessellator = new Tessellator();
                }
                this.finished.add(new Result(chunk, built));
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

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
            if (chunk.getBillboardPositions().isEmpty() || !withinRenderDistance(chunk.boundingBox, maxOverhang)) {
                continue;
            }
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
                float depthA = (a[0] + 0.5F) * forwardX + (a[1] + 0.5F) * forwardY + (a[2] + 0.5F) * forwardZ;
                float depthB = (b[0] + 0.5F) * forwardX + (b[1] + 0.5F) * forwardY + (b[2] + 0.5F) * forwardZ;
                return Float.compare(depthB, depthA);
            }
        });

        BillboardShader.bind();
        try {
            glEnable(GL_TEXTURE_2D);
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

                float worldX = x + 0.5F + billboardTile.getPivotOffsetX();
                float worldY = y - this.level.getBillboardDrop(x, y, z);
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
        } finally {
            BillboardShader.unbind();
            glDisable(GL_TEXTURE_2D);
        }
    }

    public void renderHit(HitResult hitResult) {
        renderHit(hitResult, CameraRay.yaw());
    }

    public void renderHit(HitResult hitResult, float cameraYaw) {
        if (hitResult.liquid) {
            renderLiquidHit(hitResult);
            return;
        }
        TilePicker.refine(this.level, hitResult);

        Tile tile = Tile.byId(this.level.getTile(hitResult.x, hitResult.y, hitResult.z));
        if (hitResult.overlayId >= 0) {
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

    private void renderFaceOverlay(Tile tile, HitResult hitResult) {
        int[] normal = HIT_FACE_NORMALS[hitResult.face];
        glPushMatrix();
        glTranslatef(normal[0] * HIT_FACE_NUDGE, normal[1] * HIT_FACE_NUDGE, normal[2] * HIT_FACE_NUDGE);

        this.tessellator.init();

        MultiTileInstance object = this.level.getMultiTile(hitResult.x, hitResult.y, hitResult.z);
        if (object != null && !tile.hasCustomOutline()) {
            renderBoxFace(object.x, object.y, object.z,
                    object.x + object.sizeX, object.y + object.sizeY, object.z + object.sizeZ, hitResult.face);
        } else {
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

    private void renderBillboardBlink(BillboardTile billboard, HitResult hitResult, float cameraYaw, float pulse) {
        glEnable(GL_TEXTURE_2D);
        glEnable(GL_ALPHA_TEST);
        glAlphaFunc(GL_GREATER, 0.01F);
        Textures.bind(Chunk.getAtlasTexture());

        float rotRad = (float) Math.toRadians(-cameraYaw);
        float cosYaw = (float) Math.cos(rotRad);
        float sinYaw = (float) Math.sin(rotRad);

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

    private static final int[][] HIT_FACE_NORMALS = {
            {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}
    };

    private static final float HIT_FACE_NUDGE = 0.002F;

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
        setDirty(x - 1, y - 2, z - 1, x + 1, y + 2, z + 1, true);
    }

    @Override
    public void allChanged() {
        setDirty(0, 0, 0, this.level.width, this.level.depth, this.level.height);
    }
}