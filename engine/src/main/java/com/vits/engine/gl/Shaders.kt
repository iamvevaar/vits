package com.vits.engine.gl

/**
 * All GPU programs. Conventions: uv origin is bottom-left; flow vectors are in pixels of the
 * pyramid level they live on; luma is 0..1. Pyramid texels pack (I, dI/dx, dI/dy, 1).
 */
internal object Shaders {
    const val VERTEX = """#version 300 es
in vec2 aPos;
in vec2 aUv;
uniform mat4 uPosMatrix;
uniform mat4 uTexMatrix;
out vec2 vUv;
void main() {
    gl_Position = uPosMatrix * vec4(aPos, 0.0, 1.0);
    vUv = (uTexMatrix * vec4(aUv, 0.0, 1.0)).xy;
}"""

    // External (decoder) textures are sampled with GLSL ES 1.00, which every device supports;
    // the ESSL3 variant of the extension is missing on some older drivers.
    const val OES_VERTEX = """
attribute vec2 aPos;
attribute vec2 aUv;
uniform mat4 uPosMatrix;
uniform mat4 uTexMatrix;
varying vec2 vUv;
void main() {
    gl_Position = uPosMatrix * vec4(aPos, 0.0, 1.0);
    vUv = (uTexMatrix * vec4(aUv, 0.0, 1.0)).xy;
}"""

    const val OES_COPY = """#extension GL_OES_EGL_image_external : require
precision mediump float;
uniform samplerExternalOES uTex;
varying vec2 vUv;
void main() { gl_FragColor = texture2D(uTex, vUv); }"""

    const val COPY = """#version 300 es
precision highp float;
uniform sampler2D uTex;
in vec2 vUv;
out vec4 o;
void main() { o = texture(uTex, vUv); }"""

    /** Frame blending: a linear cross-fade between the two neighbouring source frames. */
    const val BLEND = """#version 300 es
precision mediump float;
uniform sampler2D uFrame0;
uniform sampler2D uFrame1;
uniform float uT;
in vec2 vUv;
out vec4 o;
void main() { o = mix(texture(uFrame0, vUv), texture(uFrame1, vUv), uT); }"""

    // ---------------------------------------------------------------- pyramid

    /**
     * Rec.709 luma resampled to the flow resolution with a 4×4 grid of bilinear taps spanning one
     * destination texel: an area filter, so downscaling 1080p→640 does not alias.
     */
    const val LUMA_AREA = """#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform vec2 uDstTexel;
in vec2 vUv;
out vec4 o;
void main() {
    float sum = 0.0;
    for (int j = 0; j < 4; j++) {
        for (int i = 0; i < 4; i++) {
            vec2 off = (vec2(float(i), float(j)) + 0.5) * 0.25 - 0.5;
            sum += dot(texture(uTex, vUv + off * uDstTexel).rgb, vec3(0.2126, 0.7152, 0.0722));
        }
    }
    o = vec4(sum / 16.0, 0.0, 0.0, 1.0);
}"""

    /** 2× decimation with a separable binomial [1 3 3 1]/8 kernel centred between texels. */
    const val GAUSS_DOWN = """#version 300 es
precision highp float;
uniform sampler2D uTex;
out vec4 o;
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy) * 2;
    ivec2 m = textureSize(uTex, 0) - 1;
    float w[4] = float[4](1.0, 3.0, 3.0, 1.0);
    float sum = 0.0;
    for (int j = 0; j < 4; j++) {
        for (int i = 0; i < 4; i++) {
            ivec2 q = clamp(p + ivec2(i - 1, j - 1), ivec2(0), m);
            sum += w[i] * w[j] * texelFetch(uTex, q, 0).r;
        }
    }
    o = vec4(sum / 64.0, 0.0, 0.0, 1.0);
}"""

