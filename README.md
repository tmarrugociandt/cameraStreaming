# CameraStreaming (Android)

Android project for live streaming (YouTube) with continuous network detection and dynamic quality adaptation (bitrate / resolution / fps). It includes a demo AES‑GCM encryption mode for local segments and several measures to keep the stream running when the connection changes (Wi‑Fi ↔ mobile data).

Quick summary
- Goal: stream live to YouTube and keep the broadcast stable by adjusting bitrate/resolution/fps in real time according to network quality.
- Security approach (no server): RTMPS transport and hardening measures; local encryption demo with AES‑GCM.

Key features

- Continuous monitoring of throughput, RTT and a simple packet‑loss heuristic.
- Dynamic adjustments of bitrate, resolution and fps without restarting the stream (when possible).
- Visual indicators in the UI (`networkStatusText`, `bitrateText`) showing network quality and current bitrate.
- Automatic reconnection and a retry policy with backoff.
- AES‑GCM encryption demo mode (activate with a long press on the network status indicator) that writes encrypted fragments to `cacheDir/encrypted_stream.bin`.

Important limitations
- YouTube does not accept E2E encrypted payloads; to get true end‑to‑end encryption you need an intermediate ingest server that receives encrypted data from the app, decrypts it and forwards it to YouTube. This project implements local segment encryption and TLS (RTMPS) for transport to YouTube.

Requirements
- Android Studio (recommended) or JDK + Gradle
- Android device with camera and microphone permissions
- Internet connection (Wi‑Fi or mobile data)

Quick setup
1. Clone or open the project in Android Studio.
2. Set your `streamKey` in `app/src/main/java/com/ciandt/camerastreaming/MainActivityYoutube.kt` (the `streamKey` variable).
3. Make sure the app has CAMERA and RECORD_AUDIO permissions granted.

Build and install (from project root)

```bash
# build debug APK
./gradlew assembleDebug

# install on connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Run and test
- Open the app and press Start.
- Watch `networkStatusText` and `bitrateText` to see the current state and bitrate.

Logs and diagnosis (ADB)

```bash
# Filter relevant logs
adb logcat -v threadtime MainActivityYoutube:V NetMonitor:V Adaptation:V NetworkCallback:V *:S
```

Look for entries such as:
- "Preparing stream..." / "Calling rtmpCamera2.startStream with URL="
- "startStream() returned, isStreaming="
- "Network lost" / "Network available"
- "Adapting to bitrate=..." / "Adaptation applied: ..."

AES‑GCM encryption demo mode
- Activate: long press the network status indicator (`networkStatusText`).
- When enabled, the app generates a demo key (base64) shown in a toast and appends the `[ENCRYPTED]` tag to the status indicator.
- Behavior: while in demo mode the app writes encrypted markers/test payloads to `cacheDir/encrypted_stream.bin`.
- Note: this is a local demo — it does not change the live stream sent to YouTube. For E2E you need an ingest server that can decrypt.

Recommendations for security (no server)
- Force RTMPS (avoid falling back to rtmp://) to ensure transport encryption.
- Rotate your stream key periodically and use privacy settings (e.g., Unlisted/Private).

Architecture and flow
- The app prepares camera and audio using the `RtmpCamera2` library and creates an OpenGL preview.
- A background monitor calculates throughput using TrafficStats and runs RTT checks.
- Based on predefined thresholds, the app decides whether to adapt (it uses a cooldown to avoid flapping).
- Adaptations try on‑the‑fly bitrate changes via reflection (`setVideoBitrateOnFly`) or reconfigure the encoder while preserving the session when possible.

Known issues and troubleshooting
- "isStreaming=false" after startStream(): the app retries and uses a fallback to `rtmp://` for diagnostics only. In production you should remove the fallback and use RTMPS only.
- Network changes (Wi‑Fi → mobile): the app attempts a clean stop+restart of the stream and uses `ConnectivityManager.bindProcessToNetwork(...)` to force the outbound traffic to use the new interface.
- If the app does not transmit over mobile data, check logcat for related lines and verify the carrier is not blocking RTMP/RTMPS.

Next recommended steps
- For true E2E: deploy an ingest server that receives encrypted data from the app, decrypts it, and forwards it to YouTube.


Streaming to AWS server (RTMP)
--------------------------------
There is also an option to send the stream directly to an RTMP server deployed on AWS (for example: MediaLive, Nginx/RTMP on EC2 or a private ingest endpoint for the project).

Minimum configuration steps:
1. Obtain the RTMP ingest URL for your AWS server. It should look like:
   rtmp://<AWS_HOST>:1935/<app>/<streamKey>
   or with TLS (recommended):
   rtmps://<AWS_HOST>:1935/<app>/<streamKey>

2. In the app, update `streamKey` or `getRtmpUrl()` in `MainActivity.kt` to point at the AWS endpoint (use RTMPS if supported)