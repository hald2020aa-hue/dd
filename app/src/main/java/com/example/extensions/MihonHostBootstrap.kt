package com.example.extensions

import android.content.Context
import android.os.Build
import eu.kanade.tachiyomi.network.NetworkHelper
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton

/** Called immediately before a verified extension is loaded, in the probe process only. */
object MihonHostBootstrap {
    @Synchronized fun prepare(context: Context) {
        require(Build.VERSION.SDK_INT >= 26) { "واجهة Mihon 1.6 تتطلب Android 8 أو أحدث" }
        // Registration repeated in :extension_probe is safe; the scope is local to that process.
        Injekt.addSingleton<Context>(context.applicationContext)
        Injekt.addSingleton(NetworkHelper(context.applicationContext))
        // Missing host services remain explicit errors. Never silently run unsupported code.
    }
}
