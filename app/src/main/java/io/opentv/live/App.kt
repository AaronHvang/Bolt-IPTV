package io.opentv.live

import android.app.Application
import io.opentv.live.data.ChannelRepository
import io.opentv.live.data.EpgRepository
import io.opentv.live.data.Prefs

class App : Application() {

    lateinit var prefs: Prefs
        private set
    lateinit var channels: ChannelRepository
        private set
    lateinit var epg: EpgRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        channels = ChannelRepository(this, prefs)
        epg = EpgRepository(this, prefs)
        channels.loadCache()
    }

    companion object {
        private lateinit var instance: App
        fun get(): App = instance
    }
}
