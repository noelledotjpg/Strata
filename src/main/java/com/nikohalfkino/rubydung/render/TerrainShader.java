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
                    "void main() {\n" +
                    "    gl_Position = ftransform();\n" +
                    "    gl_TexCoord[0] = gl_MultiTexCoord0;\n" +
                    "    vColor = gl_Color;\n" +
                    "}\n";

    private static final String FRAG =
            "#version 120\n" +
                    "uniform sampler2D tex;\n" +
                    "uniform float ambient;\n" +
                    "uniform vec4 liqTint[8];\n" +
                    "varying vec4 vColor;\n" +

                    "void main() {\n" +
                    "    vec4 c = texture2D(tex, gl_TexCoord[0].st);\n" +

                    "    float s = vColor.r * ambient;\n" +
                    "    float l = (s + (1.0 - s) * vColor.g) * vColor.b;\n" +
                    "    vec3 rgb = c.rgb * l;\n" +

                    "    if (vColor.a < 0.9997) {\n" +
                    "        float p = floor(vColor.a * 2048.0 + 0.5);\n" +
                    "        float idx = floor(p / 256.0);\n" +
                    "        float depth = (p - idx * 256.0) / 8.0;\n" +

                    // Get the opacity of the current liquid.
                    "        vec4 t = liqTint[0];\n" +
                    "        for (int i = 1; i < 8; i++) {\n" +
                    "            if (float(i) == idx) t = liqTint[i];\n" +
                    "        }\n" +

                    // Each full block creates a new, distinct depth band.
                    "        float band = floor(max(depth - 0.001, 0.0));\n" +

                    // Tropical water palette.
                    "        vec3 water;\n" +
                    "        if (band < 1.0) {\n" +
                    "            water = vec3(0.38, 0.91, 1.00);\n" +
                    "        } else if (band < 2.0) {\n" +
                    "            water = vec3(0.21, 0.72, 0.94);\n" +
                    "        } else if (band < 3.0) {\n" +
                    "            water = vec3(0.10, 0.53, 0.82);\n" +
                    "        } else if (band < 4.0) {\n" +
                    "            water = vec3(0.06, 0.36, 0.65);\n" +
                    "        } else if (band < 5.0) {\n" +
                    "            water = vec3(0.04, 0.24, 0.47);\n" +
                    "        } else {\n" +
                    "            water = vec3(0.025, 0.13, 0.29);\n" +
                    "        }\n" +

                    // Blend according to liquid opacity.
                    "        float opacity = clamp(t.a, 0.0, 1.0);\n" +
                    "        float amount = 1.0 - exp(-depth * opacity * 0.55);\n" +
                    "        vec3 tint = mix(vec3(1.0), water, amount);\n" +

                    // The liquid surface is drawn over the terrain afterwards and covers `opacity` of every pixel,
                    // so only (1 - opacity) of this tint reaches the screen and its colour was lost, leaving just
                    // the brightness differences. Strengthen it by the share that gets covered (capped, so a
                    // nearly opaque liquid doesn't blow it up).
                    "        float visible = max(1.0 - opacity, 0.3);\n" +
                    "        tint = pow(tint, vec3(1.0 / visible));\n" +
                    "        rgb *= tint;\n" +
                    "    }\n" +

                    "    gl_FragColor = vec4(rgb, c.a);\n" +
                    "}\n";

    private static int program = 0;
    private static int ambientLocation = -1;
    private static int tintLocation = -1;

    private static final FloatBuffer TINTS =
            BufferUtils.createFloatBuffer(LiquidTile.MAX_LIQUIDS * 4);

    private TerrainShader() {
    }

    public static void bind(float ambient) {
        if (program == 0) {
            init();
        }

        GL20.glUseProgram(program);
        GL20.glUniform1f(ambientLocation, ambient);

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