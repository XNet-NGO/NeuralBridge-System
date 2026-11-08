[← Back to README](../README.md)

# 🔨 Development Guide

### 🏗️ Building from source

```bash
cd android
./gradlew assembleDebug       # Build debug APK
./gradlew test                # Run unit tests
./gradlew connectedAndroidTest  # Run instrumented tests (requires device)
```

---

### 📦 Project Structure

```
neuralbridge/
├── android/                       # Android app (runs on device)
│   ├── app/src/main/
│   │   ├── kotlin/.../companion/
│   │   │   ├── mcp/              # Embedded MCP HTTP server (Ktor CIO, port 7474)
│   │   │   ├── service/          # AccessibilityService + command handler
│   │   │   ├── gesture/          # Gesture engine
│   │   │   ├── input/            # Text input, clipboard, key events
│   │   │   ├── screenshot/       # MediaProjection + libjpeg-turbo (JNI)
│   │   │   ├── network/          # TCP server + protobuf codec
│   │   │   ├── uitree/           # UI tree walker + semantic extraction
│   │   │   └── notification/     # NotificationListenerService
│   │   ├── cpp/                   # JNI (libjpeg-turbo JPEG encoding)
│   │   └── res/                   # Resources
│   ├── proto/                     # Protobuf schema (source of truth)
│   └── build.gradle.kts
├── examples/                      # Example MCP client
│   └── mcp_client.py
├── docs/                          # Documentation
├── CONTRIBUTING.md
├── SECURITY.md
└── LICENSE                        # Apache 2.0
```

---

### 🔄 Updating the protobuf schema

The schema at `android/proto/neuralbridge.proto` is the source of truth. When you change it, regenerate Kotlin code:

```bash
cd android && ./gradlew generateProto
```

---

### 📋 Viewing companion app logs

```bash
adb logcat -s NeuralBridge:V
```
