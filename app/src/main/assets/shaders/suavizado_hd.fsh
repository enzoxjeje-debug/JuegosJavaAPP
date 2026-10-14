// Suavizado HD: Catmull-Rom bicubic upscaling + light unsharp mask.
// Smooths the low-resolution J2ME framebuffer when scaled to the phone screen
// and recovers a bit of sharpness, so the image looks clean instead of blocky.
// u_setting.x = sharpen amount (0..1)

#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

uniform sampler2D sampler0;
uniform vec2 u_texelDelta;   // 1.0 / texture size in texels
uniform vec2 u_pixelDelta;   // 1.0 / viewport size in pixels
uniform vec4 u_setting;      // x: sharpen amount

varying vec2 v_texcoord0;

// Catmull-Rom weights for the 4 taps at offsets -1, 0, 1, 2
vec4 cubic(float v) {
    vec4 n = vec4(1.0, 2.0, 3.0, 4.0) - v;
    vec4 s = n * n * n;
    float x = s.x;
    float y = s.y - 4.0 * s.x;
    float z = s.z - 4.0 * s.y + 6.0 * s.x;
    float w = 6.0 - x - y - z;
    return vec4(x, y, z, w) * (1.0 / 6.0);
}

vec4 sampleBicubic(vec2 uv) {
    vec2 texSize = vec2(1.0) / u_texelDelta;
    vec2 coord = uv * texSize - 0.5;
    vec2 f = fract(coord);
    vec2 base = (floor(coord) + 0.5) * u_texelDelta;

    vec4 xc = cubic(f.x);
    vec4 yc = cubic(f.y);

    vec4 c00 = texture2D(sampler0, base + vec2(-1.0, -1.0) * u_texelDelta);
    vec4 c10 = texture2D(sampler0, base + vec2( 0.0, -1.0) * u_texelDelta);
    vec4 c20 = texture2D(sampler0, base + vec2( 1.0, -1.0) * u_texelDelta);
    vec4 c30 = texture2D(sampler0, base + vec2( 2.0, -1.0) * u_texelDelta);

    vec4 c01 = texture2D(sampler0, base + vec2(-1.0,  0.0) * u_texelDelta);
    vec4 c11 = texture2D(sampler0, base + vec2( 0.0,  0.0) * u_texelDelta);
    vec4 c21 = texture2D(sampler0, base + vec2( 1.0,  0.0) * u_texelDelta);
    vec4 c31 = texture2D(sampler0, base + vec2( 2.0,  0.0) * u_texelDelta);

    vec4 c02 = texture2D(sampler0, base + vec2(-1.0,  1.0) * u_texelDelta);
    vec4 c12 = texture2D(sampler0, base + vec2( 0.0,  1.0) * u_texelDelta);
    vec4 c22 = texture2D(sampler0, base + vec2( 1.0,  1.0) * u_texelDelta);
    vec4 c32 = texture2D(sampler0, base + vec2( 2.0,  1.0) * u_texelDelta);

    vec4 c03 = texture2D(sampler0, base + vec2(-1.0,  2.0) * u_texelDelta);
    vec4 c13 = texture2D(sampler0, base + vec2( 0.0,  2.0) * u_texelDelta);
    vec4 c23 = texture2D(sampler0, base + vec2( 1.0,  2.0) * u_texelDelta);
    vec4 c33 = texture2D(sampler0, base + vec2( 2.0,  2.0) * u_texelDelta);

    vec4 cx0 = c00 * xc.x + c10 * xc.y + c20 * xc.z + c30 * xc.w;
    vec4 cx1 = c01 * xc.x + c11 * xc.y + c21 * xc.z + c31 * xc.w;
    vec4 cx2 = c02 * xc.x + c12 * xc.y + c22 * xc.z + c32 * xc.w;
    vec4 cx3 = c03 * xc.x + c13 * xc.y + c23 * xc.z + c33 * xc.w;

    return cx0 * yc.x + cx1 * yc.y + cx2 * yc.z + cx3 * yc.w;
}

void main() {
    vec4 color = sampleBicubic(v_texcoord0);

    // light unsharp mask to restore some contrast lost by the smoothing
    float sharpen = u_setting.x;
    if (sharpen > 0.001) {
        vec2 o = u_texelDelta * 1.5;
        vec3 blur = 0.25 * (
            texture2D(sampler0, v_texcoord0 + vec2( o.x,  o.y)).rgb +
            texture2D(sampler0, v_texcoord0 + vec2(-o.x,  o.y)).rgb +
            texture2D(sampler0, v_texcoord0 + vec2( o.x, -o.y)).rgb +
            texture2D(sampler0, v_texcoord0 + vec2(-o.x, -o.y)).rgb);
        color.rgb = clamp(color.rgb + (color.rgb - blur) * sharpen, 0.0, 1.0);
    }

    gl_FragColor = color;
}
