package com.kododake.aabrowser.car

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.hardware.CarHardwareManager
import androidx.car.app.hardware.common.OnCarDataAvailableListener
import androidx.car.app.hardware.info.Accelerometer
import androidx.car.app.hardware.info.CarHardwareLocation
import androidx.car.app.hardware.info.CarSensors
import androidx.car.app.hardware.info.Compass
import androidx.car.app.hardware.info.EnergyLevel
import androidx.car.app.hardware.info.EnergyProfile
import androidx.car.app.hardware.info.EvStatus
import androidx.car.app.hardware.info.Gyroscope
import androidx.car.app.hardware.info.Mileage
import androidx.car.app.hardware.info.Model
import androidx.car.app.hardware.info.Speed
import androidx.car.app.hardware.info.TollCard
import androidx.car.app.model.DateTimeWithZone
import androidx.car.app.model.Distance
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.Destination
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.car.app.navigation.model.Trip
import androidx.car.app.notification.CarPendingIntent
import androidx.car.app.suggestion.SuggestionManager
import androidx.car.app.suggestion.model.Suggestion
import androidx.car.app.constraints.ConstraintManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.TimeZone

/**
 * Debug readout of car-host services this app does not drive in normal use.
 * Every read and every test send is wrapped so a missing host API stays on the list.
 */
