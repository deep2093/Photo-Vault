package com.deepak.photovault.designsystem

/**
 * Liquid Glass as iOS draws it (21 Nov, Deepak: "transparent glossy elements, like water droplets, with depth; no
 * frost or blur of any kind"). Two AGSL shaders for Android 13 and newer share the glass below:
 *
 * - [LENS] is a floating bar, pill or menu. It looks through to the photos behind it. Nothing is blurred. Across its
 *   rounded edge the glass rises like a squircle (the profile iOS uses), and the picture is bent by Snell's law with
 *   glass's refractive index (1.5), so content near the edge is pulled in and magnified like under a droplet. Red and
 *   blue bend a little differently there, so high-contrast edges show a faint rainbow.
 * - [EDGE] is the same glass edge for panels and buttons that sit on the calm page, where there is nothing to bend.
 *   It is drawn as a brush, under the words.
 *
 * Light falls from the top left (Clear Glass: wherever tilting the phone moves it, `lightDir`). The rounded edge catches
 * it as a thin bright line (Fresnel: glass reflects most where it is steepest), with a soft gloss on the lit side and a
 * smaller glint opposite. A faint shade inside the edge, away from the light, gives the glass its thickness. Light
 * focused through the edge brightens the far side a little.
 *
 * The words on the glass stay readable without frost: by day the picture behind is brightened just enough for dark
 * words, keeping its colour where it can; by night it is dimmed like smoked glass for white words. [LiquidGlassInk]
 * is the same maths in Kotlin, and ThemeContrastTest checks the words against it. None of the light or shade reaches
 * the middle of the glass, where words sit, so the test holds for every pixel there.
 */
object LiquidGlassAgsl {
    private const val UNIFORMS = """
uniform float2 size;
uniform float radius;
uniform float dp;
uniform float darkMode;
uniform float2 lightDir;
"""

    private const val GLASS = """
// Distance to the edge of the rounded rectangle; negative inside.
float edgeDistance(float2 p, float2 hs, float r) {
    float2 q = abs(p) - hs + float2(r);
    return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - r;
}

// The direction straight out through the nearest edge.
float2 outward(float2 p, float2 hs, float r) {
    float2 s = step(float2(0.0), p) * 2.0 - 1.0;
    float2 q = abs(p) - hs + float2(r);
    if (q.x > 0.0 && q.y > 0.0) {
        return normalize(q) * s;
    }
    return q.x > q.y ? float2(s.x, 0.0) : float2(0.0, s.y);
}

// Across the rounded edge the glass rises like a squircle: 0 at the edge, 1 where it turns flat (x = 1).
float bezelHeight(float x) {
    float a = 1.0 - x;
    return pow(max(1.0 - a * a * a * a, 0.0), 0.25);
}

// How steeply it rises there.
float bezelSlope(float x) {
    float a = 1.0 - x;
    float a3 = a * a * a;
    return a3 / pow(max(1.0 - a3 * a, 0.00001), 0.75);
}

// 1 for a round button (a whole droplet), small for a long bar (a flat middle).
float roundnessOf(float2 hs) {
    return min(hs.x, hs.y) / max(hs.x, hs.y);
}

// How wide the rounded edge is.
float bezelWidth(float2 hs, float r) {
    float k = roundnessOf(hs);
    return max(1.0, min(min(r, 24.0 * dp), min(hs.x, hs.y) * mix(0.6, 0.92, k * k)));
}

// Light on the glass at x across its edge (0 at the edge), facing n, rising at slope.
// Returns (shade, caustic, light): how much to darken, then brighten from below, then light from above.
float3 glassLight(float2 n, float x, float slope, float panel) {
    float3 N = normalize(float3(n * slope, 1.0));
    float3 L = normalize(float3(lightDir, 0.5));
    float3 H = normalize(L + float3(0.0, 0.0, 1.0));
    float3 H2 = normalize(float3(-L.x, -L.y, L.z) + float3(0.0, 0.0, 1.0));
    float gloss = pow(max(dot(N, H), 0.0), 40.0);
    float glint = pow(max(dot(N, H2), 0.0), 60.0);
    float fresnel = pow(max(1.0 - N.z, 0.0), 5.0);
    float facing = dot(n, normalize(L.xy));
    float toLight = max(facing, 0.0);
    float away = max(-facing, 0.0);
    float rimLight = fresnel * (0.25 + 0.75 * toLight * toLight + 0.35 * away * away);
    float night = clamp(darkMode, 0.0, 1.0);
    float zone = 1.0 - smoothstep(0.3, 0.45, x);
    float band = panel > 0.5
        ? smoothstep(0.0, 0.07, x) * (1.0 - smoothstep(0.12, 0.4, x))
        : smoothstep(0.02, 0.12, x) * (1.0 - smoothstep(0.25, 0.45, x));
    float depthShade = panel > 0.5 ? mix(0.07, 0.12, night) : mix(0.08, 0.14, night);
    float shade = band * (0.35 + 0.65 * away) * depthShade * zone;
    float caustic = smoothstep(0.08, 0.2, x) * zone * away * away * mix(0.10, 0.02, night);
    float light = clamp(
        (gloss * mix(0.40, 0.24, night) + glint * mix(0.22, 0.14, night)) * zone + rimLight * mix(0.85, 0.60, night),
        0.0, 1.0);
    return float3(shade, caustic, light);
}
"""

