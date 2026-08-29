# Notifications and push providers

SecureChat displays notifications from Matrix events after the SDK synchronizes the relevant room
state. Android notification permission, channel settings, room notification rules, background
restrictions, and battery optimization can all affect delivery.

Current distribution policy:

- F-Droid builds include UnifiedPush support.
- Firebase is excluded because SecureChat does not yet have a configured Firebase project and push
  gateway.
- `PUSH_CONFIG_INCLUDE_FIREBASE` must remain `false` until both are SecureChat-owned and the
  application IDs in `BuildTimeConfig` match the deployed gateway configuration.

A future Firebase deployment requires all of the following: a SecureChat Firebase project,
`google-services.json` for `com.securechat.app`, a SecureChat-controlled Matrix push gateway,
documented privacy/retention behavior, release and debug test coverage, and removal of placeholder
configuration. Never reuse another application's Firebase project or push credentials.

For troubleshooting, confirm Android notification permission, the room's notification mode,
background/battery restrictions, and the selected push provider in developer settings. F-Droid
installations without a UnifiedPush distributor cannot receive remote push and may only update while
the app synchronizes.

Matrix push rules and gateway behavior are protocol concepts; see the current Matrix Client-Server
API specification and the [Sygnal reference implementation](https://github.com/matrix-org/sygnal).
