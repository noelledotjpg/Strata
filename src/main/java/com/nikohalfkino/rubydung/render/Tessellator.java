package com.nikohalfkino.rubydung.render;

import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL11.*;

public class Tessellator {

    private static final int MAX_VERTICES = 100000;

    private int primitive = GL_QUADS;

    private final FloatBuffer vertexBuffer = BufferUtils.createFloatBuffer(MAX_VERTICES * 3);
    private final FloatBuffer textureCoordinateBuffer = BufferUtils.createFloatBuffer(MAX_VERTICES * 2);
    private final FloatBuffer colorBuffer = BufferUtils.createFloatBuffer(MAX_VERTICES * 4);

    private int vertices = 0;

    private boolean hasTexture = false;
    private float textureU;
    private float textureV;

    private boolean hasColor;
    private float red;
    private float green;
    private float blue;
    private float alpha;

    public void init() {
        init(GL_QUADS);
    }

    public void init(int primitive) {
        clear();
        this.primitive = primitive;
    }

    public void vertex(float x, float y, float z) {
        this.vertexBuffer.put(this.vertices * 3, x);
        this.vertexBuffer.put(this.vertices * 3 + 1, y);
        this.vertexBuffer.put(this.vertices * 3 + 2, z);

        if (this.hasTexture) {
            this.textureCoordinateBuffer.put(this.vertices * 2, this.textureU);
            this.textureCoordinateBuffer.put(this.vertices * 2 + 1, this.textureV);
        }

        if (this.hasColor) {
            this.colorBuffer.put(this.vertices * 4, this.red);
            this.colorBuffer.put(this.vertices * 4 + 1, this.green);
            this.colorBuffer.put(this.vertices * 4 + 2, this.blue);
            this.colorBuffer.put(this.vertices * 4 + 3, this.alpha);
        }

        this.vertices++;

        if (this.vertices == MAX_VERTICES) {
            flush();
        }
    }

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
        this.vertexBuffer.flip();
        this.textureCoordinateBuffer.flip();
        this.colorBuffer.flip();

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