/*
 * Copyright 2026 VCWG
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.atakmap.coremap.log;

/** Test-only ATAK logging facade for standalone plugin instrumentation. */
public final class Log {

    private Log() {
    }

    public static int i(String tag, String message) {
        return android.util.Log.i(tag, message);
    }

    public static int w(String tag, String message) {
        return android.util.Log.w(tag, message);
    }

    public static int e(String tag, String message) {
        return android.util.Log.e(tag, message);
    }

    public static int e(String tag, String message, Throwable error) {
        return android.util.Log.e(tag, message, error);
    }
}