    /** Packs (I, dI/dx, dI/dy) using Scharr derivatives, in intensity per pixel. */
    const val GRADIENT = """#version 300 es
precision highp float;
uniform sampler2D uTex;
out vec4 o;
float f(ivec2 p, ivec2 m) { return texelFetch(uTex, clamp(p, ivec2(0), m), 0).r; }
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    ivec2 m = textureSize(uTex, 0) - 1;
    float tl = f(p + ivec2(-1, 1), m), t = f(p + ivec2(0, 1), m), tr = f(p + ivec2(1, 1), m);
    float l = f(p + ivec2(-1, 0), m), c = f(p, m), r = f(p + ivec2(1, 0), m);
    float bl = f(p + ivec2(-1, -1), m), b = f(p + ivec2(0, -1), m), br = f(p + ivec2(1, -1), m);
    float gx = (3.0 * (tr - tl) + 10.0 * (r - l) + 3.0 * (br - bl)) / 32.0;
    float gy = (3.0 * (tl - bl) + 10.0 * (t - b) + 3.0 * (tr - br)) / 32.0;
    o = vec4(c, gx, gy, 1.0);
}"""

    // ---------------------------------------------------------------- flow estimation

    /** Seeds a pyramid level from the coarser level's flow (or zero at the coarsest). */
    const val FLOW_UPSAMPLE = """#version 300 es
precision highp float;
uniform sampler2D uFlow;
uniform vec2 uScale;
uniform float uEnabled;
in vec2 vUv;
out vec4 o;
void main() { o = vec4(texture(uFlow, vUv).xy * uScale * uEnabled, 0.0, 1.0); }"""

    /**
     * One Gauss–Newton step of dense Lucas–Kanade with a Gaussian window and a per-window
     * brightness offset b: minimises Σ w (I1(x+u+du) − I0(x) − b)². Eliminating b centres the
     * gradients and residuals on their window means, so exposure drift between frames cancels.
     * Frame-0 gradients are used (inverse-compositional), so the 2×2 system needs no warping.
     */
    fun lucasKanade(radius: Int, sigma: Float) = """#version 300 es
precision highp float;
#define R $radius
uniform sampler2D uPyr0;
uniform sampler2D uPyr1;
uniform sampler2D uFlow;
uniform vec2 uInvSize;
out vec4 o;
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    ivec2 m = textureSize(uPyr0, 0) - 1;
    vec2 u = texelFetch(uFlow, p, 0).xy;
    float sw = 0.0, se = 0.0;
    vec2 sg = vec2(0.0), sge = vec2(0.0);
    vec3 sgg = vec3(0.0);
    for (int dy = -R; dy <= R; dy++) {
        for (int dx = -R; dx <= R; dx++) {
            ivec2 q = clamp(p + ivec2(dx, dy), ivec2(0), m);
            vec2 uv1 = (vec2(q) + 0.5 + u) * uInvSize;
            // Correspondences outside frame 1 carry no information (content left the frame).
            float inb = step(0.0, uv1.x) * step(0.0, uv1.y) * step(uv1.x, 1.0) * step(uv1.y, 1.0);
            float w = exp(-float(dx * dx + dy * dy) * ${0.5f / (sigma * sigma)}) * inb;
            vec3 g = texelFetch(uPyr0, q, 0).xyz;
            float e = texture(uPyr1, uv1).r - g.x;
            sw += w; se += w * e;
            sg += w * g.yz; sge += w * g.yz * e;
            sgg += w * vec3(g.y * g.y, g.y * g.z, g.z * g.z);
        }
    }
    if (sw < 1e-3) { o = vec4(u, 0.0, 1.0); return; }
    vec3 h = sgg - vec3(sg.x * sg.x, sg.x * sg.y, sg.y * sg.y) / sw;
    vec2 b = sge - sg * se / sw;
    h.xz += 1e-5 * sw;                       // Tikhonov: no motion where there is no texture
    float det = h.x * h.z - h.y * h.y;
    vec2 du = -vec2(h.z * b.x - h.y * b.y, h.x * b.y - h.y * b.x) / det;
    float len = length(du);
    if (len > 1.0) du /= len;                // trust region: at most 1 px per step
    o = vec4(u + du, 0.0, 1.0);
}"""

