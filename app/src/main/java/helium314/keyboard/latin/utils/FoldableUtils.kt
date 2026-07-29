// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.content.ComponentCallbacks
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.database.ContentObserver
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.define.DebugFlags
import helium314.keyboard.latin.settings.Settings as KeyboardSettings
import java.util.regex.Pattern
import kotlin.text.split

object FoldableUtils {
    private const val TAG = "FoldableUtils"
    private const val FOLDED_SMALLEST_WIDTH_DP = 600

    const val PREF_FORCE_FOLDABLE = "force_foldable_device"

    private var applicationContext: Context? = null
    private var manualOverrideEnabled = false
    private var callbacksRegistered = false

    var isFoldable = false
        private set

    var isFolded = false
        private set(value) {
            if (field == value) return
            Log.v(TAG, "set isFolded to $value")
            field = value
        }

    private val configurationCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            updateManualFoldedState(newConfig, reloadKeyboard = true)
        }

        override fun onLowMemory() {}
    }

    /** Set [isFoldable] and initialize manual fold-state tracking when requested by the user. */
    fun init(context: Context) {
        val appContext = context.applicationContext
        applicationContext = appContext
        if (!callbacksRegistered) {
            appContext.registerComponentCallbacks(configurationCallbacks)
            callbacksRegistered = true
        }

        manualOverrideEnabled = appContext.prefs().getBoolean(PREF_FORCE_FOLDABLE, false)
        isFoldable = manualOverrideEnabled || getFeatureString(appContext) != null || hasFoldSensor(appContext)
        if (manualOverrideEnabled)
            updateManualFoldedState(appContext.resources.configuration, reloadKeyboard = true)
        Log.i(TAG, if (isFoldable) "foldable" else "not foldable")
    }

    /**
     * Manual foldable mode uses the current display's narrow dimension.
     * Book-style cover displays are below 600 dp, while the unfolded inner display is 600 dp or wider.
     */
    private fun updateManualFoldedState(configuration: Configuration, reloadKeyboard: Boolean): Boolean {
        if (!manualOverrideEnabled) return false
        val smallestWidth = minOf(configuration.screenWidthDp, configuration.screenHeightDp)
        if (smallestWidth <= 0) return false
        return updateFoldedState(smallestWidth < FOLDED_SMALLEST_WIDTH_DP, reloadKeyboard)
    }

    private fun updateFoldedState(folded: Boolean, reloadKeyboard: Boolean): Boolean {
        if (isFolded == folded) return false
        isFolded = folded
        if (reloadKeyboard)
            reloadKeyboardForFoldState()
        return true
    }

    private fun reloadKeyboardForFoldState() {
        val context = applicationContext ?: return
        val settings = KeyboardSettings.getInstance()
        val current = settings.current ?: return
        settings.loadSettings(context, current.mLocale, current.mInputAttributes)
        KeyboardSwitcher.getInstance().reloadKeyboard()
    }

    private fun hasFoldSensor(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && context.packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_HINGE_ANGLE))
            return true
        if (DebugFlags.DEBUG_ENABLED) {
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            sm.getSensorList(Sensor.TYPE_ALL).forEach {
                if (it.name.contains("hinge", true) || it.name.contains("fold", true))
                    Log.v(TAG, "no default hinge sensor, but found ${it.name} with range ${it.maximumRange}")
            }
        }
        return false
    }

    /*
     * much of the code related to display_features is modified from https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/libs/WindowManager/Jetpack/src/androidx/window
     * apparently there is some information encoded in undocumented "display_features" setting in Settings.Global
     * found values
     *  null (we assume this means not foldable, and otherwise the device is foldable -> would need more testing)
     *  empty (when folded, at least according to the user who posted the logs)
     *   ca 40° hinge both directions
     *  fold-[1124,0,1124,2480]-half-opened -> AFTER configuration change (regex no match, but why?)
     *   at ca 40° hinge when opening, 140° when closing
     *  fold-[1124,0,1124,2480]-flat -> no configuration change
     *   at ca 160° hinge
     */
    private const val DISPLAY_FEATURES = "display_features"
    private val displayFeaturesUri = Settings.Global.getUriFor(DISPLAY_FEATURES)
    private val FEATURE_PATTERN = Pattern.compile("([a-z]+)-\\[(\\d+),(\\d+),(\\d+),(\\d+)]-?(flat|half-opened)?")
    private const val PATTERN_STATE_FLAT = "flat"
    private const val PATTERN_STATE_HALF_OPENED = "half-opened"

    fun getFeatureString(context: Context): String? = Settings.Global.getString(context.contentResolver, DISPLAY_FEATURES)

    private fun extractFoldedState(displayFeatures: String): Boolean {
        if (displayFeatures.isEmpty()) return true
        displayFeatures.split(";").forEach {
            try {
                val matcher = FEATURE_PATTERN.matcher(it)
                if (!matcher.matches()) return@forEach
                val featureType = matcher.group(1)
                val state = matcher.group(6)

                if (DebugFlags.DEBUG_ENABLED)
                    Log.d(TAG, "found: type $featureType, state $state")
                return (state != PATTERN_STATE_FLAT && state != PATTERN_STATE_HALF_OPENED)
            } catch (e: Exception) {
                Log.w(TAG, "error when checking $it", e)
            }
        }

        return false
    }

    /** Observes changes to [DISPLAY_FEATURES] or hinge angle, and updates [isFolded] on changes. */
    class FoldableObserver(context: Context) {
        private val context = context.applicationContext
        var sensorForDebug = false

        private val featureStringObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                if (manualOverrideEnabled || uri != displayFeaturesUri) return
                val featuresString = getFeatureString(context)
                if (featuresString == null) {
                    Log.w(TAG, "$DISPLAY_FEATURES are unexpectedly null")
                    return
                }
                if (DebugFlags.DEBUG_ENABLED)
                    Log.v(TAG, "$DISPLAY_FEATURES changed: $featuresString")
                updateFoldedState(extractFoldedState(featuresString), reloadKeyboard = true)
            }
        }

        private val sensorListener = object : SensorEventListener {
            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
            override fun onSensorChanged(event: SensorEvent) {
                if (manualOverrideEnabled) return
                val angle = event.values?.getOrNull(0)
                if (!sensorForDebug)
                    updateFoldedState((angle ?: 180f) < 40, reloadKeyboard = true)
                if (DebugFlags.DEBUG_ENABLED)
                    Log.v(TAG, "sensor changed: ${event.values?.toList()}")
            }
        }

        init {
            if (manualOverrideEnabled) {
                updateManualFoldedState(this.context.resources.configuration, reloadKeyboard = false)
                Log.v(TAG, "using display width override, folded: $isFolded")
            } else {
                val featureString = getFeatureString(this.context)
                if (featureString != null) {
                    this.context.contentResolver.registerContentObserver(displayFeaturesUri, false, featureStringObserver)
                    updateFoldedState(extractFoldedState(featureString), reloadKeyboard = false)
                    Log.v(TAG, "using $DISPLAY_FEATURES, folded: $isFolded")
                }
                if (hasFoldSensor(this.context) && (featureString == null || DebugFlags.DEBUG_ENABLED)) {
                    sensorForDebug = featureString != null
                    val sm = this.context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
                    sm.registerListener(sensorListener, sm.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE), SensorManager.SENSOR_DELAY_UI)
                    Log.v(TAG, "using sensor, for debugging only: $sensorForDebug")
                }
            }
        }

        fun unregister(context: Context) {
            context.contentResolver.unregisterContentObserver(featureStringObserver)
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            sm.unregisterListener(sensorListener)
        }
    }
}
