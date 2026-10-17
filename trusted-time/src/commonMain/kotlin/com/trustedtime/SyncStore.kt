package com.trustedtime

/** Keeps the last sync across app restarts, so the clock stays trusted offline until the device reboots. */
internal interface SyncStore {
    fun load(): String?

    fun save(value: String)
}
