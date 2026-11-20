package com.deepak.photovault.ui.shell

/**
 * A rounded-rectangle signed distance field models the glass. Its gradient points out of the bevel, so
 * sampling back along that gradient gives the rim its lensing. Controls are composited separately.
 */
internal const val LIQUID_GLASS_SHADER = """
uniform shader content;
uniform float2 size;
uniform float radius;
uniform float dp;
uniform float darkMode;
uniform float3 backdrop;

float edgeDistance(float2 p, float2 halfSize, float r) {
    float2 q = abs(p) - halfSize + float2(r);
    return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - r;
}

half3 scene(float2 p) {
    half4 pixel = content.eval(clamp(p, float2(0.5), max(float2(0.5), size - 0.5)));
    return pixel.rgb + half3(backdrop) * (1.0 - pixel.a);
}

// Pixallery draws its gallery chrome in SDR. Decode its sRGB transfer curve explicitly so
// the contrast behavior is the same when the intermediate blur surface carries no color tag.
float3 srgbToLinear(float3 rgb) {
    rgb = clamp(rgb, 0.0, 1.0);
    return mix(rgb / 12.92, pow((rgb + 0.055) / 1.055, float3(2.4)), step(float3(0.04045), rgb));
}

float3 linearToSrgb(float3 rgb) {
    return mix(rgb * 12.92, 1.055 * pow(rgb, float3(1.0 / 2.4)) - 0.055, step(float3(0.0031308), rgb));
}

half3 toneForInk(half3 rgb) {
    float3 linear = srgbToLinear(float3(rgb));
    float luma = dot(linear, float3(0.2126, 0.7152, 0.0722));
    if (darkMode > 0.5) {
        float target = 0.124 * (1.0 - exp(-luma / 0.124));
        linear *= target / max(luma, 0.0001);
    } else {
        float target = 0.35 + 0.65 * luma;
        linear = mix(linear, float3(1.0), (target - luma) / max(1.0 - luma, 0.0001));
    }
    return half3(linearToSrgb(linear));
}

half4 main(float2 coord) {
    float2 hs = size * 0.5;
    float2 p = coord - hs;
    float r = clamp(radius, 0.0, min(hs.x, hs.y));
    float depth = max(0.0, -edgeDistance(p, hs, r));
    float2 q = abs(p) - hs + float2(r);
    float2 outer = max(q, float2(0.0));
    float2 n = q.x > q.y ? float2(sign(p.x), 0.0) : float2(0.0, sign(p.y));
    if (length(outer) > 0.001) n = normalize(outer) * sign(p);
    float bevel = max(1.0, min(min(hs.x, hs.y) * 0.72, 13.0 * dp));
    float bulge = 1.0 - smoothstep(0.0, bevel, depth);
    float bend = bulge * bulge * bevel * 0.88;
    float2 samplePos = hs + (p - n * bend) * 0.975;
    half3 rgb = scene(samplePos);

    // Dispersion is confined to the polished edge. The body and the typography stay neutral.
    if (bulge > 0.08) {
        float2 split = n * bulge * 0.80 * dp;
        rgb.r = mix(rgb.r, scene(samplePos - split).r, half(bulge * 0.28));
        rgb.b = mix(rgb.b, scene(samplePos + split).b, half(bulge * 0.28));
    }

    half3 base = toneForInk(rgb);
    float facing = dot(n, float2(-0.62, -0.7846));
    float topLight = pow(max(facing, 0.0), 4.0);
    float bottomLight = pow(max(-facing, 0.0), 5.0);
    float hairline = 1.0 - smoothstep(0.35 * dp, 1.45 * dp, depth);
    float gleam = exp(-depth / max(dp, 1.0));
    float inner = smoothstep(0.8 * dp, 2.0 * dp, depth) * (1.0 - smoothstep(2.0 * dp, 5.5 * dp, depth));

    float polish = hairline * (0.14 + 0.53 * topLight + 0.46 * bottomLight) +
        gleam * (0.05 + 0.13 * topLight);
    float shade = inner * (0.035 + 0.14 * bottomLight + 0.09 * topLight);
    base = mix(base, half3(0.025, 0.034, 0.05), half(shade));
    base = mix(base, half3(1.0), half(polish));
    return half4(base, 1.0);
}
"""
