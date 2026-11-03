#version 120
// MAX_LIQUIDS is #defined by LiquidShader.java, which also writes the liqTexN sampler uniforms
// and sampleLiq() in place of the marker line further down

// ---- tunables -------------------------------------------------------------
// texels per block: matches the 16x16 block textures. every sample, the shore and the foam
// are snapped to this grid, so the liquid has the same pixel size as the terrain.
// (lower = chunkier pixels, 8.0 = each pixel is 2x2 texels of the terrain)
const float PIXELS_PER_BLOCK = 16.0;
// how far the liquid's own colour is pushed onto the texture (0 = texture only, 1 = full tint)
const float TINT = 0.35;
// colour steps per channel for the retro palette (8 -> 512 colours, 32 -> 32768).
// keep it high enough for dark liquids: sludge.png only spans about 0.14 - 0.29 per channel, which 8 steps
// (1/7 = 0.14 apart) squash into 2-3 colours
const float COLOR_LEVELS = 32.0;
// turbulence reach in blocks per unit of the liquid's warp setting
const float WARP_BLOCKS = 0.75;
// opacity of the shore rim (it is shaped by the same foam texture as the foam, just always present)
const float RIM_STRENGTH = 0.7;
// half the thickness, in blocks, of a pane (2 px collision) when the shader draws its footprint for the shore
const float PANE_HALF = 1.0 / 16.0;
// foam: distortion.png repeats this many times per block. 0.25 = one texel of the image is one texel of the
// world (64px image -> repeats every 4 blocks), so the foam pixels match the terrain's pixel size
const float FOAM_SCALE = 0.25;
// how far the foam coordinates are pushed around (in repeats of the image) and how fast that pattern varies
const float FOAM_WOBBLE = 0.04;
const float FOAM_WOBBLE_FREQ = 2.0;
// how much of the liquid's foam colour is laid over the water (1 = solid, lower = see-through foam)
const float FOAM_OPACITY = 0.55;

uniform float time;
uniform float ambient;
uniform sampler2D foamTex;   // textures/block/liquid/distortion.png, bound by LiquidShader
//@SAMPLERS

varying vec3  vWorld;
varying vec2  vCell;
varying vec3  vLight;
varying vec4  vBase;
varying vec4  vFoam;
varying vec4  vMotion;
varying vec2  vSheet;
varying vec4  vEdges;
varying vec4  vCorners;
varying vec2  vPane;
varying float vTop;
varying float vFoamWeight;
varying float vLiqIndex;

vec3 posterize(vec3 c) {
    float n = COLOR_LEVELS - 1.0;
    return clamp(floor(c * n + 0.5) / n, 0.0, 1.0);
}

// distance from p to the rectangle lo..hi (negative inside)
float boxDistance(vec2 p, vec2 lo, vec2 hi) {
    vec2 c = (lo + hi) * 0.5;
    vec2 d = abs(p - c) - (hi - lo) * 0.5;
    return length(max(d, 0.0)) + min(max(d.x, d.y), 0.0);
}

// distance from u (position inside the cell, 0..1) to the nearest solid wall
float shoreDistance(vec2 u) {
    float sdf = 9.0;
    if (vEdges.x > 0.5) sdf = min(sdf, u.x);
    if (vEdges.y > 0.5) sdf = min(sdf, 1.0 - u.x);
    if (vEdges.z > 0.5) sdf = min(sdf, u.y);
    if (vEdges.w > 0.5) sdf = min(sdf, 1.0 - u.y);
    if (vCorners.x > 0.5) sdf = min(sdf, length(u));
    if (vCorners.y > 0.5) sdf = min(sdf, length(vec2(1.0 - u.x, u.y)));
    if (vCorners.z > 0.5) sdf = min(sdf, length(vec2(u.x, 1.0 - u.y)));
    if (vCorners.w > 0.5) sdf = min(sdf, length(vec2(1.0) - u));
    // panes (metal bars, framed glass): a thin plane through the middle of the cell, running along x and/or z
    if (vPane.x > 0.5) sdf = min(sdf, boxDistance(u, vec2(0.0, 0.5 - PANE_HALF), vec2(1.0, 0.5 + PANE_HALF)));
    if (vPane.y > 0.5) sdf = min(sdf, boxDistance(u, vec2(0.5 - PANE_HALF, 0.0), vec2(0.5 + PANE_HALF, 1.0)));
    return sdf;
}

