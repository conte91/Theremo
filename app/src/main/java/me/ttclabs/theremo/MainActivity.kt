// vim: set filetype=kotlin ts=4 sw=4 et:
package me.ttclabs.theremo


import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.media.midi.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import me.ttclabs.theremo.R

const val MIDI_MAX_VALUE = 127

fun Int.dpToPx(context: Context) = (this * context.resources.displayMetrics.density).toInt()

data class MidiControlRange(val min: Int, val max: Int) {
    init {
        require(min in 0..127) { "min must be between 0 and 127" }
        require(max in 0..127) { "max must be between 0 and 127" }
    }
}

interface MidiValueFormatter {
    fun valToString(value: Int, min: Int, max: Int): String
}

class PercentMidiValueFormatter : MidiValueFormatter {
    override fun valToString(value: Int, min: Int, max: Int): String = "${(value.toFloat() / (max - min).toFloat() * 100).toInt()}%"
}

class BilateralPercentMidiValueFormatter(private val minPercent: Int, private val maxPercent: Int) : MidiValueFormatter {
    override fun valToString(value: Int, min: Int, max: Int): String {
        // for bilateral parameters: 0 is min%, 64 is dead zero, 127 is max%.
        val ratio = (value - min).toDouble() / (max - min).toDouble()
        val percentValue = ratio * (maxPercent - minPercent) + minPercent
        return String.format("%.02f%%", percentValue)
    }
}

class LinearMidiValueFormatter(private val min: Double, private val max: Double) : MidiValueFormatter {
    override fun valToString(value: Int, min: Int, max: Int): String {
        val ratio = (value - min).toDouble() / (max - min).toDouble()
        val actualValue = ratio * (this.max - this.min) + this.min
        return String.format("%.02f", actualValue)
    }
}

class MidiParameter(val name: String, val cc: Int, val default: Int, private val formatter: MidiValueFormatter, val range: MidiControlRange = MidiControlRange(0, 127)) {
    init {
        require(default in range.min..range.max) { "defaultValue must be between ${range.min} and ${range.max}" }
    }
    fun valToString(value: Int): String = formatter.valToString(value, range.min, range.max)
    fun defaultValue(): Int = default
}

fun percentMidiParameter(name: String, cc: Int, defaultPercent: Int = 0): MidiParameter {
    return MidiParameter(name, cc, (defaultPercent * 127 / 100), PercentMidiValueFormatter())
}

fun bilateralMidiParameter(name: String, cc: Int, minPercent: Int, maxPercent: Int): MidiParameter {
    return MidiParameter(name, cc, 64, BilateralPercentMidiValueFormatter(minPercent, maxPercent))
}

fun linearMidiParameter(name: String, cc: Int, min: Double, max: Double, defaultMidiVal: Int = 0): MidiParameter {
    return MidiParameter(name, cc, defaultMidiVal, LinearMidiValueFormatter(min, max))
}

fun labeledSliderView(
    context: Context,
    theremidi: ThereminiState,
    parameter: MidiParameter,
): View {
    val layout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(16.dpToPx(context),16.dpToPx(context),16.dpToPx(context),16.dpToPx(context))
    }
    val labelView = TextView(context, null, 0, R.style.SeekBarText).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }
    // initialize to default
    val seek = MidiSeekBar(ContextThemeWrapper(context, R.style.CustomSeekBar), theremidi, parameter.cc, parameter.range, {
        val humanReadable = it?.let { parameter.valToString(it) } ?: "???"
        labelView.text = "${parameter.name}: ${humanReadable} (min: ${parameter.valToString(parameter.range.min)}, max: ${parameter.valToString(parameter.range.max)})"
    }).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            48.dpToPx(context)
        ).apply { topMargin = 8.dpToPx(context) }
    }

    val resetBtn = Button(context).apply {
        text = "Default (${parameter.valToString(parameter.defaultValue())})"
        setOnClickListener {
            seek.setProgressAndNotify(parameter.defaultValue())
        }
    }
    val controls = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 8.dpToPx(context) }
    }

    seek.layoutParams = LinearLayout.LayoutParams(
        0,
        48.dpToPx(context),
        1f
    )

    resetBtn.layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply {
        marginStart = 8.dpToPx(context)
    }

    controls.addView(seek)
    controls.addView(resetBtn)

    layout.addView(labelView)
    layout.addView(controls)
    return layout
}

