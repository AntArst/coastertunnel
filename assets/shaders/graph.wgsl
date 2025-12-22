#import bevy_ui::ui_vertex_output::UiVertexOutput

struct GraphMaterial {
    color: vec4<f32>,
    offset: f32,
    _padding: vec3<f32>, // Padding for alignment
}

@group(1) @binding(0) var<uniform> material: GraphMaterial;
@group(1) @binding(1) var history_texture: texture_2d<f32>;
@group(1) @binding(2) var history_sampler: sampler;

@fragment
fn fragment(in: UiVertexOutput) -> @location(0) vec4<f32> {
    // Background
    var color = vec4<f32>(0.0, 0.0, 0.0, 0.8);
    
    // Time mapping
    // offset is the head (newest data).
    // uv.x = 1.0 (Right) should be Head.
    // uv.x = 0.0 (Left) should be Tail.
    // sampler u should wrap.
    // u = (offset - (1.0 - uv.x)) % 1.0
    //   = offset - 1.0 + uv.x
    //   = offset + uv.x (fract will handle the -1.0)
    
    let u = fract(material.offset + in.uv.x);
    
    // Sample texture
    let row0 = textureSample(history_texture, history_sampler, vec2<f32>(u, 0.25));
    let row1 = textureSample(history_texture, history_sampler, vec2<f32>(u, 0.75));
    
    // Graph Y coordinate (0 at bottom)
    let y = 1.0 - in.uv.y;
    let thickness = 0.01;
    let blur = 0.005;
    
    // Plot Band 0 (Bass) - Red
    let val0 = row0.r;
    let d0 = abs(y - val0);
    let alpha0 = smoothstep(thickness + blur, thickness, d0);
    color = mix(color, vec4<f32>(1.0, 0.3, 0.3, 1.0), alpha0);
    
    // Plot Band 3 (Mids) - Green
    let val3 = row0.a;
    let d3 = abs(y - val3);
    let alpha3 = smoothstep(thickness + blur, thickness, d3);
    color = mix(color, vec4<f32>(0.3, 1.0, 0.3, 1.0), alpha3);
    
    // Plot Band 6 (Treble) - Blue
    let val6 = row1.b;
    let d6 = abs(y - val6);
    let alpha6 = smoothstep(thickness + blur, thickness, d6);
    color = mix(color, vec4<f32>(0.3, 0.5, 1.0, 1.0), alpha6);

    return color;
}
