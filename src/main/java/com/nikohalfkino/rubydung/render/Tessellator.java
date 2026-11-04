package com.nikohalfkino.rubydung.render;

import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL11.*;

public class Tessellator {

    private static final int MAX_VERTICES = 100000;

    private int primitive = GL_QUADS;

    private final FloatBuffer vertexBuffer = BufferUtils.createFloatBuffer(MAX_VERTICES * 3);
    private final FloatBuffer textureCoordinateBuffer = BufferUtils.createFloatBuffer(MAX_VERTICES * 2);
    private final FloatBuffer colorBuffer = BufferUtils.createFloatBuffer(MAX_VERTICES * 4);

    private int vertices = 0;

    // added to every vertex, lets a tile be drawn shifted inside its cell (see SubTile)
    private float offsetX, offsetY, offsetZ;

    // optional vertical clip (see setClipY): quads are cut to the height range
    // flat quads on the cell's top or bottom outside it are pulled onto the cut, closing the shape
    private boolean clipActive = false;
    private float clipCellMin, clipCellMax, clipMin, clipMax;
    private final float[][] clipPos = new float[4][3];
    private final float[][] clipTex = new float[4][2];
    private final float[][] clipCol = new float[4][4];
    private int clipCount = 0;

    // icon mode (TileIconRenderer): set on the icon renderer's own tessellator only. Living here instead of in shared
    // state means the chunk workers, which each own their tessellator, can never see it
    private boolean iconFullBright = false;
    private int iconSelfFaces = 0;

    private boolean hasTexture = false;
    private float textureU;
    private float textureV;

    private boolean hasColor;
    private float red;
    private float green;
    private float blue;
    private float alpha;

    /**
     * one flushed batch of vertices, held in plain arrays instead of being drawn: it can be built on any thread
     * and turned into GL calls later on the GL thread with {@link #draw(Mesh)}
     */
    public static final class Mesh {
        final int primitive;
        final int vertices;
        final float[] positions;
        final float[] uvs;      // null if no texture coordinates were set
        final float[] colors;   // null if no colours were set

        Mesh(int primitive, int vertices, float[] positions, float[] uvs, float[] colors) {
            this.primitive = primitive;
            this.vertices = vertices;
            this.positions = positions;
            this.uvs = uvs;
            this.colors = colors;
        }
    }

    // when not null, flush() stores the batch here instead of making GL calls (no GL context needed)
    private List<Mesh> capture = null;

    /** from now on flush() records meshes instead of drawing, until endCapture() */
    public void beginCapture() {
        this.capture = new ArrayList<>();
    }

    public List<Mesh> endCapture() {
        List<Mesh> result = this.capture;
        this.capture = null;
        return result;
    }

    private static FloatBuffer replayPositions, replayUvs, replayColors;

    /** GL thread only: draws a recorded mesh (inside a display list, this compiles it into the list) */
    public static void draw(Mesh mesh) {
        if (mesh.vertices == 0) {
            return;
        }
        if (replayPositions == null) {
            replayPositions = BufferUtils.createFloatBuffer(MAX_VERTICES * 3);
            replayUvs = BufferUtils.createFloatBuffer(MAX_VERTICES * 2);
            replayColors = BufferUtils.createFloatBuffer(MAX_VERTICES * 4);
        }

        replayPositions.clear();
        replayPositions.put(mesh.positions, 0, mesh.vertices * 3);
        replayPositions.flip();
        glEnableClientState(GL_VERTEX_ARRAY);
        glVertexPointer(3, 0, replayPositions);

        if (mesh.uvs != null) {
            replayUvs.clear();
            replayUvs.put(mesh.uvs, 0, mesh.vertices * 2);
            replayUvs.flip();
            glEnableClientState(GL_TEXTURE_COORD_ARRAY);
            glTexCoordPointer(2, 0, replayUvs);
        }
        if (mesh.colors != null) {
            replayColors.clear();
            replayColors.put(mesh.colors, 0, mesh.vertices * 4);
            replayColors.flip();
            glEnableClientState(GL_COLOR_ARRAY);
            glColorPointer(4, 0, replayColors);
        }

        glDrawArrays(mesh.primitive, 0, mesh.vertices);

        glDisableClientState(GL_VERTEX_ARRAY);
        if (mesh.uvs != null) {
            glDisableClientState(GL_TEXTURE_COORD_ARRAY);
        }
        if (mesh.colors != null) {
            glDisableClientState(GL_COLOR_ARRAY);
        }
    }

    private static float[] copyOut(FloatBuffer source, int count) {
        float[] out = new float[count];
        FloatBuffer view = source.duplicate();
        view.clear();
        view.get(out, 0, count);
        return out;
    }

    private void captureBatch() {
        if (this.vertices == 0) {
            return;
        }
        float[] positions = copyOut(this.vertexBuffer, this.vertices * 3);
        float[] uvs = this.hasTexture ? copyOut(this.textureCoordinateBuffer, this.vertices * 2) : null;
        float[] colors = this.hasColor ? copyOut(this.colorBuffer, this.vertices * 4) : null;
        this.capture.add(new Mesh(this.primitive, this.vertices, positions, uvs, colors));
    }

