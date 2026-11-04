package com.nikohalfkino.rubydung.render;

import org.lwjgl.opengl.GL20;

public final class TerrainShader {

    private static final String VERT =
            "#version 120\n" +
                    "varying vec4 vColor;\n" +
                    Fog.GLSL_VERTEX_DECLARATIONS +
                    "void main() {\n" +
                    "    gl_Position = ftransform();\n" +
                    "    vFogWorldXZ = gl_Vertex.xz;\n" +
                    "    gl_TexCoord[0] = gl_MultiTexCoord0;\n" +
                    "    vColor = gl_Color;\n" +
                    "}\n";

    private static final String FRAG =
            "#version 120\n" +
                    "uniform sampler2D tex;\n" +
                    "uniform float ambient;\n" +
                    "varying vec4 vColor;\n" +
                    LiquidTinting.GLSL_DECLARATIONS +
                    Fog.GLSL_FRAGMENT_DECLARATIONS +
                    Fog.GLSL_DITHER_DECLARATIONS +

                    "void main() {\n" +
                    "    applyDither();\n" +
                    "    vec4 c = texture2D(tex, gl_TexCoord[0].st);\n" +

                    "    float s = vColor.r * ambient;\n" +
                    "    float l = (s + (1.0 - s) * vColor.g) * vColor.b;\n" +
                    "    vec3 rgb = c.rgb * l;\n" +

                    // terrain faces are tinted by the depth of the cell they were packed for
                    "    if (vColor.a < 0.9997) {\n" +
                    "        rgb = liquidTint(rgb, vColor.a, -1000.0);\n" +
                    "    }\n" +

                    "    gl_FragColor = vec4(applyFog(rgb), c.a);\n" +
                    "}\n";

    private static int program = 0;
    private static int ambientLocation = -1;
    private static int fogParamsLocation = -1;
    private static int fogColorLocation = -1;
    private static int fogEyeLocation = -1;
    private static LiquidTinting.Uniforms tinting;

    /** the ocean, set by LevelRenderer every frame before bind (a very low height = no ocean). shared with the billboard shader */
    public static void setOcean(float height, int liquidIndex) {
        LiquidTinting.setOcean(height, liquidIndex);
    }

    private TerrainShader() {
    }

    public static void bind(float ambient) {
        if (program == 0) {
            init();
        }

        GL20.glUseProgram(program);
        GL20.glUniform1f(ambientLocation, ambient);
        Fog.upload(fogParamsLocation, fogColorLocation, fogEyeLocation);
        tinting.upload();
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
            throw new RuntimeException(
                    "Terrain shader link failed: " +
                            GL20.glGetProgramInfoLog(p, 4096)
            );
        }

        GL20.glUseProgram(p);
        GL20.glUniform1i(GL20.glGetUniformLocation(p, "tex"), 0);
        GL20.glUseProgram(0);

        ambientLocation = GL20.glGetUniformLocation(p, "ambient");
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
            throw new RuntimeException(
                    "Terrain shader compile failed: " +
                            GL20.glGetShaderInfoLog(shader, 4096)
            );
        }

        return shader;
    }
}