    /** Component-wise 3×3 median (McGuire's min/max network): removes outlier vectors. */
    const val MEDIAN3 = """#version 300 es
precision highp float;
uniform sampler2D uFlow;
out vec4 o;
#define s2(a, b) t = a; a = min(a, b); b = max(t, b);
#define mn3(a, b, c) s2(a, b); s2(a, c);
#define mx3(a, b, c) s2(b, c); s2(a, c);
#define mnmx3(a, b, c) mx3(a, b, c); s2(a, b);
#define mnmx4(a, b, c, d) s2(a, b); s2(c, d); s2(a, c); s2(b, d);
#define mnmx5(a, b, c, d, e) s2(a, b); s2(c, d); mn3(a, c, e); mx3(b, d, e);
#define mnmx6(a, b, c, d, e, f) s2(a, d); s2(b, e); s2(c, f); mn3(a, b, c); mx3(d, e, f);
vec2 at(ivec2 p, ivec2 m) { return texelFetch(uFlow, clamp(p, ivec2(0), m), 0).xy; }
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    ivec2 m = textureSize(uFlow, 0) - 1;
    vec2 v0 = at(p + ivec2(-1, -1), m), v1 = at(p + ivec2(0, -1), m), v2 = at(p + ivec2(1, -1), m);
    vec2 v3 = at(p + ivec2(-1, 0), m), v4 = at(p, m), v5 = at(p + ivec2(1, 0), m);
    vec2 v6 = at(p + ivec2(-1, 1), m), v7 = at(p + ivec2(0, 1), m), v8 = at(p + ivec2(1, 1), m);
    vec2 t;
    mnmx6(v0, v1, v2, v3, v4, v5);
    mnmx5(v1, v2, v3, v4, v6);
    mnmx4(v2, v3, v4, v7);
    mnmx3(v3, v4, v8);
    o = vec4(v4, 0.0, 1.0);
}"""

    /** Linearisation point for the variational stage: (I1(x+u0) − I0(x), averaged gradient). */
    const val WARP_PREP = """#version 300 es
precision highp float;
uniform sampler2D uPyr0;
uniform sampler2D uPyr1;
uniform sampler2D uFlow0;
uniform vec2 uInvSize;
out vec4 o;
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec3 a = texelFetch(uPyr0, p, 0).xyz;
    vec2 uv1 = (vec2(p) + 0.5 + texelFetch(uFlow0, p, 0).xy) * uInvSize;
    vec3 b = texture(uPyr1, uv1).xyz;
    float inb = step(0.0, uv1.x) * step(0.0, uv1.y) * step(uv1.x, 1.0) * step(uv1.y, 1.0);
    o = vec4(b.x - a.x, 0.5 * (a.yz + b.yz), inb);
}"""

    /**
     * One Jacobi sweep of variational refinement around u0:
     *   E(u) = ψ(r²) + α Σₙ wₙ |u − uₙ|²,  r = Iₜ + ∇I·(u − u0)
     * with a Charbonnier data penalty ψ (re-weighted each sweep, so outliers lose influence) and
     * edge-aware neighbour weights wₙ (motion may change where the image does). Each pixel solves
     * its 2×2 normal equations exactly instead of a scalar Horn–Schunck update.
     */
    const val VARIATIONAL = """#version 300 es
precision highp float;
uniform sampler2D uFlow;
uniform sampler2D uFlow0;
uniform sampler2D uWarp;
uniform sampler2D uPyr0;
uniform float uAlpha;
out vec4 o;
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    ivec2 m = textureSize(uFlow, 0) - 1;
    vec2 u = texelFetch(uFlow, p, 0).xy;
    vec2 u0 = texelFetch(uFlow0, p, 0).xy;
    vec4 wd = texelFetch(uWarp, p, 0);
    float c = texelFetch(uPyr0, p, 0).r;
    vec2 g = wd.yz;

    float wsum = 0.0;
    vec2 usum = vec2(0.0);
    ivec2 nb[4] = ivec2[4](ivec2(1, 0), ivec2(-1, 0), ivec2(0, 1), ivec2(0, -1));
    for (int i = 0; i < 4; i++) {
        ivec2 q = clamp(p + nb[i], ivec2(0), m);
        float w = exp(-abs(texelFetch(uPyr0, q, 0).r - c) * 30.0);
        wsum += w;
        usum += w * texelFetch(uFlow, q, 0).xy;
    }

    float r = wd.x + dot(g, u - u0);
    // Charbonnier (ε = 0.01); zero where the match left the frame, so smoothness extrapolates.
    float psi = inversesqrt(r * r + 1e-4) * wd.w;
    float a = uAlpha * wsum;
    float k = wd.x - dot(g, u0);
    float h11 = psi * g.x * g.x + a, h12 = psi * g.x * g.y, h22 = psi * g.y * g.y + a;
    vec2 rhs = uAlpha * usum - psi * g * k;
    float det = h11 * h22 - h12 * h12;
    o = vec4(vec2(h22 * rhs.x - h12 * rhs.y, h11 * rhs.y - h12 * rhs.x) / det, 0.0, 1.0);
}"""