class ThereminiConnection(
    private val midiPort: MidiInputPort
) {
    private val TAG = ThereminiConnection::class.java.simpleName ?: "ThereminiConnection"

    fun sendCC(cc: Int, value: Int) {
        val msg = byteArrayOf(0xB0.toByte(), cc.toByte(), value.toByte())
        try {
            midiPort?.send(msg, 0, msg.size) ?: throw Exception("MIDI port not initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending MIDI CC $cc: ${e.message}")
            throw e
        }
    }

    fun close() {
        midiPort.close()
    }
}

class ThereminiState(private val connection: ThereminiConnection) {
    private val midiParams = mutableMapOf<Int, Int?>()

    fun setParam(cc: Int, value: Int) {
        connection.sendCC(cc, value)
        midiParams[cc] = value
    }

    fun getCachedValue(cc: Int): Int? {
        return midiParams[cc]
    }

    fun close() {
        connection.close()
    }
}

class ResettableSeekBar(
    context: Context,
    min: Int,
    max: Int,
    startValue: Int?,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.seekBarStyle
) : SeekBar(context, attrs, defStyleAttr) {

    init {
        this.min = min
        this.max = max
        progress = startValue?:0
    }

    private var userHasInteracted = false
    private var externalListener: OnSeekBarChangeListener? = null

    private val compositeListener = object : OnSeekBarChangeListener {
        override fun onStartTrackingTouch(sb: SeekBar?) {
            userHasInteracted = true
            externalListener?.onStartTrackingTouch(sb)
        }
        override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
            if (!userHasInteracted) return
            externalListener?.onProgressChanged(sb, p, fromUser)
        }
        override fun onStopTrackingTouch(sb: SeekBar?) {
            if (userHasInteracted) externalListener?.onStopTrackingTouch(sb)
        }
    }

    init {
        super.setOnSeekBarChangeListener(compositeListener)
    }

    override fun setOnSeekBarChangeListener(listener: OnSeekBarChangeListener?) {
        externalListener = listener
    }

    /** Call to set progress programmatically *and* fire the listener **/
    fun setProgressAndNotify(p: Int) {
        userHasInteracted = true
        super.setProgress(p)
    }

    /** Reset to “undefined” so next change comes only from user or setProgressAndNotify **/
    fun reset() {
        userHasInteracted = false
    }

    /** Check if it’s been explicitly set yet **/
    fun hasBeenSet(): Boolean = userHasInteracted
}

fun MidiSeekBar (
    context: Context,
    theremidi: ThereminiState,
    cc: Int = 0,
    range: MidiControlRange,
    changeCallback: (value: Int?) -> Unit
): ResettableSeekBar {
    val currentVal = theremidi.getCachedValue(cc)
    var seekbar = ResettableSeekBar(context, range.min, range.max, currentVal)
    seekbar.setOnSeekBarChangeListener (object: OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
            try {
                theremidi.setParam(cc, p)
                changeCallback(p)
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to send MIDI CC $cc", Toast.LENGTH_SHORT).show()
            }
        }
        override fun onStartTrackingTouch(sb: SeekBar?) {
        }
        override fun onStopTrackingTouch(sb: SeekBar?) {
        }
    })

    // Advertise current value to listeners
    changeCallback(currentVal)
    return seekbar
}

class MainActivity : AppCompatActivity() {
    private var midiDevice: MidiDevice? = null
    private var devicePollHandler: Handler? = null
    private var devicePollRunnable: Runnable? = null
    private var theremidi: ThereminiState? = null

    data class MidiControlPage(val title: String, val createFragment: () -> Fragment)

    companion object {
        private val TAG = MainActivity::class.java.simpleName ?: "MainActivity"
        lateinit var instance: MainActivity

    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        showDeviceSelection()
    }