    /** A floating bar's glass: the photos behind, bent at the edge, never blurred. */
    const val LENS = UNIFORMS + """
uniform shader content;
uniform float3 backdrop;
uniform float inkFloor;
uniform float inkCeiling;
uniform float frost;
uniform float3 frostColor;
""" + GLASS + """
// What is behind the glass here. Where the recorded content is see-through, the page's own colour shows.
float3 scene(float2 p) {
    float4 pixel = float4(content.eval(clamp(p, float2(0.5), max(float2(0.5), size - 0.5))));
    return pixel.rgb + backdrop * (1.0 - pixel.a);
}

float3 srgbToLinear(float3 c) {
    c = clamp(c, 0.0, 1.0);
    return mix(c / 12.92, pow((c + 0.055) / 1.055, float3(2.4)), step(float3(0.04045), c));
}

float3 linearToSrgb(float3 c) {
    c = clamp(c, 0.0, 1.0);
    return mix(c * 12.92, 1.055 * pow(c, float3(1.0 / 2.4)) - 0.055, step(float3(0.0031308), c));
}

// The ink rule (LiquidGlassInk in Kotlin): by day at least inkFloor of light behind dark words, keeping the colour
// where it can; by night at most inkCeiling behind white words, like smoked glass. Clear Glass's bars change over
// between the two smoothly (darkMode between 0 and 1). amount < 1 only at the edge.
float3 toneForInk(float3 lin, float amount) {
    float luma = max(dot(lin, float3(0.2126, 0.7152, 0.0722)), 0.00001);
    float nightTarget = inkCeiling * (1.0 - exp(-luma / inkCeiling));
    float3 night = lin * (nightTarget / luma);
    float dayTarget = inkFloor + (1.0 - inkFloor) * luma;
    float brightest = max(max(lin.r, max(lin.g, lin.b)), 0.00001);
    float gain = min(min(dayTarget / luma, 1.0 / brightest), 3.0);
    float lifted = luma * gain;
    float3 day = mix(lin * gain, float3(1.0), max(0.0, (dayTarget - lifted) / max(1.0 - lifted, 0.00001)));
    float3 toned = mix(day, night, clamp(darkMode, 0.0, 1.0));
    return mix(lin, toned, amount);
}

// Where a ray looking straight down lands after entering the glass (Snell's law), relative to where it entered.
float2 refractShift(float2 n, float slope, float height, float ior) {
    float3 N = normalize(float3(n * slope, 1.0));
    float eta = 1.0 / ior;
    float c = N.z;
    float k = 1.0 - eta * eta * (1.0 - c * c);
    float3 t = float3(0.0, 0.0, -eta) + (eta * c - sqrt(max(k, 0.0))) * N;
    return t.xy * (height / max(-t.z, 0.1));
}

half4 main(float2 coord) {
    float2 hs = size * 0.5;
    float2 p = coord - hs;
    float r = clamp(radius, 0.0, min(hs.x, hs.y));
    float depth = max(0.0, -edgeDistance(p, hs, r));
    float2 n = outward(p, hs, r);
    float k = roundnessOf(hs);
    float bezel = bezelWidth(hs, r);
    float thick = bezel * mix(0.8, 1.0, k);
    float x = clamp(depth / bezel, 0.0, 1.0);
    float slope = (thick / bezel) * bezelSlope(max(x, 0.001));
    float height = thick * bezelHeight(x);
    // A droplet magnifies a little all over; a long bar hardly at all.
    float2 base = hs + p * (1.0 - mix(0.012, 0.05, k * k));
    float2 shift = refractShift(n, slope, height, 1.5);
    float3 rgb = scene(base + shift);
    if (length(shift) > 0.35 * dp) {
        rgb.r = scene(base + refractShift(n, slope, height, 1.472)).r;
        rgb.b = scene(base + refractShift(n, slope, height, 1.54)).b;
    }
    float rim = 1.0 - smoothstep(0.08, 0.32, x);
    float3 lin = toneForInk(srgbToLinear(rgb), 1.0 - 0.45 * rim);
    // Clear Glass: Tinted's firmer film, or the chosen tab's (0 for Liquid Glass).
    lin = mix(lin, frostColor, frost);
    float3 l = glassLight(n, x, slope, 0.0);
    lin = lin * (1.0 - l.x);
    lin = lin + (1.0 - lin) * l.y;
    lin = lin + (1.0 - lin) * l.z;
    return half4(half3(linearToSrgb(lin)), 1.0);
}
"""

    /** The glass edge alone, for panels and buttons on the page: white light over a faint shade, premultiplied. */
    const val EDGE = UNIFORMS + GLASS + """
half4 main(float2 coord) {
    float2 hs = size * 0.5;
    float2 p = coord - hs;
    float r = clamp(radius, 0.0, min(hs.x, hs.y));
    float d = edgeDistance(p, hs, r);
    if (d > 1.0) {
        return half4(0.0);
    }
    float bezel = bezelWidth(hs, r);
    float x = max(0.0, -d) / bezel;
    if (x >= 1.0) {
        return half4(0.0);
    }
    float2 n = outward(p, hs, r);
    float thick = bezel * mix(0.8, 1.0, roundnessOf(hs));
    float slope = (thick / bezel) * bezelSlope(max(x, 0.001));
    float3 l = glassLight(n, x, slope, 1.0);
    float cover = clamp(0.5 - d, 0.0, 1.0);
    float light = l.z * cover;
    float alpha = light + l.x * cover * (1.0 - light);
    return half4(half3(light), half(alpha));
}
"""
}
