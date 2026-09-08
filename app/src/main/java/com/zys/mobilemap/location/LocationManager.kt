package com.zys.mobilemap.location

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.os.Bundle
import android.os.Looper
import android.util.Log
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.concurrent.Volatile

/**
 * 定位管理器（基于 Android 原生定位 API，无需 GMS 依赖）。
 * 使用方法：
 * LocationManager.getInstance().init(application)
 * LocationManager.getInstance().startLocation(false)
 */
class LocationManager private constructor() {

    fun interface OnLocationChangedListener {
        fun onLocationChanged(location: Location?)
    }

    private var systemLocationManager: android.location.LocationManager? = null
    private var locationListener: LocationListener? = null

    @Volatile
    private var isLocating = false

    @Volatile
    private var isBackgroundMode = false

    @Volatile
    private var lastLocation: Location? = null

    private var listener: OnLocationChangedListener? = null

    /** 附加监听器集合：定位展示之外的模块（如轨迹记录）订阅位置更新 */
    private val extraListeners = CopyOnWriteArraySet<OnLocationChangedListener>()

    /**
     * 初始化定位管理器，需要在使用前调用一次。
     */
    fun init(application: Application?) {
        if (application == null) return
        systemLocationManager =
            application.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
    }

    fun setOnLocationChangedListener(listener: OnLocationChangedListener?) {
        this.listener = listener
    }

    fun addLocationListener(listener: OnLocationChangedListener) {
        extraListeners.add(listener)
    }

    fun removeLocationListener(listener: OnLocationChangedListener) {
        extraListeners.remove(listener)
    }

    @SuppressLint("MissingPermission")
    fun startLocation(background: Boolean) {
        val lm = systemLocationManager ?: return
        if (isLocating) return
        isLocating = true
        isBackgroundMode = background

        locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                lastLocation = location
                listener?.onLocationChanged(location)
                for (extra in extraListeners) {
                    extra.onLocationChanged(location)
                }
            }

            // onStatusChanged 自 API 29 起弃用且无实际回调；低版本设备上仍为抽象方法，
            // 必须保留空实现（删除会在旧设备抛 AbstractMethodError），仅抑制弃用告警
            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

            override fun onProviderEnabled(provider: String) {}

            override fun onProviderDisabled(provider: String) {}
        }

        val callback = locationListener ?: return
        try {
            val providers = lm.getProviders(true)
            for (provider in providers) {
                try {
                    lm.requestLocationUpdates(provider, MIN_INTERVAL_MS, MIN_DISTANCE_M, callback,
                        Looper.getMainLooper())
                    // 用各提供者的最后已知位置刷新缓存（取时间更新的一条）
                    val last = lm.getLastKnownLocation(provider)
                    // 先存局部变量：lastLocation 为可空字段无法智能转换，免写 !!
                    val cached = lastLocation
                    if (last != null && (cached == null || last.time > cached.time)) {
                        lastLocation = last
                        listener?.onLocationChanged(last)
                        for (extra in extraListeners) {
                            extra.onLocationChanged(last)
                        }
                    }
                } catch (e: SecurityException) {
                    Log.w(TAG, "no permission for provider $provider", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "startLocation failed", e)
            isLocating = false
        }
    }

    fun stopLocation() {
        val lm = systemLocationManager ?: return
        if (!isLocating) return
        isLocating = false
        isBackgroundMode = false
        locationListener?.let { lm.removeUpdates(it) }
        locationListener = null
    }

    fun isLocating(): Boolean {
        return isLocating
    }

    fun getLastLocation(): Location? {
        return lastLocation
    }

    companion object {
        private const val TAG = "LocationManager"

        /** 最小更新间隔（毫秒） */
        private const val MIN_INTERVAL_MS = 2000L

        /** 最小更新距离（米） */
        private const val MIN_DISTANCE_M = 0f

        @Volatile
        private var instance: LocationManager? = null

        fun getInstance(): LocationManager {
            return instance ?: synchronized(this) {
                instance ?: LocationManager().also { instance = it }
            }
        }
    }
}