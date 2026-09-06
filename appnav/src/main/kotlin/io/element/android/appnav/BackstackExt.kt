/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.appnav

import com.bumble.appyx.navmodel.backstack.BackStack
import com.bumble.appyx.navmodel.backstack.activeElement
import com.bumble.appyx.navmodel.backstack.operation.NewRoot
import com.bumble.appyx.navmodel.backstack.operation.Remove

/** Don't process NewRoot only when the requested target is already active. */
fun <T : Any> BackStack<T>.safeRoot(element: T) {
    // A previous root can remain as an inactive element while a transition is settling. Treating
    // that as current leaves the security splash permanently active after unlock.
    if (activeElement == element) return
    accept(NewRoot(element))
}

/**
 * Remove the last element on the backstack equals to the given one.
 */
fun <T : Any> BackStack<T>.removeLast(element: T) {
    val lastExpectedNavElement = elements.value.lastOrNull {
        it.key.navTarget == element
    } ?: return
    accept(Remove(lastExpectedNavElement.key))
}
