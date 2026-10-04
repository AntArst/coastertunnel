#version 300 es
precision highp float;

in vec2 vUv;
out vec4 fragColor;

// uTime is wrapped to [0, TAU) on the CPU so it never loses precision. Every
// use of it must therefore be periodic in TAU: multiply it by whole numbers only.
uniform float uTime;
uniform vec3 uAudio; // bass, mids, treble
uniform float uBands[32];

uniform vec2 uResolution;
uniform sampler2D uAudioHistory;
uniform float uOffset;     // history ring-buffer read head, already on a texel centre
uniform float uRotation;   // rigid spin, wrapped to [0, TAU)
uniform float uSpinVel;    // smoothed spin velocity (rad/s), bends the lines into a lean
uniform float uStarTime;   // star/ring travel, wrapped to [0, STAR_PERIOD)
uniform float uSpeed;      // tempo factor, 1.0 at 60 BPM
uniform float uBeatAge;    // seconds since the last detected beat

const float PI = 3.14159265359;
const float TAU = 6.28318530718;
const float NUM_BANDS = 32.0;
const float STAR_PERIOD = 256.0; // must match TunnelRenderer.STAR_PERIOD

// Fast pseudo-random hash
float hash21(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

vec2 hash22(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * vec3(0.1031, 0.1030, 0.0973));
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.xx + p3.yz) * p3.zy);
}

// Smooth spectral hue: a cubic-eased HSV wheel at full saturation and value,
// which avoids the bright creases plain HSV has at the primaries.
vec3 spectrum(float h) {
    vec3 c = clamp(abs(mod(h * 6.0 + vec3(0.0, 4.0, 2.0), 6.0) - 3.0) - 1.0, 0.0, 1.0);
    return c * c * (3.0 - 2.0 * c);
}

// Coverage of a line of half-width w at distance d, anti-aliased over one pixel.
// Lines thinner than a pixel keep their width at one pixel and dim instead, so
// they fade out rather than breaking up into dotted, shimmering fragments.
float line_cover(float d, float w, float px) {
    float wa = max(w, px * 0.5);
    return (w / wa) * (1.0 - smoothstep(wa - px * 0.5, wa + px * 0.5, d));
}

float get_history(int band, float r) {
    float u = fract(uOffset - r * 0.1);
    // 32 bands packed into 8 rows of 4 channels
    float v = (float(band / 4) + 0.5) / 8.0;
    // Two taps half a texel apart smooth the corners that plain linear
    // filtering leaves between audio frames, which show as kinks in the lines
    float texel_w = 1.0 / float(textureSize(uAudioHistory, 0).x);
    vec4 texel = texture(uAudioHistory, vec2(u - 0.5 * texel_w, v))
               + texture(uAudioHistory, vec2(u + 0.5 * texel_w, v));
    return 0.5 * dot(texel, vec4(equal(ivec4(band % 4), ivec4(0, 1, 2, 3))));
}

// One coloured line per band, rippling with that band's recent history. The
// pixel's own sector and both neighbours are evaluated so that wide lines and
// their glow cross sector borders instead of being clipped by them.
vec3 frequency_lines(float angle, float r, float px, float time) {
    float f = fract(1.25 - angle / TAU) * NUM_BANDS;
    float idx_f = floor(f);
    float angle_diff = f - idx_f - 0.5;     // [-0.5, 0.5] across the sector
    float sector = TAU * r / NUM_BANDS;     // arc length of one sector

    vec3 color = vec3(0.0);
    for (int k = -1; k <= 1; k++) {
        int band = (int(idx_f) + k + 32) % 32;
        float fb = float(band);
        float now = uBands[band];
        float hist = get_history(band, r);

        // Pitch sets the ripple frequency and speed, recent amplitude its size.
        // Both speeds are whole numbers so the wave survives uTime wrapping.
        float spatial = 15.0 + fb * 4.0;
        float travel = r * spatial - time * (8.0 + fb * 3.0);
        float amp = hist * 0.22;
        float disp = sin(travel) * amp;

        // Distance across the line, corrected for the slope of the ripple so
        // steep sections keep the same thickness as flat ones.
        float slope = sector * cos(travel) * amp * spatial;
        float d = abs(angle_diff - float(k) - disp) * sector / sqrt(1.0 + slope * slope);

        // Loudness sets thickness. Widths scale with the sector, so lines
        // thicken as they approach the viewer.
        float w = (0.012 + now * 0.05) * sector;
        float body = line_cover(d, w, px);
        float hot = line_cover(d, w * 0.3, px);

        // Soft halo, kept inside a third of a sector so neighbours never clip it
        float glow_len = min(0.004 + now * 0.006, sector * 0.3);
        float glow = exp(-d / glow_len);

        float energy = 0.35 + hist * 2.6 + now * 0.8;
        vec3 hue = spectrum(fb / NUM_BANDS);
        color += hue * (body * 1.2 + glow * 0.22) * energy;
        color += vec3(1.0) * hot * hist * hist * 1.6;
    }

    // Fog toward the vanishing point, and ease off slightly at the screen edge
    float fade = smoothstep(0.035, 0.17, r) * (1.0 - 0.35 * smoothstep(0.45, 0.95, r));
    return color * fade;
}

