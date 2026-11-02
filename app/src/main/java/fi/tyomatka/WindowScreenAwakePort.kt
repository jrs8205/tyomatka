package fi.tyomatka

import android.app.Activity
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager

/**
 * ScreenAwakePortin ikkuna- ja anturitoteutus. Valoanturi (TYPE_LIGHT) on
 * puhelimen yläosassa — juuri siinä osassa, joka menee autotelineen koloon
 * piiloon — joten lux putoaa nollaan kolossa ja nousee heti nostettaessa.
 * Himmennys on OLED-tietoinen: kirkkaus minimiin JA koko ruudun peittävä
 * musta verho — mustat pikselit eivät kuluta OLEDilla, joten näyttö
 * näyttää ja kuluttaa kuin sammunut. Ilman valoanturia himmennystä ei
 * tapahdu lainkaan.
 */
internal class WindowScreenAwakePort(
    private val activity: Activity,
    private val onLux: (Float) -> Unit,
) : ScreenAwakePort, SensorEventListener {

    private val sensorManager = activity.getSystemService(SensorManager::class.java)
    private val lightSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)

    /** Koko ruudun (myös tilarivin) peittävä musta verho himmennettynä. */
    private var veil: View? = null

    override fun setKeepScreenOn(on: Boolean) {
        if (on) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun setDimmed(dimmed: Boolean) {
        val attributes = activity.window.attributes
        attributes.screenBrightness = if (dimmed) {
            ScreenAwakeController.DIM_BRIGHTNESS
        } else {
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        activity.window.attributes = attributes
        // Verho decor-tasolle: peittää myös tilarivin, jolloin OLED-pikselit
        // sammuvat kokonaan ja näyttö näyttää sammuneelta.
        val decor = activity.window.decorView as ViewGroup
        if (dimmed) {
            if (veil == null) {
                veil = View(activity).apply { setBackgroundColor(Color.BLACK) }.also {
                    decor.addView(
                        it,
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        ),
                    )
                }
            }
        } else {
            veil?.let(decor::removeView)
            veil = null
        }
    }

    override fun startLightListening() {
        lightSensor?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun stopLightListening() {
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        onLux(event.values[0])
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
