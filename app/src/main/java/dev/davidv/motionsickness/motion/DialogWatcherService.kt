// SPDX-FileCopyrightText: 2026 David Ventura
// SPDX-License-Identifier: GPL-3.0-only

package dev.davidv.motionsickness.motion

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Watches for system/app dialogs appearing in the foreground (identified by the standard
 * framework button IDs AlertDialog uses) and tells MotionCuesService to hide the overlay
 * while one is showing, so dialog buttons stay tappable.
 */
class DialogWatcherService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event?.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return

        val root = rootInActiveWindow
        val hasDialogButtons = root != null && (
                root.findAccessibilityNodeInfosByViewId("android:id/button1").isNotEmpty() ||
                        root.findAccessibilityNodeInfosByViewId("android:id/button2").isNotEmpty() ||
                        root.findAccessibilityNodeInfosByViewId("android:id/button3").isNotEmpty()
                )
        android.util.Log.d("DialogWatcher", "hasDialogButtons=$hasDialogButtons")
        MotionCuesService.setDialogVisible(hasDialogButtons)
    }

    override fun onInterrupt() {
        MotionCuesService.setDialogVisible(false)
    }
}