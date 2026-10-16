package dev.dumbtalk.signal

import android.content.Context

/** JNI entry points of libsigbridge that aren't part of the UniFFI API. */
internal object SignalNative {
    init {
        System.loadLibrary("sigbridge")
    }

    /** Lets Signal's TLS (rustls) use Android's certificate verifier. */
    @JvmStatic
    external fun init(context: Context)
}
