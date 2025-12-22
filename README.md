# CoasterTunnel

An immersive Android application that creates a real-time audio-reactive tunnel visualization. The app captures microphone audio, performs FFT analysis to extract frequency bands, and renders a dynamic tunnel effect using OpenGL ES 3.0 shaders.

## Overview

CoasterTunnel transforms audio input into a mesmerizing visual experience. As you play music or make sounds, the app analyzes the audio spectrum in real-time and renders colorful frequency lines that spiral through a tunnel-like space, creating a "warp speed" effect synchronized with the audio.

## Features

- **Real-time Audio Analysis**: Captures audio at 44.1kHz and performs FFT analysis to extract 32 frequency bands
- **Dynamic Visualization**: 
  - Radial frequency lines that oscillate based on audio history
  - Warp-speed starfield background
  - Pulsing center core that reacts to bass frequencies
  - Smooth attack/decay envelope for fluid visual transitions
- **Fullscreen Immersive Experience**: Landscape orientation with hidden system UI
- **Audio History Tracking**: Maintains a rolling history texture for temporal effects
- **High Performance**: Optimized OpenGL ES 3.0 rendering with efficient shader code

## Requirements

- **Android**: Minimum SDK 26 (Android 8.0), Target SDK 34
- **Permissions**: `RECORD_AUDIO` (requested at runtime)
- **Hardware**: OpenGL ES 3.0 capable device

## Building and Running

### Prerequisites

- Android Studio or command-line Android SDK tools
- Gradle (included via wrapper)
- Android device with USB debugging enabled, or emulator

### Build Steps

1. Navigate to the Android project directory:
   ```bash
   cd android
   ```

2. Build and install the debug APK:
   ```bash
   ./gradlew installDebug
   ```

3. Or use the provided build script:
   ```bash
   ./build_android.sh
   ```
   Note: The build script includes a device-specific launch command. Modify the `adb -s` device ID in `build_android.sh` for your device.

4. Grant microphone permission when prompted on first launch.

### Project Structure

```
CoasterTunnel/
├── android/
│   ├── app/
│   │   ├── src/main/
│   │   │   ├── java/com/coaster/tunnel/
│   │   │   │   ├── MainActivity.kt              # Entry point, permissions
│   │   │   │   ├── audio/
│   │   │   │   │   ├── AudioCapture.kt         # PCM audio capture
│   │   │   │   │   └── FFTAnalyzer.kt          # FFT analysis, 32-band extraction
│   │   │   │   └── gl/
│   │   │   │       ├── TunnelGLSurfaceView.kt  # OpenGL ES 3.0 surface
│   │   │   │       └── TunnelRenderer.kt       # Renderer, shader uniforms
│   │   │   └── res/raw/
│   │   │       ├── tunnel_vert.glsl            # Vertex shader
│   │   │       └── tunnel_frag.glsl            # Fragment shader (main effect)
│   │   └── build.gradle
│   └── build.gradle
└── assets/shaders/                              # Additional shader files (WGSL)
    ├── tunnel_warp.wgsl
    └── graph.wgsl
```

## Architecture

The application follows a pipeline architecture from audio capture to visual rendering:

```mermaid
flowchart TB
    subgraph audio [Audio Pipeline]
        Mic[Microphone] --> AC[AudioCapture]
        AC --> FFT[FFTAnalyzer]
        FFT --> Bands["32 Frequency Bands<br/>Bass | Mids | Treble"]
    end
    
    subgraph render [Rendering Pipeline]
        GLView[TunnelGLSurfaceView] --> Renderer[TunnelRenderer]
        Renderer --> Shader[GLSL Fragment Shader]
        Shader --> Display[Fullscreen Display]
    end
    
    Bands --> GLView
```

### Data Flow

1. **Audio Capture** (`AudioCapture.kt`): 
   - Uses Android's `AudioRecord` API to capture PCM float samples at 44.1kHz
   - Reads 2048-sample buffers (FFT size) in a background thread
   - Passes samples to the analyzer via callback

