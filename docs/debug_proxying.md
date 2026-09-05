# Inspecting debug network traffic

Use a dedicated emulator and test account; intercepted traffic can contain credentials and message
content.

1. Install and start [mitmproxy](https://mitmproxy.org/).
2. Configure the emulator proxy for `10.0.2.2:8080`, or run:

   ```bash
   adb shell settings put global http_proxy 10.0.2.2:8080
   ```

3. Follow mitmproxy's Android instructions at `http://mitm.it` to install its temporary CA on the
   test emulator.
4. If Rust SDK traffic must be inspected, temporarily add `.disableSslVerification()` to the client
   builder in `RustMatrixClientFactory` and run a debug build.
5. Remove the code change and proxy immediately after testing:

   ```bash
   adb shell settings delete global http_proxy
   ```

Never commit disabled TLS verification, proxy certificates, captured traffic, or test credentials.
Certificate pinning and native TLS behavior may prevent some traffic from being decrypted.
