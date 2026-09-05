# Notifications in the closed distribution

SecureChat is sideloaded as a closed APK and does not compile a remote push provider. Both Firebase
and UnifiedPush are disabled in `BuildTimeConfig`; no Google project, public distributor directory,
or public Matrix push gateway is present in the production dependency graph.

This is an intentional confidentiality tradeoff:

- Notifications can be created only after the app's Matrix client has synchronized the event.
- Delivery is reliable while SecureChat is open and connected.
- Android may suspend or kill the process in the background. With no remote wake-up channel, new
  messages and calls can remain silent until the user opens SecureChat and synchronization resumes.
- Sideloading from a computer does not provide a background notification transport.

The notification settings screen treats an empty provider set as a supported local-sync mode. It
does not try to register a pusher or show a provider-configuration error. Android notification
permission, channel settings, and Matrix room notification rules still apply to events already
received by the process.

Do not re-enable either provider merely to improve background delivery. A future push design needs a
separate security review, a SecureChat-owned private transport and gateway, documented metadata and
retention rules, certificate and endpoint pinning decisions, incident response, and release tests.
The repository configuration audit intentionally fails if the current closed-build flags or
non-routable fallback endpoints are weakened.