    /** fullBright: tiles are drawn with plain face shade, no sky or block light. selfFaceMask: world faces (bit per Tile.FACE_*) the icon treats as touching the same tile, so self-cull faces there are skipped */
    public void setIconMode(boolean fullBright, int selfFaceMask) {
        this.iconFullBright = fullBright;
        this.iconSelfFaces = selfFaceMask;
    }

    public boolean isIconFullBright() {
        return this.iconFullBright;
    }

    public boolean iconTouchesSelf(int worldFace) {
        return (this.iconSelfFaces & (1 << worldFace)) != 0;
    }

    public void init() {
        init(GL_QUADS);
    }

    public void init(int primitive) {
        clear();
        this.primitive = primitive;
    }

    public void vertex(float x, float y, float z) {
        if (this.clipActive && this.primitive == GL_QUADS) {
            int i = this.clipCount++;
            this.clipPos[i][0] = x + this.offsetX;
            this.clipPos[i][1] = y + this.offsetY;
            this.clipPos[i][2] = z + this.offsetZ;
            this.clipTex[i][0] = this.textureU;
            this.clipTex[i][1] = this.textureV;
            this.clipCol[i][0] = this.red;
            this.clipCol[i][1] = this.green;
            this.clipCol[i][2] = this.blue;
            this.clipCol[i][3] = this.alpha;
            if (this.clipCount == 4) {
                this.clipCount = 0;
                emitClippedQuad();
            }
            return;
        }
        put(x + this.offsetX, y + this.offsetY, z + this.offsetZ,
                this.textureU, this.textureV, this.red, this.green, this.blue, this.alpha);
    }

    private void put(float x, float y, float z, float u, float v, float r, float g, float b, float a) {
        this.vertexBuffer.put(this.vertices * 3, x);
        this.vertexBuffer.put(this.vertices * 3 + 1, y);
        this.vertexBuffer.put(this.vertices * 3 + 2, z);

        if (this.hasTexture) {
            this.textureCoordinateBuffer.put(this.vertices * 2, u);
            this.textureCoordinateBuffer.put(this.vertices * 2 + 1, v);
        }

        if (this.hasColor) {
            this.colorBuffer.put(this.vertices * 4, r);
            this.colorBuffer.put(this.vertices * 4 + 1, g);
            this.colorBuffer.put(this.vertices * 4 + 2, b);
            this.colorBuffer.put(this.vertices * 4 + 3, a);
        }

        this.vertices++;

        if (this.vertices == MAX_VERTICES) {
            flush();
        }
    }

    /**
     * draws only the part of what follows between rangeMin and rangeMax (world heights), for a tile that fills half its cell (cellMin..cellMax is the whole cell)
     *
     * flat quads on the cell's top or bottom outside the range move onto the cut, so the half block has a lid instead of being hollow
     */
    public void setClipY(float cellMin, float cellMax, float rangeMin, float rangeMax) {
        this.clipActive = true;
        this.clipCellMin = cellMin;
        this.clipCellMax = cellMax;
        this.clipMin = rangeMin;
        this.clipMax = rangeMax;
        this.clipCount = 0;
    }

    public void clearClip() {
        this.clipActive = false;
        this.clipCount = 0;
    }

    /** the face (Tile.FACE_*: 0 bottom, 1 top) that's the lid of the clipped shape: top of a lower half, bottom of an upper half, -1 when nothing is clipped. drawn whatever the neighbour is, since it faces into the free half of the cell */
    public int getClipCapFace() {
        if (!this.clipActive) {
            return -1;
        }
        if (this.clipMax < this.clipCellMax - 1.0E-4F) {
            return 1;
        }
        return this.clipMin > this.clipCellMin + 1.0E-4F ? 0 : -1;
    }

    public boolean isClipped() {
        return this.clipActive;
    }

    // the cut plane a flat quad on the cell's top or bottom is moved to, NaN if it isn't one
    private float capPlane(float y) {
        final float e = 1.0E-4F;
        if (y < this.clipMin - e && Math.abs(y - this.clipCellMin) < e) {
            return this.clipMin;
        }
        if (y > this.clipMax + e && Math.abs(y - this.clipCellMax) < e) {
            return this.clipMax;
        }
        return Float.NaN;
    }

