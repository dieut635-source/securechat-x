# Embedded calls

<!--- TOC -->

* [Overview](#overview)
* [Custom call deployments](#custom-call-deployments)
* [Where the value is read](#where-the-value-is-read)

<!--- END -->

## Overview

SecureChat bundles its call web application in the Android package and loads it through Android's
local app-assets origin. Calls therefore work without a separate public web deployment, and every
installation uses the reviewed version pinned by the Android build.

The build verifies the embedded artifact checksum, removes source maps, replaces inherited product
presentation and external policy links, and fails if those checks no longer hold after an upgrade.

## Custom call deployments

Developers can point a local build at a SecureChat-owned staging or self-hosted call page through
*Settings* → *Developer options* → *Custom call server URL*. Developer options are unlocked by
tapping the version number seven times. Leaving the field empty restores the bundled call client.

The value is used verbatim as the widget base URL. Enter the complete page URL that joins a room;
the app does not append a deployment-specific path. This keeps reverse-proxy layouts and custom
mount points working without guessing their server configuration.

Production builds should use only HTTPS endpoints controlled by the SecureChat deployment owner.

## Where the value is read

The developer-settings presenter stores the custom URL in the app preferences. The call widget
provider reads it and falls back to the bundled, checksum-verified copy when it is empty.
