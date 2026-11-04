#version 120
// depth-only vertex shader for the liquid pre-pass (see LiquidShader.bindDepth).
// the waves are gone, so the position is a plain transform. LiquidShader.addFog() wraps this main() and adds the
// fog / ocean-fade varyings, exactly as it does for liquid.vert
invariant gl_Position;

void main() {
    gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;
}
