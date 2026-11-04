package com.nikohalfkino.rubydung.render;

import com.nikohalfkino.rubydung.level.tiles.LiquidTile;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL20;

import java.nio.FloatBuffer;
import java.util.List;

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
                    "uniform vec4 liqTint[" + LiquidTile.MAX_LIQUIDS + "];\n" +
                    // depth bands (shallow to deep) of rgb per liquid, MAX_DEPTH_BANDS slots each, see LiquidTile.getDepthPalette
                    "uniform vec3 liqPalette[" + (LiquidTile.MAX_LIQUIDS * LiquidTile.MAX_DEPTH_BANDS) + "];\n" +
                    // how many of those slots each liquid really uses
                    "uniform float liqBands[" + LiquidTile.MAX_LIQUIDS + "];\n" +
                    "varying vec4 vColor;\n" +
                    Fog.GLSL_FRAGMENT_DECLARATIONS +
                    Fog.GLSL_DITHER_DECLARATIONS +

                    "void main() {\n" +
                    "    applyDither();\n" +
                    "    vec4 c = texture2D(tex, gl_TexCoord[0].st);\n" +

                    "    float s = vColor.r * ambient;\n" +
                    "    float l = (s + (1.0 - s) * vColor.g) * vColor.b;\n" +
                    "    vec3 rgb = c.rgb * l;\n" +

                    "    if (vColor.a < 0.9997) {\n" +
                    "        float p = floor(vColor.a * 2048.0 + 0.5);\n" +
                    "        float idx = floor(p / 256.0);\n" +
                    "        float depth = (p - idx * 256.0) / 8.0;\n" +

                    "        vec4 t = liqTint[0];\n" +
                    "        for (int i = 1; i < " + LiquidTile.MAX_LIQUIDS + "; i++) {\n" +
                    "            if (float(i) == idx) t = liqTint[i];\n" +
                    "        }\n" +

                    "        float band = floor(max(depth - 0.001, 0.0));\n" +

                    "        vec3 water = liqPalette[0];\n" +
                    "        for (int i = 0; i < " + LiquidTile.MAX_LIQUIDS + "; i++) {\n" +
                    "            if (float(i) == idx) {\n" +
                    "                float bandC = min(band, liqBands[i] - 1.0);\n" +
                    "                for (int b = 0; b < " + LiquidTile.MAX_DEPTH_BANDS + "; b++) {\n" +
                    "                    if (float(b) == bandC) water = liqPalette[i * " + LiquidTile.MAX_DEPTH_BANDS + " + b];\n" +
                    "                }\n" +
                    "            }\n" +
                    "        }\n" +

                    "        float opacity = clamp(t.a, 0.0, 1.0);\n" +
                    "        float amount = 1.0 - exp(-depth * opacity * 0.55);\n" +
                    "        vec3 tint = mix(vec3(1.0), water, amount);\n" +

                    "        float visible = max(1.0 - opacity, 0.3);\n" +
                    "        tint = pow(tint, vec3(1.0 / visible));\n" +
                    "        rgb *= tint;\n" +
                    "    }\n" +

                    "    gl_FragColor = vec4(applyFog(rgb), c.a);\n" +
                    "}\n";

    private static int program = 0;
    private static int ambientLocation = -1;
    private static int tintLocation = -1;
    private static int paletteLocation = -1;
    private static int bandsLocation = -1;
    private static int fogParamsLocation = -1;
    private static int fogColorLocation = -1;
    private static int fogEyeLocation = -1;

    private static final FloatBuffer TINTS =
            BufferUtils.createFloatBuffer(LiquidTile.MAX_LIQUIDS * 4);

    // bands of a liquid that doesn't set its own palette
    private static final int DEFAULT_BANDS = 6;

    private static final FloatBuffer PALETTE =
            BufferUtils.createFloatBuffer(LiquidTile.MAX_LIQUIDS * LiquidTile.MAX_DEPTH_BANDS * 3);
    private static final FloatBuffer BAND_COUNTS =
            BufferUtils.createFloatBuffer(LiquidTile.MAX_LIQUIDS);

    private TerrainShader() {
    }

    public static void bind(float ambient) {
        if (program == 0) {
            init();
        }

        GL20.glUseProgram(program);
        GL20.glUniform1f(ambientLocation, ambient);
        Fog.upload(fogParamsLocation, fogColorLocation, fogEyeLocation);

        TINTS.clear();
        List<LiquidTile> liquids = LiquidTile.all();

        for (int i = 0; i < LiquidTile.MAX_LIQUIDS; i++) {
            if (i < liquids.size()) {
                LiquidTile liquid = liquids.get(i);
                TINTS.put(liquid.getRed())
                        .put(liquid.getGreen())
                        .put(liquid.getBlue())
                        .put(liquid.getOpacity());
            } else {
                TINTS.put(0.0F)
                        .put(0.0F)
                        .put(0.0F)
                        .put(0.0F);
            }
        }

        TINTS.flip();
        GL20.glUniform4(tintLocation, TINTS);

        PALETTE.clear();
        BAND_COUNTS.clear();
        for (int i = 0; i < LiquidTile.MAX_LIQUIDS; i++) {
            LiquidTile liquid = i < liquids.size() ? liquids.get(i) : null;
            float[][] custom = liquid == null ? null : liquid.getDepthPalette();
            int bands = custom != null ? custom.length : DEFAULT_BANDS;
            bands = Math.max(1, Math.min(LiquidTile.MAX_DEPTH_BANDS, bands));

            float[] last = {1.0F, 1.0F, 1.0F};
            for (int band = 0; band < LiquidTile.MAX_DEPTH_BANDS; band++) {
                if (band < bands) {
                    last = liquid == null ? last : bandColor(liquid, custom, band, bands);
                }
                PALETTE.put(last[0]).put(last[1]).put(last[2]);   // unused slots repeat the deepest colour
            }
            BAND_COUNTS.put(bands);
        }
        PALETTE.flip();
        BAND_COUNTS.flip();
        GL20.glUniform3(paletteLocation, PALETTE);
        GL20.glUniform1(bandsLocation, BAND_COUNTS);
    }

    /** the colour blocks take on under this liquid at a depth band (0 = right under the surface): the liquid's own palette if it has one, otherwise a ramp from a lightened to a darkened version of its colour */
    private static float[] bandColor(LiquidTile liquid, float[][] custom, int band, int bands) {
        if (custom != null) {
            return custom[band];
        }
        float k = bands <= 1 ? 0.0F : band / (float) (bands - 1);
        float[] rgb = {liquid.getRed(), liquid.getGreen(), liquid.getBlue()};
        float[] out = new float[3];
        for (int c = 0; c < 3; c++) {
            float shallow = rgb[c] + (1.0F - rgb[c]) * 0.35F;
            float deep = rgb[c] * 0.3F;
            out[c] = shallow + (deep - shallow) * k;
        }
        return out;
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
        tintLocation = GL20.glGetUniformLocation(p, "liqTint");
        paletteLocation = GL20.glGetUniformLocation(p, "liqPalette");
        bandsLocation = GL20.glGetUniformLocation(p, "liqBands");
        fogParamsLocation = GL20.glGetUniformLocation(p, "uFogParams");
        fogColorLocation = GL20.glGetUniformLocation(p, "uFogColor");
        fogEyeLocation = GL20.glGetUniformLocation(p, "uFogEye");
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