    fun showDeviceSelection() {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val midiManager = getSystemService(MIDI_SERVICE) as MidiManager
        val deviceContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        layout.addView(TextView(this).apply { text = "Select a MIDI device:" })
        layout.addView(deviceContainer)

        fun updateDeviceList() {
            deviceContainer.removeAllViews()
            val devices = midiManager.devices
            if (devices.isEmpty()) {
                deviceContainer.addView(TextView(this).apply { text = "No MIDI devices found." })
            } else {
                devices.forEachIndexed { index, device ->
                    deviceContainer.addView(Button(this).apply {
                        val props = device.getProperties()
                        val name = props.getString(MidiDeviceInfo.PROPERTY_NAME)
                        val manufacturer = props.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER)
                        val product = props.getString(MidiDeviceInfo.PROPERTY_PRODUCT)
                        text = "Device $index: $name, Manufacturer: $manufacturer, Product: $product"
                        setOnClickListener {
                            midiManager.openDevice(device, { dev ->
                                if (dev == null) {
                                    toast("Failed to open device.")
                                    return@openDevice
                                }
                                midiDevice = dev
                                var midiPort = dev.openInputPort(0)
                                if (midiPort == null) {
                                    toast("Failed to open device's MIDI port.")
                                    return@openDevice
                                }
                                theremidi = ThereminiState(ThereminiConnection(midiPort))
                                runOnUiThread {
                                    stopDevicePolling()
                                    showMainUI()
                                }
                            }, null)
                        }
                    })
                }
            }
        }

