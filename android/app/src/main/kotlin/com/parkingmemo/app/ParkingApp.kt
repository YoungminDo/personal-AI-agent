package com.parkingmemo.app

import android.app.Application
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import com.parkingmemo.engine.GuideState

class ParkingApp : Application() {
    lateinit var prefs: Prefs
        private set
    lateinit var store: Store
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        store = Store(this)
        Notifier.createChannels(this)
    }
}

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    var carAddress: String?
        get() = sp.getString("car_addr", null)
        set(v) = sp.edit().putString("car_addr", v).apply()

    var carName: String?
        get() = sp.getString("car_name", null)
        set(v) = sp.edit().putString("car_name", v).apply()

    var setupDone: Boolean
        get() = sp.getBoolean("setup_done", false)
        set(v) = sp.edit().putBoolean("setup_done", v).apply()

    var soundOn: Boolean
        get() = sp.getBoolean("sound_on", false)
        set(v) = sp.edit().putBoolean("sound_on", v).apply()
}

/** Shared between the service and the UI (same process). */
object TrackerState {
    enum class Status { IDLE, DRIVING, WAITING_WALK, WALK_OUT, GUIDING }

    val status = MutableStateFlow(Status.IDLE)
    val guide = MutableStateFlow<GuideState?>(null)
    val walkInfo = MutableStateFlow("")
    val message = MutableStateFlow<String?>(null)
    val recordVersion = MutableStateFlow(0)
}