    // ---------------------------------------------------------------- synthesis

    /**
     * Forward-splats every pixel of frame A to its position at time uT along its flow, storing the
     * flow (in the 0→1 direction). Depth = how badly the pixel's correspondence disagrees
     * (photometric error + forward/backward inconsistency), so at collisions the pixel that is
     * visible in both frames wins: occlusion ordering without knowing scene depth.
     */
    const val SPLAT_VERTEX = """#version 300 es
precision highp float;
uniform sampler2D uFlowA;
uniform sampler2D uFlowB;
uniform sampler2D uPyrA;
uniform sampler2D uPyrB;
uniform float uT;
uniform float uSign;
uniform ivec2 uSize;
out vec2 vFlow;
void main() {
    ivec2 p = ivec2(gl_VertexID % uSize.x, gl_VertexID / uSize.x);
    vec2 inv = 1.0 / vec2(uSize);
    vec2 f = texelFetch(uFlowA, p, 0).xy;
    vec2 q = (vec2(p) + 0.5 + f) * inv;
    float photo = abs(texelFetch(uPyrA, p, 0).r - texture(uPyrB, q).r);
    float fb = length(f + texture(uFlowB, q).xy);
    bool inb = all(greaterThanEqual(q, vec2(0.0))) && all(lessThanEqual(q, vec2(1.0)));
    // Pixels whose match left the frame are still valid at time t; give them neutral depth.
    float depth = inb ? clamp(photo * 4.0 + fb * 0.05, 0.0, 1.0) : 0.5;
    vec2 pos = (vec2(p) + 0.5 + uT * f) * inv;
    gl_Position = vec4(pos * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    gl_PointSize = 2.0;
    vFlow = f * uSign;
}"""

    const val SPLAT_FRAGMENT = """#version 300 es
precision highp float;
in vec2 vFlow;
out vec4 o;
void main() { o = vec4(vFlow, 0.0, 1.0); }"""

    /** Fills splat holes (alpha 0) from valid neighbours at distance uStep (jump-flood style). */
    const val HOLE_FILL = """#version 300 es
precision highp float;
uniform sampler2D uFlow;
uniform int uStep;
out vec4 o;
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    ivec2 m = textureSize(uFlow, 0) - 1;
    vec4 c = texelFetch(uFlow, p, 0);
    if (c.a > 0.5) { o = c; return; }
    vec2 sum = vec2(0.0);
    float n = 0.0;
    for (int dy = -1; dy <= 1; dy++) {
        for (int dx = -1; dx <= 1; dx++) {
            vec4 s = texelFetch(uFlow, clamp(p + ivec2(dx, dy) * uStep, ivec2(0), m), 0);
            if (s.a > 0.5) { sum += s.xy; n += 1.0; }
        }
    }
    o = n > 0.0 ? vec4(sum / n, 0.0, 1.0) : vec4(0.0);
}"""

