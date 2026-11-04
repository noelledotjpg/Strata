#version 120
// depth-only fragment shader for the liquid pre-pass. the colour is masked off, what matters is which pixels survive:
// LiquidShader.addFog() wraps this main() with applyDither() and applyOceanFade(), the same discards the colour pass has,
// so both passes keep exactly the same pixels

void main() {
    gl_FragColor = vec4(0.0);
}
