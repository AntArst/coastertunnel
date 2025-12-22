// Tunnel Warp Shader - Audio Reactive Visualization
// Optimized for performance (Grid-based stars) & Fluidity
// Compatible with Bevy 0.15 Material2d

#import bevy_sprite::mesh2d_vertex_output::VertexOutput

struct TunnelMaterial {
    time: f32,
    bass: f32,
    mids: f32,
    treble: f32,
    resolution: vec2<f32>,
    // Extended spectrum data (8 bands)
    band0: f32,
    band1: f32,
    band2: f32,
    band3: f32,
    band4: f32,
    band5: f32,
    band6: f32,
    band7: f32,
    offset: f32, // Ring buffer head position
};

@group(2) @binding(0)
var<uniform> material: TunnelMaterial;
@group(2) @binding(1) 
var audio_history: texture_2d<f32>;
@group(2) @binding(2) 
var audio_sampler: sampler;

const PI: f32 = 3.14159265359;
const TAU: f32 = 6.28318530718;

// Fast pseudo-random hash
fn hash21(p: vec2<f32>) -> f32 {
    var p3 = fract(vec3<f32>(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

fn hash22(p: vec2<f32>) -> vec2<f32> {
    var p3 = fract(vec3<f32>(p.xyx) * vec3<f32>(0.1031, 0.1030, 0.0973));
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.xx + p3.yz) * p3.zy);
}

// HSV to RGB conversion
fn hsv_to_rgb(hsv: vec3<f32>) -> vec3<f32> {
    let h = hsv.x * 6.0;
    let s = hsv.y;
    let v = hsv.z;
    
    let i = floor(h);
    let f = h - i;
    let p = v * (1.0 - s);
    let q = v * (1.0 - s * f);
    let t = v * (1.0 - s * (1.0 - f));
    
    let ii = i32(i) % 6;
    
    if (ii == 0) { return vec3<f32>(v, t, p); }
    if (ii == 1) { return vec3<f32>(q, v, p); }
    if (ii == 2) { return vec3<f32>(p, v, t); }
    if (ii == 3) { return vec3<f32>(p, q, v); }
    if (ii == 4) { return vec3<f32>(t, p, v); }
    return vec3<f32>(v, p, q);
}

// Get band value by index
fn get_band(i: i32) -> f32 {
    if (i == 0) { return material.band0; }
    if (i == 1) { return material.band1; }
    if (i == 2) { return material.band2; }
    if (i == 3) { return material.band3; }
    if (i == 4) { return material.band4; }
    if (i == 5) { return material.band5; }
    if (i == 6) { return material.band6; }
    return material.band7;
}

fn get_history(band: i32, r: f32) -> f32 {
    // Map radius to history time.
    // Center (r=0) is NOW. Edge (r=0.5) is PAST.
    // Tuning scale: 0.5 radius = 0.5 texture width history
    let history_scale = 1.0; 
    let u_raw = material.offset - r * history_scale;
    let u = fract(u_raw + 10.0);
    
    // Row 0: 0-3. Row 1: 4-7.
    let row_idx = band / 4;
    let channel_idx = band % 4;
    
    // Y: 0.25 or 0.75
    let v = (f32(row_idx) + 0.5) / 2.0;
    
    let val_vec = textureSample(audio_history, audio_sampler, vec2<f32>(u, v));
    
    if (channel_idx == 0) { return val_vec.r; }
    if (channel_idx == 1) { return val_vec.g; }
    if (channel_idx == 2) { return val_vec.b; }
    return val_vec.a;
}

fn audio_energy() -> f32 {
    return (material.bass * 2.0 + material.mids * 1.5 + material.treble) / 4.5;
}

// Draw distinct oscillating waveform lines for each frequency
fn frequency_lines(angle: f32, depth: f32, r: f32, time: f32) -> vec3<f32> {
    var color = vec3<f32>(0.0);
    
    let shifted_angle = angle - PI/2.0;
    var norm_angle = fract(1.0 - shifted_angle / TAU); 
    
    let num_bands = 8.0;
    let band_idx_f = floor(norm_angle * num_bands);
    let band_idx = i32(band_idx_f);
    let band_center = (band_idx_f + 0.5) / num_bands;
    let sector_width = 1.0 / num_bands;
    let angle_diff = (norm_angle - band_center) / sector_width; 
    
    let band_val_now = get_band(band_idx);
    let history_val = get_history(band_idx, r);
    
    // Use magnitude history to modulate carrier wave
    // Direction: Center to Camera -> Travel = Depth growing + Time.
    // Wait, radius grows from center to camera. r goes 0->0.5.
    // So if history is sampled by r, the 'pulse' moves as r grows.
    
    // Carrier wave
    let travel = r * 20.0 - time * 10.0; // r grows, time grows -> phase constant?
    // If P = r*20 - t*10 = C -> r = (C + 10t)/20. r grows with time.
    // So wave moves OUTWARD. Yes.
    
    let carrier = sin(travel);
    
    // Displacement
    let displacement = carrier * history_val * 0.8;
    
    let dist_from_wave = abs(angle_diff - displacement);
    let line_width = 0.03 + band_val_now * 0.03;
    let line_intensity = smoothstep(line_width, 0.0, dist_from_wave);
    
    let hue = f32(band_idx) / num_bands;
    let depth_fade = smoothstep(0.0, 5.0, depth) * smoothstep(20.0, 10.0, depth);
    
    // Brightness highlights
    let brightness = line_intensity * (0.5 + history_val * 5.0);
    
    color = hsv_to_rgb(vec3<f32>(hue, 0.8, 1.0)) * brightness * depth_fade;
    
    let core = smoothstep(line_width * 0.2, 0.0, dist_from_wave);
    color += vec3<f32>(1.0) * core * brightness;
    
    return color;
}

// Optimized grid-based starfield
fn star_layer(uv: vec2<f32>, time: f32, scale: f32) -> vec3<f32> {
    let speed = 0.2 + audio_energy() * 0.5;
    let moving_uv = uv + vec2<f32>(0.0, time * speed);
    let id = floor(moving_uv * scale);
    let rect = fract(moving_uv * scale) - 0.5;
    var col = vec3<f32>(0.0);
    let rnd = hash22(id);
    let offset = (rnd - 0.5) * 0.8; 
    let d = length(rect - offset);
    let star_val = hash21(id);
    let size = 0.05 * star_val * (1.0 + material.treble);
    let glow = 0.05 / (d * 8.0 + 0.01) * smoothstep(1.0, 0.1, d);
    let core = smoothstep(size, size * 0.5, d);
    let twinkle = sin(time * 10.0 + star_val * 100.0) * 0.5 + 0.5;
    let brightness = (core + glow) * twinkle * star_val;
    let hue = fract(star_val + time * 0.1);
    col = hsv_to_rgb(vec3<f32>(hue, 0.5, brightness));
    return col;
}

// Warp stars
fn warp_stars(uv: vec2<f32>, time: f32) -> vec3<f32> {
    let centered = uv - 0.5;
    let r = length(centered);
    let a = atan2(centered.y, centered.x) / TAU + 0.5;
    let z = 1.0 / max(r, 0.001);
    let speed = 0.5 + material.bass * 12.0;
    let uv_map = vec2<f32>(a * 8.0, z - time * speed); 
    var col = vec3<f32>(0.0);
    col += star_layer(uv_map, time * 0.0, 1.0);
    col += star_layer(uv_map + vec2<f32>(0.5, 0.5), time * 0.0, 2.0) * 0.5;
    col *= smoothstep(0.0, 2.0, z); 
    col *= smoothstep(20.0, 5.0, z);
    return col;
}

@fragment
fn fragment(in: VertexOutput) -> @location(0) vec4<f32> {
    let uv = in.uv;
    var centered = uv - 0.5;
    centered.x *= material.resolution.x / material.resolution.y;
    
    let r = length(centered);
    let a = atan2(centered.y, centered.x);
    
    // 1. Background / Void
    var color = vec3<f32>(0.0, 0.0, 0.02);
    
    // 2. Radial Frequency Lines
    let depth = 0.5 / max(r, 0.001);
    color += frequency_lines(a, depth, r, material.time);
    
    // 3. Streaming Stars
    color += warp_stars(in.uv, material.time);
    
    // 4. Center Singularity
    let core_radius = 0.05 + material.bass * 0.05;
    let core_glow = 0.02 / abs(r - core_radius * 0.5);
    color += vec3<f32>(1.0, 0.8, 0.5) * core_glow * smoothstep(0.5, 0.0, r);
    color += vec3<f32>(1.0) * smoothstep(core_radius, 0.0, r);
    
    // Vignette
    color *= 1.0 - smoothstep(0.5, 1.5, r);
    
    // Tone mapping
    color = 1.0 - exp(-color);
    
    return vec4<f32>(color, 1.0);
}
