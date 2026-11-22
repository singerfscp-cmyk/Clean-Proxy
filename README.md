# Clean Proxy (ad-free SOCKS5 VPN)

Build (no PC needed):
1. Create a GitHub repo, upload all these files (keep the .github folder).
2. Actions tab -> "Build APK" -> Run workflow.
3. Download the `CleanProxy-apk` artifact, unzip, install app-debug.apk.

Local build: git clone --recursive https://github.com/heiher/hev-socks5-tunnel app/src/main/jni/hev-socks5-tunnel
then `gradle assembleDebug` (needs Android SDK + NDK 26).
