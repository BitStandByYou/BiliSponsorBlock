package io.github.idongyou.bilisb.util

import android.util.Log
import io.github.libxposed.api.XposedInterface

private const val TAG = "BiliSB"

fun XposedInterface.info(message: String) {
    log(Log.INFO, TAG, message)
}

fun XposedInterface.warn(message: String, throwable: Throwable? = null) {
    if (throwable == null) {
        log(Log.WARN, TAG, message)
    } else {
        log(Log.WARN, TAG, message, throwable)
    }
}