2. **FFT Analysis** (`FFTAnalyzer.kt`):
   - Applies Hann window function to reduce spectral leakage
   - Performs Radix-2 FFT on 2048 samples
   - Groups frequency bins into 32 logarithmic bands (60Hz - 22kHz)
   - Applies smoothing with attack/decay envelope (attack: 25.0, decay: 8.0)
   - Extracts summary bands: Bass (0-3), Mids (4-19), Treble (20-31)

3. **Rendering** (`TunnelRenderer.kt`):
   - Updates a 512x8 RGBA history texture (32 bands packed into 8 rows)
   - Passes audio data to shader via uniforms:
     - `uTime`: Elapsed time
     - `uAudio`: Bass, mids, treble summary
     - `uBands`: 32-band array
     - `uAudioHistory`: History texture for temporal effects
     - `uOffset`: Ring buffer head position

4. **Visual Effects** (`tunnel_frag.glsl`):
   - **Frequency Lines**: Each of 32 bands maps to a radial sector. Lines oscillate based on audio history, creating a wave that travels outward
   - **Warp Stars**: Grid-based procedural starfield with audio-reactive speed
   - **Center Core**: Pulsing white core that expands with bass
   - **Vignette**: Darkened edges for tunnel effect

## Key Components

| Component | File | Purpose |
|-----------|------|---------|
| Entry Point | [`MainActivity.kt`](android/app/src/main/java/com/coaster/tunnel/MainActivity.kt) | Handles permissions, fullscreen setup, lifecycle |
| Audio Capture | [`AudioCapture.kt`](android/app/src/main/java/com/coaster/tunnel/audio/AudioCapture.kt) | PCM audio capture at 44.1kHz, 2048-sample buffers |
| FFT Analysis | [`FFTAnalyzer.kt`](android/app/src/main/java/com/coaster/tunnel/audio/FFTAnalyzer.kt) | Radix-2 FFT, 32-band logarithmic grouping, smoothing |
| GL Surface | [`TunnelGLSurfaceView.kt`](android/app/src/main/java/com/coaster/tunnel/gl/TunnelGLSurfaceView.kt) | OpenGL ES 3.0 context management, audio integration |
| Renderer | [`TunnelRenderer.kt`](android/app/src/main/java/com/coaster/tunnel/gl/TunnelRenderer.kt) | Shader compilation, uniform updates, history texture |
| Fragment Shader | [`tunnel_frag.glsl`](android/app/src/main/res/raw/tunnel_frag.glsl) | Visual effect computation (frequency lines, stars, core) |

## Technical Details

### Audio Processing

- **Sample Rate**: 44.1kHz
- **Format**: PCM Float, Mono
- **Buffer Size**: 2048 samples (matches FFT size)
- **FFT**: Radix-2 iterative implementation
- **Window Function**: Hann window
- **Frequency Bands**: 32 logarithmic bands from 60Hz to 22kHz
- **Smoothing**: Exponential attack/decay with configurable rates

### Rendering

- **API**: OpenGL ES 3.0
- **Resolution**: Adaptive to screen size
- **Frame Rate**: Continuous rendering (60 FPS target)
- **History Texture**: 512x8 RGBA8 texture (512 frames of 32-band history)
- **Shader Language**: GLSL ES 3.00

### Visual Effects Breakdown

1. **Radial Frequency Lines**: 
   - Each frequency band occupies a radial sector
   - Lines oscillate using a carrier wave modulated by audio history
   - Color mapped via HSV (hue based on band index)
   - Depth-based fade for perspective

2. **Warp Starfield**:
   - Procedural grid-based stars using hash functions
   - Audio-reactive speed (bass-controlled)
   - Twinkling effect with time-based modulation

3. **Center Singularity**:
   - White core with bass-reactive radius
   - Glow effect using inverse distance
   - Creates focal point of the tunnel

## Development

### Dependencies

- `androidx.core:core-ktx:1.12.0`
- `androidx.appcompat:appcompat:1.6.1`
- `androidx.activity:activity-ktx:1.8.2`

### Build Configuration

- **Language**: Kotlin
- **Java Version**: 17
- **Compile SDK**: 34
- **Min SDK**: 26
- **Target SDK**: 34