// foam pattern, from the distortion image (the red channel is the blob texture)
float foamAt(vec2 pos, float t) {
    // foam perturbed coordinates
    vec2 uv = pos * FOAM_SCALE;
    uv.x += cos(pos.y * FOAM_WOBBLE_FREQ + t) * FOAM_WOBBLE;
    uv.y += sin(pos.x * FOAM_WOBBLE_FREQ + t) * FOAM_WOBBLE;

    // process the blob texture into a foam-like pattern
    float foam = texture2D(foamTex, uv).x;
    foam = 1.0 - foam;
    foam = min(foam * 1.2, 1.0);
    foam = pow(foam, 4.0);
    return min(foam * 1.5, 1.0);
}

void main() {
    float t    = time * vMotion.y;
    bool  top  = vTop > 0.5;
    int   idx  = int(vLiqIndex + 0.5);
    float warp = vMotion.x * WARP_BLOCKS;
    float P    = PIXELS_PER_BLOCK;

    // top face uses XZ, sides unroll along the wall
    vec2 pos = top ? vWorld.xz : vec2(vWorld.x + vWorld.z, vWorld.y);

    // snap to the texel grid first, so the whole texel moves together when warped
    vec2 pp = (floor(pos * P) + 0.5) / P;

    // Quake-style turbulence, in blocks; then snap again so we always read whole texels
    vec2 w = pp;
    w.x += warp * (sin(pp.y * 4.0 + t)        + 0.4 * sin(pp.y * 7.3 - t * 0.6));
    w.y += warp * (sin(pp.x * 4.0 + t * 1.07) + 0.4 * sin(pp.x * 8.1 + t * 0.8));
    vec2 uv = (floor(w * P) + 0.5) / P;      // position in blocks, on the texel grid

    // the texture is a sheet of vSheet.x * vSheet.y cells laid over the world, like WrappedTile:
    // one cell per block, repeating every vSheet blocks. top faces: columns follow X, rows follow Z.
    // side faces: along the wall, and up is up the sheet (row 0 = top)
    uv = vec2(uv.x, top ? uv.y : -uv.y) / vSheet;

    vec4 tex   = sampleLiq(idx, uv);
    vec3 color = mix(tex.rgb, clamp(tex.rgb * vBase.rgb * 2.0, 0.0, 1.0), TINT);
    // a fully opaque liquid (lava, sludge) ignores the texture's own alpha
    float alpha = vBase.a > 0.999 ? 1.0 : vBase.a * tex.a;

    if (top) {
        vec2 u = (min(floor(vCell * P), P - 1.0) + 0.5) / P;   // pixel centre inside the cell
        float sdf = max(shoreDistance(u), 0.0);
        if (sdf < 8.0) {
            float foam = foamAt(pp, t);

            // shore rim: a lip hugging every wall, broken up by the same foam texture as the foam itself
            float lip = (1.0 - smoothstep(0.0, 0.12, sdf)) * (0.4 + 0.6 * foam);
            float k = lip * RIM_STRENGTH;

            // foam waves: only where LiquidTile marked the body as big (foamWeight > 0)
            if (vFoam.a > 0.0 && vFoamWeight > 0.0) {
                // coastal pulse wave. the wave position is counted in whole texels (read at the texel centre),
                // so the crest is never thinner than one pixel
                float texel  = floor((sdf + t * 0.2) * P);
                float pulse  = (mod(texel, P) + 0.5) / P;
                float front  = min((pulse - 0.95) * 20.0, 1.0);
                float back   = pow(1.0 - pulse, 8.0);
                float height = max(front, back);
                float fade   = 1.0 - clamp((sdf - 0.6) * 5.0, 0.0, 1.0);
                float wave   = max(0.0, height * 1.5 - 1.0 + foam) * fade * vFoam.a * vFoamWeight;
                k = max(k, wave * FOAM_OPACITY);
            }

            color = mix(color, vFoam.rgb, k);
            alpha = mix(alpha, 1.0, lip * RIM_STRENGTH * 0.5);
        }
    }

    color = posterize(color);

    float s = vLight.x * ambient;
    float l = (s + (1.0 - s) * vLight.y) * vLight.z;
    gl_FragColor = vec4(color * l, alpha);
}