    private void emitClippedQuad() {
        final float e = 1.0E-4F;

        // A flat quad (all four corners at one height) outside the range: a lid, or dropped.
        boolean flat = true;
        for (int i = 1; i < 4; i++) {
            flat &= Math.abs(this.clipPos[i][1] - this.clipPos[0][1]) < e;
        }
        if (flat) {
            float y = this.clipPos[0][1];
            if (y >= this.clipMin - e && y <= this.clipMax + e) {
                for (int i = 0; i < 4; i++) {
                    putClip(this.clipPos[i], this.clipTex[i], this.clipCol[i], this.clipPos[i][1]);
                }
                return;
            }
            float cap = capPlane(y);
            if (!Float.isNaN(cap)) {
                for (int i = 0; i < 4; i++) {
                    putClip(this.clipPos[i], this.clipTex[i], this.clipCol[i], cap);
                }
            }
            return;
        }

        // Sutherland-Hodgman against y >= clipMin, then y <= clipMax. Each vertex: x y z u v r g b a.
        float[][] poly = new float[4][9];
        for (int i = 0; i < 4; i++) {
            fill(poly[i], this.clipPos[i], this.clipTex[i], this.clipCol[i]);
        }
        int n = 4;
        float[][] out = new float[8][];
        for (int pass = 0; pass < 2; pass++) {
            float bound = pass == 0 ? this.clipMin : this.clipMax;
            int m = 0;
            for (int i = 0; i < n; i++) {
                float[] a = poly[i];
                float[] b = poly[(i + 1) % n];
                boolean aIn = pass == 0 ? a[1] >= bound - e : a[1] <= bound + e;
                boolean bIn = pass == 0 ? b[1] >= bound - e : b[1] <= bound + e;
                if (aIn) {
                    out[m++] = a;
                }
                if (aIn != bIn) {
                    float t = (bound - a[1]) / (b[1] - a[1]);
                    float[] c = new float[9];
                    for (int k = 0; k < 9; k++) {
                        c[k] = a[k] + (b[k] - a[k]) * t;
                    }
                    c[1] = bound;
                    out[m++] = c;
                }
            }
            n = m;
            poly = new float[Math.max(n, 1)][];
            System.arraycopy(out, 0, poly, 0, n);
            if (n < 3) {
                return;
            }
        }

        if (n == 3) {
            n = 4;
            poly = new float[][]{poly[0], poly[1], poly[2], poly[2]};
        }
        if (n == 4) {
            for (int i = 0; i < 4; i++) {
                putPoly(poly[i]);
            }
        } else {
            for (int i = 1; i < n - 1; i++) {      // a fan of degenerate quads
                putPoly(poly[0]);
                putPoly(poly[i]);
                putPoly(poly[i + 1]);
                putPoly(poly[i + 1]);
            }
        }
    }

    private static void fill(float[] v, float[] pos, float[] tex, float[] col) {
        v[0] = pos[0]; v[1] = pos[1]; v[2] = pos[2];
        v[3] = tex[0]; v[4] = tex[1];
        v[5] = col[0]; v[6] = col[1]; v[7] = col[2]; v[8] = col[3];
    }

    private void putPoly(float[] v) {
        put(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8]);
    }

    private void putClip(float[] pos, float[] tex, float[] col, float y) {
        put(pos[0], y, pos[2], tex[0], tex[1], col[0], col[1], col[2], col[3]);
    }

    public void setOffset(float x, float y, float z) {
        this.offsetX = x;
        this.offsetY = y;
        this.offsetZ = z;
    }

    public void clearOffset() {
        this.offsetX = 0.0F;
        this.offsetY = 0.0F;
        this.offsetZ = 0.0F;
    }

    public float getOffsetX() { return this.offsetX; }
    public float getOffsetY() { return this.offsetY; }
    public float getOffsetZ() { return this.offsetZ; }

    public void texture(float textureU, float textureV) {
        this.hasTexture = true;
        this.textureU = textureU;
        this.textureV = textureV;
    }

    public void color(float red, float green, float blue) {
        color(red, green, blue, 1.0f);
    }

    public void color(float red, float green, float blue, float alpha) {
        this.hasColor = true;
        this.red = red;
        this.green = green;
        this.blue = blue;
        this.alpha = alpha;
    }

    public void flush() {
        if (this.capture != null) {
            captureBatch();
            clear();
            return;
        }

        // vertices are written with absolute puts, so the position stays 0 and flip() would set the limit to 0:
        // keep the buffers at position 0 / full limit, which is all the pointer calls look at
        this.vertexBuffer.clear();
        this.textureCoordinateBuffer.clear();
        this.colorBuffer.clear();

        glEnableClientState(GL_VERTEX_ARRAY);
        glVertexPointer(3, 0, this.vertexBuffer);

        if (this.hasTexture) {
            glEnableClientState(GL_TEXTURE_COORD_ARRAY);
            glTexCoordPointer(2, 0, this.textureCoordinateBuffer);
        }

        if (this.hasColor) {
            glEnableClientState(GL_COLOR_ARRAY);
            glColorPointer(4, 0, this.colorBuffer);
        }

        glDrawArrays(this.primitive, 0, this.vertices);

        glDisableClientState(GL_VERTEX_ARRAY);
        if (this.hasTexture) {
            glDisableClientState(GL_TEXTURE_COORD_ARRAY);
        }
        if (this.hasColor) {
            glDisableClientState(GL_COLOR_ARRAY);
        }

        clear();
    }

    private void clear() {
        this.vertexBuffer.clear();
        this.textureCoordinateBuffer.clear();
        this.colorBuffer.clear();
        this.vertices = 0;

        this.hasTexture = false;
        this.hasColor = false;
    }
}