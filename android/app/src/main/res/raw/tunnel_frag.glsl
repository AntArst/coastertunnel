#version 300 es
precision highp float;

in vec2 vUv;
out vec4 fragColor;

uniform float uTime;
uniform vec3 uAudio; // bass, mids, treble
uniform float uBands[32]; // Increased to 32

uniform vec2 uResolution;
uniform sampler2D uAudioHistory;
uniform float uOffset;

const float PI = 3.14159265359;
const float TAU = 6.28318530718;

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

// HSV to RGB conversion
vec3 hsv_to_rgb(vec3 hsv) {
    float h = hsv.x * 6.0;
    float s = hsv.y;
    float v = hsv.z;
    float i = floor(h);
    float f = h - i;
    float p = v * (1.0 - s);
    float q = v * (1.0 - s * f);
    float t = v * (1.0 - s * (1.0 - f));
    int ii = int(i) % 6;
    if (ii == 0) return vec3(v, t, p);
    if (ii == 1) return vec3(q, v, p);
    if (ii == 2) return vec3(p, v, t);
    if (ii == 3) return vec3(p, q, v);
    if (ii == 4) return vec3(t, p, v);
    return vec3(v, p, q);
}

float get_band(int i) {
    if (i >= 0 && i < 32) return uBands[i];
    return 0.0;
}

float get_history(int band, float r) {
    float history_scale = 1.0;
    float u = fract(uOffset - r * 0.1); 
    
    // 32 bands packed into 8 rows of 4 channels
    int row_idx = band / 4;
    int channel_idx = band % 4;
    
    // Center of pixel row (texture height is 8)
    float v = (float(row_idx) + 0.5) / 8.0;
    
    vec4 val_vec = texture(uAudioHistory, vec2(u, v));
    if (channel_idx == 0) return val_vec.r;
    if (channel_idx == 1) return val_vec.g;
    if (channel_idx == 2) return val_vec.b;
    return val_vec.a;
}

float audio_energy() {
    return (uAudio.x * 2.0 + uAudio.y * 1.5 + uAudio.z) / 4.5;
}

vec3 frequency_lines(float angle, float depth, float r, float time) {
    vec3 color = vec3(0.0);
    float shifted_angle = angle - PI/2.0;
    float norm_angle = fract(1.0 - shifted_angle / TAU);
    float num_bands = 32.0; // Increased to 32
    float band_idx_f = floor(norm_angle * num_bands);
    int band_idx = int(band_idx_f);
    float band_center = (band_idx_f + 0.5) / num_bands;
    float sector_width = 1.0 / num_bands;
    float angle_diff = (norm_angle - band_center) / sector_width;
    
    // Wrap around fix for angle diff
    // Not needed if we use simple sector logic, but let's be safe
    
    float band_val_now = get_band(band_idx);
    float history_val = get_history(band_idx, r);
    // Map band index to wave frequency (pitch representation)
    // Bass (low index) = wide waves (low spatial freq)
    // Treble (high index) = tight waves (high spatial freq)
    float wave_spatial_freq = 10.0 + float(band_idx) * 2.5;
    float wave_speed = 5.0 + float(band_idx) * 0.5;

    float travel = r * wave_spatial_freq - time * wave_speed;
    float carrier = sin(travel);
    
    float displacement = carrier * history_val * 0.5;
    
    float dist_from_wave = abs(angle_diff - displacement);
    
    // Thinner lines for higher resolution
    float line_width = 0.01 + band_val_now * 0.05; 
    float line_intensity = smoothstep(line_width, 0.0, dist_from_wave);
    
    float hue = float(band_idx) / num_bands;
    
    float depth_fade = smoothstep(0.0, 5.0, depth) * (1.0 - smoothstep(10.0, 20.0, depth));
    
    float brightness = line_intensity * (0.5 + history_val * 5.0);
    
    // Different color mapping? 
    // Hue from 0 to 1 covers Red->Green->Blue->Red
    // Let's keep simpler HSV
    color = hsv_to_rgb(vec3(hue, 0.8, 1.0)) * brightness * depth_fade;
    
    float core = smoothstep(line_width * 0.2, 0.0, dist_from_wave);
    color += vec3(1.0) * core * brightness;
    return color;
}

vec3 star_layer(vec2 uv, float time, float scale) {
    float speed = 0.2 + audio_energy() * 0.5;
    vec2 moving_uv = uv + vec2(0.0, time * speed);
    vec2 id = floor(moving_uv * scale);
    vec2 rect = fract(moving_uv * scale) - 0.5;
    vec2 rnd = hash22(id);
    vec2 offset = (rnd - 0.5) * 0.8;
    float d = length(rect - offset);
    float star_val = hash21(id);
    float size = 0.05 * star_val * (1.0 + uAudio.z);
    float glow = 0.05 / (d * 8.0 + 0.01) * smoothstep(1.0, 0.1, d);
    float core = smoothstep(size, size * 0.5, d);
    float twinkle = sin(time * 10.0 + star_val * 100.0) * 0.5 + 0.5;
    float brightness = (core + glow) * twinkle * star_val;
    float hue = fract(star_val + time * 0.1);
    return hsv_to_rgb(vec3(hue, 0.5, brightness));
}

vec3 warp_stars(vec2 uv, float time) {
    vec2 centered = uv - 0.5;
    float r = length(centered);
    float a = atan(centered.y, centered.x) / TAU + 0.5;
    float z = 1.0 / max(r, 0.001);
    float speed = 0.5 + uAudio.x * 12.0;
    vec2 uv_map = vec2(a * 8.0, z - time * speed);
    vec3 col = star_layer(uv_map, time * 0.0, 1.0);
    col += star_layer(uv_map + vec2(0.5, 0.5), time * 0.0, 2.0) * 0.5;
    col *= smoothstep(0.0, 2.0, z);
    col *= smoothstep(20.0, 5.0, z);
    return col;
}

void main() {
    vec2 uv = vUv;
    vec2 centered = uv - 0.5;
    centered.x *= uResolution.x / uResolution.y;
    float r = length(centered);
    float a = atan(centered.y, centered.x);
    vec3 color = vec3(0.0, 0.0, 0.02);
    float depth = 0.5 / max(r, 0.001);
    color += frequency_lines(a, depth, r, uTime);
    color += warp_stars(uv, uTime);
    float core_radius = 0.05 + uAudio.x * 0.05;
    float core_glow = 0.02 / abs(r - core_radius * 0.5);
    color += vec3(1.0, 0.8, 0.5) * core_glow * smoothstep(0.5, 0.0, r);
    color += vec3(1.0) * smoothstep(core_radius, 0.0, r);
    color *= 1.0 - smoothstep(0.5, 1.5, r);
    color = 1.0 - exp(-color);
    fragColor = vec4(color, 1.0);
}