class CarHostProbe(
    private val carContext: CarContext,
    private val evaluate: (String) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val values = LinkedHashMap<String, String>()
    private var open = false
    private var publishPosted = false
    private var hardwareBound = false
    private var tripActive = false
    private var navigation: NavigationManager? = null
    private var mediaSession: Any? = null

    private val onModel = OnCarDataAvailableListener<Model> { set("Model", it.toString()) }
    private val onEnergyProfile = OnCarDataAvailableListener<EnergyProfile> { set("Energy profile", it.toString()) }
    private val onToll = OnCarDataAvailableListener<TollCard> { set("Toll card", it.toString()) }
    private val onEnergy = OnCarDataAvailableListener<EnergyLevel> { set("Energy level", it.toString()) }
    private val onSpeed = OnCarDataAvailableListener<Speed> { set("Speed", it.toString()) }
    private val onMileage = OnCarDataAvailableListener<Mileage> { set("Mileage", it.toString()) }
    private val onEv = OnCarDataAvailableListener<EvStatus> { set("EV status", it.toString()) }
    private val onAccelerometer = OnCarDataAvailableListener<Accelerometer> { set("Accelerometer", it.toString()) }
    private val onGyroscope = OnCarDataAvailableListener<Gyroscope> { set("Gyroscope", it.toString()) }
    private val onCompass = OnCarDataAvailableListener<Compass> { set("Compass", it.toString()) }
    private val onHardwareLocation = OnCarDataAvailableListener<CarHardwareLocation> { set("Hardware location", it.toString()) }

    fun open() {
        open = true
        values.clear()
        readSnapshot()
        bindHardware()
        bindNavigation()
        readMedia()
        publish()
    }

    fun close() {
        open = false
        publishPosted = false
        unbindHardware()
        if (tripActive) {
            try {
                navigation?.navigationEnded()
            } catch (_: Throwable) {
            }
            tripActive = false
        }
        try {
            navigation?.clearNavigationManagerCallback()
        } catch (_: Throwable) {
        }
        navigation = null
        releaseMediaSession()
    }

    fun action(name: String) {
        if (!open) open()
        val result = when (name) {
            "nav-start" -> send {
                navigation().navigationStarted()
                tripActive = true
                "navigationStarted sent"
            }
            "nav-end" -> send {
                navigation().navigationEnded()
                tripActive = false
                "navigationEnded sent"
            }
            "nav-trip" -> send {
                val nav = navigation()
                try {
                    nav.navigationStarted()
                    tripActive = true
                } catch (_: Throwable) {
                }
                nav.updateTrip(exampleTrip())
                tripActive = true
                "example next turn sent"
            }
            "suggestion" -> send { sendSuggestion(); "example suggestion sent" }
            "media-token" -> send { registerMediaToken() }
            else -> "unknown action"
        }
        set("Last result", result)
        publish()
    }

    private fun readSnapshot() {
        set("Dark mode", try { carContext.isDarkMode.toString() } catch (t: Throwable) { error(t) })
        set("Car API level", try { carContext.carAppApiLevel.toString() } catch (t: Throwable) { error(t) })
        set("Host package", try { carContext.hostInfo?.packageName ?: "none" } catch (t: Throwable) { error(t) })
        set("Host uid", try { carContext.hostInfo?.uid?.toString() ?: "none" } catch (t: Throwable) { error(t) })
        set("Calling component", try { carContext.callingComponent?.flattenToString() ?: "none" } catch (t: Throwable) { error(t) })
        try {
            val config = carContext.resources.configuration
            val metrics = carContext.resources.displayMetrics
            val night = config.uiMode and Configuration.UI_MODE_NIGHT_MASK
            set("UI mode", when (night) {
                Configuration.UI_MODE_NIGHT_YES -> "night"
                Configuration.UI_MODE_NIGHT_NO -> "not night"
                else -> "undefined"
            })
            set("Locale", config.locales.toString())
            set("Screen width dp", config.screenWidthDp.toString())
            set("Screen height dp", config.screenHeightDp.toString())
            set("Smallest width dp", config.smallestScreenWidthDp.toString())
            set("Density dpi", metrics.densityDpi.toString())
        } catch (t: Throwable) {
            set("Screen configuration", error(t))
        }
        try {
            val limits = carContext.getCarService(ConstraintManager::class.java)
            set("List limit", limits.getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST).toString())
            set("Grid limit", limits.getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_GRID).toString())
            set("Place list limit", limits.getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_PLACE_LIST).toString())
            set("Route list limit", limits.getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_ROUTE_LIST).toString())
            set("Pane limit", limits.getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_PANE).toString())
            set("App-driven refresh", limits.isAppDrivenRefreshEnabled.toString())
        } catch (t: Throwable) {
            set("Content limits", error(t))
        }
        set("Suggestion manager", try {
            carContext.getCarService(SuggestionManager::class.java)
            "available"
        } catch (t: Throwable) {
            error(t)
        })
        set("Navigation events", "none yet")
        set("Last result", "")
    }

    private fun bindNavigation() {
        try {
            val nav = carContext.getCarService(NavigationManager::class.java)
            navigation = nav
            nav.setNavigationManagerCallback(carContext.mainExecutor, object : NavigationManagerCallback {
                override fun onStopNavigation() {
                    set("Navigation events", "host called onStopNavigation")
                    publishSoon()
                }

                override fun onAutoDriveEnabled() {
                    set("Navigation events", "host called onAutoDriveEnabled")
                    publishSoon()
                }
            })
            set("Navigation manager", "callback registered")
        } catch (t: Throwable) {
            set("Navigation manager", error(t))
        }
    }

    private fun bindHardware() {
        if (hardwareBound) return
        try {
            val hardware = carContext.getCarService(CarHardwareManager::class.java)
            val info = hardware.carInfo
            val sensors = hardware.carSensors
            val executor = carContext.mainExecutor
            set("Model", "waiting")
            set("Energy profile", "waiting")
            set("Toll card", "waiting")
            set("Energy level", "waiting")
            set("Speed", "waiting")
            set("Mileage", "waiting")
            set("EV status", "waiting")
            set("Accelerometer", "waiting")
            set("Gyroscope", "waiting")
            set("Compass", "waiting")
            set("Hardware location", "waiting")
            hardwareBound = true
            info.fetchModel(executor, onModel)
            info.fetchEnergyProfile(executor, onEnergyProfile)
            info.addTollListener(executor, onToll)
            info.addEnergyLevelListener(executor, onEnergy)
            info.addSpeedListener(executor, onSpeed)
            info.addMileageListener(executor, onMileage)
            info.addEvStatusListener(executor, onEv)
            val rate = CarSensors.UPDATE_RATE_NORMAL
            sensors.addAccelerometerListener(rate, executor, onAccelerometer)
            sensors.addGyroscopeListener(rate, executor, onGyroscope)
            sensors.addCompassListener(rate, executor, onCompass)
            sensors.addCarHardwareLocationListener(rate, executor, onHardwareLocation)
            readClimate(hardware)
        } catch (t: Throwable) {
            set("Vehicle hardware", error(t))
            unbindHardware()
        }
    }

    private fun readClimate(hardware: CarHardwareManager) {
        try {
            val getter = hardware.javaClass.methods.firstOrNull { it.name == "getCarClimate" && it.parameterCount == 0 }
            if (getter == null) {
                set("Climate", "no getCarClimate")
                return
            }
            val climate = getter.invoke(hardware)
            if (climate == null) {
                set("Climate", "null")
                return
            }
            set("Climate", climate.javaClass.name)
            val methods = climate.javaClass.methods.filter {
                it.declaringClass != Any::class.java && it.name != "getClass"
            }
            set("Climate methods", methods.joinToString { it.name }.ifEmpty { "none" })
            readBean("Climate", climate)
        } catch (t: Throwable) {
            set("Climate", error(t))
        }
    }

    private fun readMedia() {
        try {
            val manager = carService("androidx.car.app.media.MediaPlaybackManager")
            set("Media playback service", manager.javaClass.name)
            val methods = manager.javaClass.methods.filter {
                it.declaringClass != Any::class.java && it.name != "getClass"
            }
            set(
                "Media methods",
                methods.joinToString { it.name }.ifEmpty { "none" }
            )
            readBean("Media", manager)
            readMediaSession()
        } catch (t: Throwable) {
            set("Media playback service", error(t))
            readMediaSession()
        }
    }

    private fun readMediaSession() {
        try {
            val sessionClass = Class.forName("android.support.v4.media.session.MediaSessionCompat")
            val session = sessionClass.getConstructor(Context::class.java, String::class.java)
                .newInstance(carContext, "AABrowserHostProbeRead")
            try {
                set("Media session class", session.javaClass.name)
                readBean("Media session", session)
            } finally {
                try {
                    session.javaClass.getMethod("release").invoke(session)
                } catch (_: Throwable) {
                }
            }
        } catch (t: Throwable) {
            set("Media session", error(t))
        }
    }

    private fun readBean(prefix: String, target: Any) {
        val getters = target.javaClass.methods.filter {
            it.parameterCount == 0 &&
                (it.name.startsWith("get") || it.name.startsWith("is")) &&
                it.declaringClass != Any::class.java &&
                it.name != "getClass"
        }
        if (getters.isEmpty()) {
            set("$prefix readable properties", "none")
            return
        }
        getters.forEach { method ->
            val value = try {
                method.invoke(target)?.toString() ?: "null"
            } catch (t: Throwable) {
                error(t)
            }
            set("$prefix " + method.name, value)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun carService(name: String): Any {
        val type = Class.forName(name) as Class<Any>
        return carContext.getCarService(type)
    }

    private fun unbindHardware() {
        if (!hardwareBound) return
        hardwareBound = false
        try {
            val hardware = carContext.getCarService(CarHardwareManager::class.java)
            val info = hardware.carInfo
            val sensors = hardware.carSensors
            runCatching { info.removeTollListener(onToll) }
            runCatching { info.removeEnergyLevelListener(onEnergy) }
            runCatching { info.removeSpeedListener(onSpeed) }
            runCatching { info.removeMileageListener(onMileage) }
            runCatching { info.removeEvStatusListener(onEv) }
            runCatching { sensors.removeAccelerometerListener(onAccelerometer) }
            runCatching { sensors.removeGyroscopeListener(onGyroscope) }
            runCatching { sensors.removeCompassListener(onCompass) }
            runCatching { sensors.removeCarHardwareLocationListener(onHardwareLocation) }
        } catch (_: Throwable) {
        }
    }

    private fun navigation(): NavigationManager {
        return navigation ?: carContext.getCarService(NavigationManager::class.java).also { navigation = it }
    }

    private fun exampleTrip(): Trip {
        val distance = Distance.create(450.0, Distance.UNIT_METERS)
        val arrival = DateTimeWithZone.create(System.currentTimeMillis() + 90_000L, TimeZone.getDefault())
        val estimate = TravelEstimate.Builder(distance, arrival).setRemainingTimeSeconds(90L).build()
        val step = Step.Builder("Turn right onto Queen Street")
            .setManeuver(Maneuver.Builder(Maneuver.TYPE_TURN_NORMAL_RIGHT).build())
            .setRoad("Queen Street")
            .build()
        val destination = Destination.Builder()
            .setName("Test stop")
            .setAddress("1 Queen Street")
            .build()
        return Trip.Builder()
            .setCurrentRoad("Test road")
            .addStep(step, estimate)
            .addDestination(destination, estimate)
            .build()
    }

    private fun sendSuggestion() {
        val intent = Intent(Intent.ACTION_VIEW).setComponent(
            ComponentName(carContext, BrowserCarAppService::class.java)
        )
        val pending = CarPendingIntent.getCarApp(carContext, 21, intent, 0)
        val suggestion = Suggestion.Builder()
            .setIdentifier("host-probe-example")
            .setTitle("Test suggestion")
            .setSubtitle("Sent from the car host list")
            .setAction(pending)
            .build()
        carContext.getCarService(SuggestionManager::class.java).updateSuggestions(listOf(suggestion))
    }

    private fun registerMediaToken(): String {
        val sessionClass = Class.forName("android.support.v4.media.session.MediaSessionCompat")
        val session = sessionClass.getConstructor(Context::class.java, String::class.java)
            .newInstance(carContext, "AABrowserHostProbe")
        sessionClass.getMethod("setActive", Boolean::class.javaPrimitiveType).invoke(session, true)
        val token = sessionClass.getMethod("getSessionToken").invoke(session)
        val manager = carService("androidx.car.app.media.MediaPlaybackManager")
        val register = manager.javaClass.methods.first { it.name == "registerMediaPlaybackToken" }
        register.invoke(manager, token)
        releaseMediaSession()
        mediaSession = session
        return "test media token registered"
    }

    private fun releaseMediaSession() {
        val session = mediaSession ?: return
        mediaSession = null
        try {
            session.javaClass.getMethod("release").invoke(session)
        } catch (_: Throwable) {
        }
    }

    private fun send(block: () -> String): String {
        return try {
            block()
        } catch (t: Throwable) {
            error(t)
        }
    }

    private fun set(label: String, value: String) {
        values[label] = value.take(280)
        if (open && label != "Last result") publishSoon()
    }

    private fun publishSoon() {
        if (!open || publishPosted) return
        publishPosted = true
        main.postDelayed({
            publishPosted = false
            if (open) publish()
        }, 400)
    }

    private fun publish() {
        if (!open) return
        try {
            val rows = JSONArray()
            values.forEach { (label, value) ->
                if (label == "Last result" && value.isEmpty()) return@forEach
                rows.put(JSONObject().put("label", label).put("value", value))
            }
            actionRow(rows, "Trip active", "nav-start", "Tell the host a trip is active")
            actionRow(rows, "Trip ended", "nav-end", "Tell the host the trip ended")
            actionRow(rows, "Next turn", "nav-trip", "Right turn onto Queen Street, 450 m")
            actionRow(rows, "Suggestion", "suggestion", "Post one example suggestion")
            actionRow(rows, "Media token", "media-token", "Register a test playback token")
            val payload = JSONObject().put("rows", rows)
            val quoted = JSONObject.quote(payload.toString())
            evaluate("window.__aaHostProbe && window.__aaHostProbe($quoted);")
        } catch (t: Throwable) {
            val quoted = JSONObject.quote(error(t))
            evaluate("window.__aaHostProbe && window.__aaHostProbe({\"rows\":[{\"label\":\"Probe\",\"value\":$quoted}]});")
        }
    }

    private fun actionRow(rows: JSONArray, label: String, action: String, detail: String) {
        rows.put(
            JSONObject()
                .put("label", label)
                .put("value", detail)
                .put("action", action)
                .put("button", "Send")
        )
    }

    private fun error(t: Throwable): String {
        val root = generateSequence(t) { it.cause }.last()
        return (root.message ?: root.javaClass.simpleName).take(280)
    }
}
