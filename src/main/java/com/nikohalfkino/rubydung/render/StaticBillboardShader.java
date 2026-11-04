package com.nikohalfkino.rubydung.render;

import org.lwjgl.opengl.GL20;

/**
 * Draws BillboardMesh geometry. The vertex shader does what LevelRenderer used to do on the CPU for every sprite on
 * every frame: turn the quad to face the camera (around Y only), grow it with distance (BillboardTile.getDistanceScale)
 * and drop it when it is past its reach. The fragment half is the same as BillboardShader's.
 */
public final class StaticBillboardShader {

    private static final String VERT =
            "#version 120\n" +
                    "varying vec4 vColor;\n" +
                    "varying float vWorldY;\n" +
                    Fog.GLSL_VERTEX_DECLARATIONS +
                    "uniform vec3 uEye;\n" +
                    "uniform vec2 uRight;\n" +
                    // x: reach per block of sprite size, y: reach whatever the size
                    "uniform vec2 uReach;\n" +
                    "float distanceScale(float d) {\n" +
                    "    if (d < 20.0) return 1.0;\n" +
                    "    if (d < 150.0) return 1.0 + (d - 20.0) / 260.0;\n" +
                    "    if (d < 500.0) return 1.5 + (d - 150.0) / 700.0;\n" +
                    "    return 2.0;\n" +
                    "}\n" +
                    "void main() {\n" +
                    "    vec3 pivot = gl_Vertex.xyz;\n" +
                    "    vec3 corner = gl_MultiTexCoord1.xyz;\n" +
                    "    float d = distance(uEye, pivot);\n" +
                    "    gl_TexCoord[0] = gl_MultiTexCoord0;\n" +
                    "    vColor = gl_Color;\n" +
                    "    vWorldY = pivot.y;\n" +
                    "    vFogWorldXZ = pivot.xz;\n" +
                    "    if (d > min(uReach.y, corner.z * uReach.x)) {\n" +
                    "        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);\n" +
                    "        return;\n" +
                    "    }\n" +
                    "    float size = corner.z * distanceScale(d);\n" +
                    "    vec3 p = pivot + vec3(uRight.x * corner.x * size, corner.y * size, uRight.y * corner.x * size);\n" +
                    "    vWorldY = p.y;\n" +
                    "    vFogWorldXZ = p.xz;\n" +
                    "    gl_Position = gl_ModelViewProjectionMatrix * vec4(p, 1.0);\n" +
                    "}\n";

    private static final String FRAG =
            "#version 120\n" +
                    "uniform sampler2D tex;\n" +
                    "uniform float ambient;\n" +
                    // pixels with less alpha than this are cut out (0.01 when the sprites are blended, 0.5 when they are opaque cut-outs)
                    "uniform float uCutoff;\n" +
                    "varying vec4 vColor;\n" +
                    "varying float vWorldY;\n" +
                    LiquidTinting.GLSL_DECLARATIONS +
                    Fog.GLSL_FRAGMENT_DECLARATIONS +
                    Fog.GLSL_DITHER_DECLARATIONS +
                    "void main() {\n" +
                    "    applyDither();\n" +
                    "    vec4 c = texture2D(tex, gl_TexCoord[0].st);\n" +
                    "    if (c.a < uCutoff) discard;\n" +
                    // same light mix as TerrainShader: sky light (r) scaled by the live day/night ambient, block light (g) fills the rest
                    "    float s = vColor.r * ambient;\n" +
                    "    float l = s + (1.0 - s) * vColor.g;\n" +
                    "    vec3 rgb = c.rgb * l;\n" +
                    "    float alpha = vColor.a;\n" +
                    "    if (alpha < 0.9997) {\n" +
                    "        rgb = liquidTint(rgb, alpha, vWorldY);\n" +
                    "        alpha = 1.0;\n" +
                    "    }\n" +
                    "    gl_FragColor = vec4(applyFog(rgb), c.a * alpha);\n" +
                    "}\n";

    private static int program = 0;
    private static int eyeLocation = -1;
    private static int rightLocation = -1;
    private static int reachLocation = -1;
    private static int ambientLocation = -1;
    private static int cutoffLocation = -1;
    private static int fogParamsLocation = -1;
    private static int fogColorLocation = -1;
    private static int fogEyeLocation = -1;
    private static LiquidTinting.Uniforms tinting;

    private StaticBillboardShader() {}

    /**
     * rightX / rightZ: the screen-right vector in the XZ plane. reachPerBlock / maxReach: a sprite is dropped past
     * min(maxReach, its size * reachPerBlock) blocks from the eye. ambient: the day/night sky ambient, as for TerrainShader.
     */
    public static void bind(double eyeX, double eyeY, double eyeZ, float rightX, float rightZ,
                            float reachPerBlock, float maxReach, float ambient, float alphaCutoff) {
        if (program == 0) {
            init();
        }
        GL20.glUseProgram(program);
        GL20.glUniform1f(ambientLocation, ambient);
        GL20.glUniform1f(cutoffLocation, alphaCutoff);
        Fog.upload(fogParamsLocation, fogColorLocation, fogEyeLocation);
        tinting.upload();
        GL20.glUniform3f(eyeLocation, (float) eyeX, (float) eyeY, (float) eyeZ);
        GL20.glUniform2f(rightLocation, rightX, rightZ);
        GL20.glUniform2f(reachLocation, reachPerBlock, maxReach);
    }

    /** the alpha below which a pixel is cut out, for the program already bound (0.01 for blended sprites, 0.5 for cut-outs) */
    public static void setCutoff(float alphaCutoff) {
        GL20.glUniform1f(cutoffLocation, alphaCutoff);
    }

    public static void unbind() {
        GL20.glUseProgram(0);
    }

    private static void init() {
        int vs = compile(GL20.GL_VERTEX_SHADER, VERT);
        int fs = compile(GL20.GL_FRAGMENT_SHADER, FRAG);
        int p = GL20.glCreateProgram();
        GL20.glAttachShader(p, vs);
        GL20.glAttachShader(p, fs);
        GL20.glLinkProgram(p);
        if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == 0) {
            throw new RuntimeException("Static billboard shader link failed: " + GL20.glGetProgramInfoLog(p, 4096));
        }

        GL20.glUseProgram(p);
        GL20.glUniform1i(GL20.glGetUniformLocation(p, "tex"), 0);
        GL20.glUseProgram(0);

        eyeLocation = GL20.glGetUniformLocation(p, "uEye");
        rightLocation = GL20.glGetUniformLocation(p, "uRight");
        reachLocation = GL20.glGetUniformLocation(p, "uReach");
        ambientLocation = GL20.glGetUniformLocation(p, "ambient");
        cutoffLocation = GL20.glGetUniformLocation(p, "uCutoff");
        fogParamsLocation = GL20.glGetUniformLocation(p, "uFogParams");
        fogColorLocation = GL20.glGetUniformLocation(p, "uFogColor");
        fogEyeLocation = GL20.glGetUniformLocation(p, "uFogEye");
        tinting = new LiquidTinting.Uniforms(p);
        program = p;
    }

    private static int compile(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) {
            throw new RuntimeException("Static billboard shader compile failed: " + GL20.glGetShaderInfoLog(shader, 4096));
        }
        return shader;
    }
}