        devicePollHandler = Handler(Looper.getMainLooper())
        devicePollRunnable = object : Runnable {
            override fun run() {
                updateDeviceList()
                devicePollHandler?.postDelayed(this, 1000)
            }
        }
        devicePollHandler?.post(devicePollRunnable!!)
        setContentView(layout)
    }

    private fun stopDevicePolling() {
        devicePollRunnable?.let { devicePollHandler?.removeCallbacks(it) }
        devicePollHandler = null
        devicePollRunnable = null
    }

    private fun showMainUI() {
        val tabLayout = TabLayout(this).apply {
            tabMode = TabLayout.MODE_SCROLLABLE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val viewPager = ViewPager2(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tabLayout)
            addView(viewPager)
        }
        setContentView(container)

        val pages = listOf(
            MidiControlPage("Volume & Range", {MainFragment(this, theremidi!!)}),
            MidiControlPage("Pitch Correction", {PitchCorrectionFragment(this, theremidi!!)}),
            MidiControlPage("Transpose", {TransposeFragment(this, theremidi!!)}),
            MidiControlPage("Waveform", {WaveformFragment(this, theremidi!!)}),
            MidiControlPage("Filter", {FilterFragment(this, theremidi!!)}),
            MidiControlPage("Volume Antenna", {VolumeAntennaFragment(this, theremidi!!)}),
            MidiControlPage("Pitch Antenna", {PitchAntennaFragment(this, theremidi!!)}),
            MidiControlPage("Mod Targeting", {ModTargetFragment(this, theremidi!!)}),
            MidiControlPage("Scan/Wavetable", {ScanFragment(this, theremidi!!)}),
            MidiControlPage("Delay", {DelayFragment(this, theremidi!!)}),
            MidiControlPage("Preset", {PresetFragment(this, theremidi!!)}),
            MidiControlPage("Back to Setup", {DeviceSetupFragment(this)}),
        )
        viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = pages.size
            override fun createFragment(position: Int): Fragment =
            pages[position].createFragment()
        }
        TabLayoutMediator(tabLayout, viewPager) { tab, pos ->
            tab.text = pages[pos].title
        }.attach()
    }

    class MainFragment(private val context: Context, private val theremidi: ThereminiState) : Fragment() {
        override fun onCreateView(
            inflater: LayoutInflater, c: ViewGroup?, s: Bundle?
        ): View = ScrollView(context).apply {
            val layout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            layout.addView(labeledSliderView(context, theremidi, percentMidiParameter("Master Volume", 7, 100)))
            layout.addView(labeledSliderView(context, theremidi, percentMidiParameter("Low Note", 87, 0)))
            layout.addView(labeledSliderView(context, theremidi, percentMidiParameter("High Note", 88, 0)))
            return ScrollView(context).apply { addView(layout) }
        }
    }

    class PitchCorrectionFragment(private val context: Context, private val theremidi: ThereminiState) : Fragment() {
        override fun onCreateView(
            i: LayoutInflater, c: ViewGroup?, s: Bundle?
        ): View {
            val layout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            layout.addView(labeledSliderView(context, theremidi, percentMidiParameter("Pitch Correction", 84, 0)))
            layout.addView(TextView(context).apply { text = "Root Note (CC 86)" })
            listOf("C","C#","D","D#","E","F","F#","G","G#","A","A#","B")
            .forEachIndexed { idx, name ->
                layout.addView(Button(context).apply {
                    text = name
                    setOnClickListener { theremidi.setParam(86, idx) }
                })
            }
            layout.addView(TextView(context).apply { text = "Scale (CC 85)" })
            listOf(
                "Chromatic","Ionian","Dorian","Phrygian","Lydian","Mixolydian","Aeolian",
                "Locrian","Maj Blues","Min Blues","Dim","Maj Penta","Min Penta","Spanish",
                "Gypsy","Arabian","Egyptian","Ryukyu","Wholetone","Maj 3rd","Min 3rd","5th"
            ).forEachIndexed { idx, name ->
                layout.addView(Button(context).apply {
                    text = "$idx: $name"
                    setOnClickListener { theremidi.setParam(85, idx) }
                })
            }
            return ScrollView(context).apply { addView(layout) }
        }
    }

    class TransposeFragment(private val context: Context, private val theremidi: ThereminiState) : Fragment() {
        override fun onCreateView(
            i: LayoutInflater, c: ViewGroup?, s: Bundle?
        ): View = ScrollView(context).apply {
            addView(
                labeledSliderView(
                    context,
                    theremidi,
                    percentMidiParameter("Transpose", 102, 0)
                )
            )
        }
    }

    class WaveformFragment(private val context: Context, private val theremidi: ThereminiState)  : Fragment() {
        override fun onCreateView(
            i: LayoutInflater, c: ViewGroup?, s: Bundle?
        ): View {
            val layout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            layout.addView(TextView(context).apply { text = "Waveform (CC 90)" })
            listOf("Sine","Triangle","Super Saw","Animoog 1","Animoog 2","Animoog 3","Etherwave")
            .forEachIndexed { idx, name ->
                layout.addView(Button(context).apply {
                    text = "$idx: $name"
                    setOnClickListener { theremidi.setParam(90, idx) }
                })
            }
            return ScrollView(context).apply { addView(layout) }
        }
    }

    class FilterFragment(private val context: Context, private val theremidi: ThereminiState) : Fragment() {
        override fun onCreateView(
            i: LayoutInflater, c: ViewGroup?, s: Bundle?
        ): View {
            val layout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            layout.addView(labeledSliderView(context, theremidi, percentMidiParameter("Filter cutoff", 74, 0)));
            layout.addView(labeledSliderView(context, theremidi, percentMidiParameter("Filter Resonance", 71, 0)))
            layout.addView(TextView(context).apply { text = "Filter Type (CC 80)" })
            listOf("Bypass", "Lowpass", "Bandpass", "Highpass", "Notch", "Animoog 3", "Etherwave")
            .forEachIndexed { idx, name ->
                layout.addView(Button(context).apply {
                    text = "$idx: $name"
                    setOnClickListener { theremidi.setParam(80, idx) }
                })
            }
            return ScrollView(context).apply { addView(layout) }
        }
    }

    class PitchAntennaFragment(private val context: Context, private val theremidi: ThereminiState) : Fragment() {
        override fun onCreateView(
            i: LayoutInflater, c: ViewGroup?, s: Bundle?
        ): View {
            val layout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            layout.addView(TextView(context, null, 0, R.style.SeekBarText).apply {
                text = "These settings affect how much the pitch antenna modifies the filter and other parameters."
            })
            layout.addView(labeledSliderView(context, theremidi, bilateralMidiParameter("Filter Cutoff Pitch Tracking", 29, -800, 800)))
            layout.addView(labeledSliderView(context, theremidi, bilateralMidiParameter("Filter Resonance", 30, -400, 400)))
            layout.addView(labeledSliderView(context, theremidi, bilateralMidiParameter("Wavetable Scan Amount", 24, -400, 400)))
            layout.addView(labeledSliderView(context, theremidi, bilateralMidiParameter("Wavetable Scan Frequency", 22, -400, 400)))
            return ScrollView(context).apply { addView(layout) }
        }
    }

    class VolumeAntennaFragment(private val context: Context, private val theremidi: ThereminiState) : Fragment() {
        override fun onCreateView(
            i: LayoutInflater, c: ViewGroup?, s: Bundle?
        ): View {
            val layout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            layout.addView(TextView(context, null, 0, R.style.SeekBarText).apply {
                text = "These settings affect how much the volume antenna modifies the filter and other params."
            })
            layout.addView(labeledSliderView(context, theremidi, bilateralMidiParameter("Filter Cutoff", 27, -100, 100)))
            layout.addView(labeledSliderView(context, theremidi, bilateralMidiParameter("Filter Resonance", 28, -200, 200)))
            layout.addView(labeledSliderView(context, theremidi, bilateralMidiParameter("Volume", 26, 0, 1600)))
            layout.addView(labeledSliderView(context, theremidi, bilateralMidiParameter("Wavetable Scan Amount", 25, -400, 400)))
            layout.addView(labeledSliderView(context, theremidi, bilateralMidiParameter("Wavetable Scan Frequency", 23, -400, 400)))
            return ScrollView(context).apply { addView(layout) }
        }
    }

    class ModTargetFragment(private val context: Context, private val theremidi: ThereminiState) : Fragment() {
        override fun onCreateView(
            i: LayoutInflater, c: ViewGroup?, s: Bundle?
        ): View {
            val layout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            return ScrollView(context).apply { addView(layout) }
        }
    }

    class ScanFragment(private val context: Context, private val theremidi: ThereminiState) : Fragment() {
        override fun onCreateView(
            i: LayoutInflater, c: ViewGroup?, s: Bundle?
        ): View {
            val layout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val scanRateFormatter = object : MidiValueFormatter {
                override fun valToString(value: Int, min: Int, max: Int): String {
                    var f = String.format("%.02f", value.toFloat() / (max - min).toFloat() * 32)
                    return "${f}Hz"
                }
            }
            layout.addView(labeledSliderView(context, theremidi, MidiParameter("Wavetable Scan Rate", 9, 0, scanRateFormatter)))
            layout.addView(labeledSliderView(context, theremidi, linearMidiParameter("Scan Amount", 20, 0.0, 2.0)))
            layout.addView(labeledSliderView(context, theremidi, linearMidiParameter("Scan Position", 21, 0.0, 2.0)))
            return ScrollView(context).apply { addView(layout) }
        }
    }

    class DelayFragment(private val context: Context, private val theremidi: ThereminiState) : Fragment() {
        override fun onCreateView(
            i: LayoutInflater, c: ViewGroup?, s: Bundle?
        ): View {
            val layout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            layout.addView(labeledSliderView(context, theremidi, linearMidiParameter("Delay Time", 12, 0.0, 0.83)))
            layout.addView(labeledSliderView(context, theremidi, percentMidiParameter("Delay Feedback", 14, 0)))
            layout.addView(labeledSliderView(context, theremidi, percentMidiParameter("Effect Mix", 91, 0)))
            return ScrollView(context).apply { addView(layout) }
        }
    }

    private fun toast(msg: String) = runOnUiThread {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        stopDevicePolling()
        theremidi?.close()
        midiDevice?.close()
        super.onDestroy()
    }
}

class PresetFragment(private val context: Context, private val theremidi: ThereminiState) : Fragment() {
    override fun onCreateView(
        i: LayoutInflater, c: ViewGroup?, s: Bundle?
    ): View {
        val layout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        layout.addView(labeledSliderView(context, theremidi, percentMidiParameter("Preset Volume", 103, 100)))
        layout.addView(Button(context).apply {
            text = "Save Preset (CC 119)"
            setOnClickListener { theremidi.setParam(119, 0) }
        })
        return ScrollView(context).apply { addView(layout) }
    }
}

class DeviceSetupFragment(private val context: Context) : Fragment() {
    override fun onCreateView(
        i: LayoutInflater, c: ViewGroup?, s: Bundle?
    ): View = ScrollView(context).apply {
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(Button(context).apply {
                text = "Choose MIDI Device"
                setOnClickListener { (activity as? MainActivity)?.showDeviceSelection() }
            })
        })
    }
}
