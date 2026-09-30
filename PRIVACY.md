# Privacy Policy

**DeadEasy Player** is built with privacy in mind.

### Data Collection & Analytics
- **No Personal Data Collected**: DeadEasy Player does not collect, store, transmit, or share any personal information.
- **No Analytics / Telemetry**: The app does not include third-party tracking SDKs, advertising libraries, or telemetry services.

### Media Streams & Network Usage
- **On-Device Stream Processing**: Stream URLs passed to the player (via external Android intents or standalone file picker) remain strictly on your device.
- **Authentication Tokens**: If a stream URL contains authentication parameters or access tokens (e.g. in query strings), this information is processed locally by libVLC to play the video.
- **System Logs**: Stream URLs may appear in Android system logs (`logcat`) and process memory. Be mindful of this if exporting system-wide debug logs.
- **Local Area Network (Cleartext HTTP)**: The app permits cleartext HTTP connections specifically to support home and local area network (LAN) media servers (such as Jellyfin, Plex, or local NAS devices).

### Contact
If you have any questions or feedback regarding this policy, please file an issue on the [GitHub repository](https://github.com/jmez11/deadeasy-player).
