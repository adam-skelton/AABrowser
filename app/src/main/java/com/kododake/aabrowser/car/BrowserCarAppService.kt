package com.kododake.aabrowser.car

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

class BrowserCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator {
        return if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }
    }

    override fun onCreateSession(): Session = BrowserCarSession()
}

class BrowserCarSession : Session() {
    private lateinit var webHost: CarWebViewHost

    override fun onCreateScreen(intent: Intent): Screen {
        webHost = CarWebViewHost(carContext)
        webHost.register()
        requestLocationIfNeeded()
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                webHost.onForegrounded()
            }

            override fun onDestroy(owner: LifecycleOwner) {
                webHost.destroy()
            }
        })
        return BrowserCarScreen(carContext, webHost)
    }

    private fun requestLocationIfNeeded() {
        val needed = LOCATION_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(carContext, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) return
        carContext.requestPermissions(needed) { _, _ -> }
    }

    private companion object {
        val LOCATION_PERMISSIONS = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }
}
