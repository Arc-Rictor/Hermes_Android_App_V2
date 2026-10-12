package com.arcrictor.hermes.config

/** Owner's gateway, baked in. Password arrives via BuildConfig (CI secret / local.properties). */
object Defaults {
    const val BASE_URL = "http://100.123.89.114:9119"
    const val USERNAME = "kirsty"
}
