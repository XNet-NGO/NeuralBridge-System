[← Back to README](../README.md)

# 🔧 Troubleshooting

### 🌐 Cannot connect to MCP server (HTTP)

```bash
# 1. Check that both devices are reachable on the same network
#    The app shows its IP on the main screen

# 2. Verify the server is running (use the IP shown in the app)
curl http://<device-ip>:7474/health
```

Also check that the NeuralBridge toggle is enabled in the app — the MCP server only runs when the toggle is on.

---

### ♿ AccessibilityService not running

```bash
# Check current status
adb shell settings get secure enabled_accessibility_services

# Re-enable
adb shell settings put secure enabled_accessibility_services \
  com.neuralbridge.companion/.service.NeuralBridgeAccessibilityService
adb shell settings put secure accessibility_enabled 1
```

On Android 15+, you may also need to enable **"Allow restricted settings"** for NeuralBridge in Settings > Apps.

---

### 📸 Screenshots return fallback (ADB screencap)

MediaProjection requires a one-time user consent. Open the NeuralBridge app on the device and trigger a screenshot — tap "Start now" on the system dialog. On Android 14+, this consent resets when the app process dies.

---

### 🐌 High latency on first screenshot

The first screenshot after MediaProjection setup takes 150-300ms (warm-up). Subsequent screenshots run at ~60ms. This is a one-time cost per session.

---

### 💥 Companion app crashes or stops responding

```bash
# Check crash logs
adb logcat -s NeuralBridge:V

# Force restart
adb shell am force-stop com.neuralbridge.companion
adb shell am start -n com.neuralbridge.companion/.MainActivity

# Re-enable AccessibilityService after restart
adb shell settings put secure enabled_accessibility_services \
  com.neuralbridge.companion/.service.NeuralBridgeAccessibilityService
```
