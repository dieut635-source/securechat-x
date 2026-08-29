#!/usr/bin/env bash

# Copyright (c) 2025 Element Creations Ltd.
# Copyright 2023-2024 New Vector Ltd.
#
# SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
# Please see LICENSE files in the repository root for full details.

set -euo pipefail

printf '%s\n' 'This inherited local release flow is disabled for SecureChat.' >&2
printf '%s\n' 'Use the signed SecureChat Release APK workflow in GitHub Actions.' >&2
exit 1