    /**
     * Final in-between frame. With u the flow at time t, frame 0 is sampled at x − t·u and frame 1
     * at x + (1−t)·u. A side is trusted only if its own flow there agrees with u (otherwise that
     * point is occluded in that frame), and the result reduces to Baker et al.'s rule in the hard
     * limit. Where neither side is trustworthy and their colours disagree, it degrades to a plain
     * cross-fade, the one fallback that can never tear.
     */
    const val COMPOSITE = """#version 300 es
precision highp float;
uniform sampler2D uFrame0;
uniform sampler2D uFrame1;
uniform sampler2D uFlowT;
uniform sampler2D uFlow01;
uniform sampler2D uFlow10;
uniform float uT;
uniform vec2 uFlowToUv;
uniform vec2 uFrameSize;
in vec2 vUv;
out vec4 o;

// Catmull–Rom bicubic in 9 bilinear taps: warps that land between pixels stay sharp instead of
// being averaged by bilinear filtering (the dominant softness at half-pixel offsets).
vec4 bicubic(sampler2D tex, vec2 uv) {
    vec2 sp = uv * uFrameSize;
    vec2 t1 = floor(sp - 0.5) + 0.5;
    vec2 f = sp - t1;
    vec2 w0 = f * (-0.5 + f * (1.0 - 0.5 * f));
    vec2 w1 = 1.0 + f * f * (-2.5 + 1.5 * f);
    vec2 w2 = f * (0.5 + f * (2.0 - 1.5 * f));
    vec2 w3 = f * f * (-0.5 + 0.5 * f);
    vec2 w12 = w1 + w2;
    vec2 t0 = (t1 - 1.0) / uFrameSize;
    vec2 t3 = (t1 + 2.0) / uFrameSize;
    vec2 t12 = (t1 + w2 / w12) / uFrameSize;
    vec4 r = texture(tex, vec2(t0.x, t0.y)) * w0.x * w0.y
           + texture(tex, vec2(t12.x, t0.y)) * w12.x * w0.y
           + texture(tex, vec2(t3.x, t0.y)) * w3.x * w0.y
           + texture(tex, vec2(t0.x, t12.y)) * w0.x * w12.y
           + texture(tex, vec2(t12.x, t12.y)) * w12.x * w12.y
           + texture(tex, vec2(t3.x, t12.y)) * w3.x * w12.y
           + texture(tex, vec2(t0.x, t3.y)) * w0.x * w3.y
           + texture(tex, vec2(t12.x, t3.y)) * w12.x * w3.y
           + texture(tex, vec2(t3.x, t3.y)) * w3.x * w3.y;
    return clamp(r, 0.0, 1.0);
}

float inside(vec2 uv) {
    return step(0.0, uv.x) * step(0.0, uv.y) * step(uv.x, 1.0) * step(uv.y, 1.0);
}

void main() {
    float t = uT;
    vec2 u = texture(uFlowT, vUv).xy;
    vec2 x0 = vUv - t * u * uFlowToUv;
    vec2 x1 = vUv + (1.0 - t) * u * uFlowToUv;
    vec2 e0 = texture(uFlow01, x0).xy - u;
    vec2 e1 = texture(uFlow10, x1).xy + u;
    // A side whose sample point left the frame never saw this content (pans, objects exiting).
    float in0 = inside(x0), in1 = inside(x1);
    float v0 = exp(-dot(e0, e0) * 0.5) * in0;
    float v1 = exp(-dot(e1, e1) * 0.5) * in1;
    vec4 c0 = bicubic(uFrame0, x0);
    vec4 c1 = bicubic(uFrame1, x1);
    float w0 = (1.0 - t) * (v0 + 1e-3 * in0) + 1e-6;
    float w1 = t * (v1 + 1e-3 * in1) + 1e-6;
    vec4 warped = (w0 * c0 + w1 * c1) / (w0 + w1);
    vec3 d = c0.rgb - c1.rgb;
    float agree = exp(-dot(d, d) * 50.0) * in0 * in1;
    float edge = max(in0 * (1.0 - in1), in1 * (1.0 - in0));   // exactly one side is valid
    float trust = clamp(max(max(max(v0, v1), agree) * 1.5, edge), 0.0, 1.0);
    vec4 blend = mix(texture(uFrame0, vUv), texture(uFrame1, vUv), t);
    o = mix(blend, warped, trust);
}"""

    /** Fraction of a 16×16 sample grid whose correspondence fails: ≈1 across a scene cut. */
    const val CUT_DETECT = """#version 300 es
precision highp float;
uniform sampler2D uFlow01;
uniform sampler2D uFlow10;
uniform sampler2D uPyr0;
uniform sampler2D uPyr1;
out vec4 o;
void main() {
    vec2 size = vec2(textureSize(uFlow01, 0));
    float bad = 0.0;
    for (int j = 0; j < 16; j++) {
        for (int i = 0; i < 16; i++) {
            vec2 uv = (vec2(float(i), float(j)) + 0.5) / 16.0;
            vec2 f = texture(uFlow01, uv).xy;
            vec2 q = uv + f / size;
            float photo = abs(texture(uPyr0, uv).r - texture(uPyr1, q).r);
            float fb = length(f + texture(uFlow10, q).xy);
            bad += (photo > 0.12 || fb > 3.0) ? 1.0 : 0.0;
        }
    }
    o = vec4(bad / 256.0, 0.0, 0.0, 1.0);
}"""
}