// Rings of the tunnel wall rushing past at the tempo; brighter on each beat.
vec3 tunnel_rings(float r, float px, float travel, float beat) {
    float v = (1.0 / r + travel) * 0.5;
    float dv_dr = 0.5 / (r * r);
    float d = abs(fract(v + 0.5) - 0.5) / dv_dr;   // radial distance to the nearest ring
    float spacing = 1.0 / dv_dr;

    float w = 0.0006 + 0.0035 * r;
    float body = line_cover(d, w, px);
    float glow = exp(-d / min(0.02 * r, spacing * 0.2));

    // Fade out where rings bunch up closer than a few pixels apart
    float density = smoothstep(3.0 * px, 12.0 * px, spacing);
    float strength = (0.05 + uAudio.x * 0.12 + beat * 0.35) * density;
    vec3 tint = mix(vec3(0.35, 0.3, 1.0), vec3(1.0, 0.45, 0.8), beat);
    return tint * (body * 1.4 + glow * 0.35) * strength;
}

// One layer of stars flying out of the tunnel, streaking radially with speed.
vec3 star_layer(float s, float r, float px, float travel, float cells, float seed) {
    vec2 g = vec2(s * cells, 1.0 / r + travel);
    vec2 id = floor(g);
    id.y = mod(id.y, STAR_PERIOD);  // seamless when uStarTime wraps
    id += seed;
    vec2 q = fract(g) - 0.5 - (hash22(id) - 0.5) * 0.5;

    // Cell size on screen: tangential and radial
    vec2 cell = vec2(TAU * r / cells, r * r);
    vec2 dp = q * cell;

    float star_val = hash21(id);
    float size = (0.0007 + 0.0016 * star_val) * (r * 2.5) * (1.0 + uAudio.z * 0.6);
    float streak = size * (1.0 + uSpeed * 2.5);
    // Keep each star well inside its cell so no edges are cut off
    vec2 radius = min(vec2(size, streak), cell * 0.12);
    vec2 aa = max(radius, vec2(px * 0.6));
    float e = length(dp / aa);
    float energy = (radius.x * radius.y) / (aa.x * aa.y);
    float shape = exp(-e * e * 2.0) * energy;

    float twinkle = 0.65 + 0.35 * sin(uTime * 3.0 + star_val * 50.0);
    vec3 tint = mix(vec3(0.7, 0.8, 1.0), spectrum(fract(star_val * 7.0)), 0.35);
    float visible = step(0.35, star_val);  // thin out the field
    return tint * shape * twinkle * visible * (1.5 + star_val * 2.5);
}

vec3 warp_stars(float angle, float r, float px, float travel) {
    float s = fract(angle / TAU);
    vec3 col = star_layer(s, r, px, travel, 14.0, 0.0);
    col += star_layer(s, r, px, travel + 0.5, 26.0, 17.0) * 0.6;
    return col * smoothstep(0.04, 0.2, r);
}

vec3 core(float r, float px, float beat) {
    float core_r = 0.038 + uAudio.x * 0.03 + beat * 0.012;
    vec3 warm = vec3(1.0, 0.72, 0.42);
    vec3 col = vec3(0.0);
    // Wide warm halo that breathes with the bass
    col += warm * 0.03 / (r + 0.03) * exp(-r * 3.5) * (0.6 + uAudio.x * 0.8);
    // Bright rim, bounded so it never blows up into a singular ring
    col += mix(warm, vec3(1.0), 0.5) * 0.004 / (abs(r - core_r) + 0.004 + px) * 0.7;
    // White-hot disc
    col += vec3(1.0) * (1.0 - smoothstep(core_r * 0.4, core_r + px, r)) * 1.6;
    // Shockwave on each beat, expanding out of the core
    float wave_r = core_r + uBeatAge * 0.9;
    float wave_w = 0.005 + uBeatAge * 0.02;
    float wave_d = (r - wave_r) / wave_w;
    float wave = exp(-wave_d * wave_d) * exp(-uBeatAge * 4.0);
    col += vec3(0.85, 0.6, 1.0) * wave * 0.5;
    return col;
}

void main() {
    vec2 centered = vUv - 0.5;
    centered.x *= uResolution.x / uResolution.y;
    float r = max(length(centered), 1e-4);
    float px = 1.0 / uResolution.y;   // one pixel in these units
    float a = atan(centered.y, centered.x);
    float beat = exp(-uBeatAge * 6.0);

    // Rigid spin, plus a lean toward the spin direction that is strongest at
    // the centre. The lean follows the velocity, so it stays bounded.
    float spun = a + uRotation;
    float lean = clamp(uSpinVel, -3.0, 3.0) * 0.8 * (1.0 - smoothstep(0.0, 0.9, r));

    // Deep-space backdrop that lightens toward the tunnel mouth
    vec3 color = vec3(0.004, 0.002, 0.018)
               + vec3(0.02, 0.008, 0.05) * smoothstep(0.1, 1.0, r) * (0.7 + uAudio.y * 0.6);

    color += tunnel_rings(r, px, uStarTime, beat) * smoothstep(0.05, 0.2, r);
    color += warp_stars(spun, r, px, uStarTime);
    color += frequency_lines(spun + lean, r, px, uTime);
    color += core(r, px, beat);

    // Vignette, tone map, then dither to hide 8-bit banding in the dark glows
    color *= 1.0 - 0.85 * smoothstep(0.55, 1.4, r);
    color = 1.0 - exp(-color);
    color += (hash21(gl_FragCoord.xy) - 0.5) / 255.0;
    fragColor = vec4(color, 1.0);
}
