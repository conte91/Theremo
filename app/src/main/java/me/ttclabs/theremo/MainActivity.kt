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
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.time.format.DateTimeFormatter
import java.time.Instant
import java.time.ZoneId
import me.ttclabs.theremo.R


const val MIDI_MAX_VALUE = 127

fun Int.dpToPx(context: Context) = (this * context.resources.displayMetrics.density).toInt()

fun ByteArray.toHexString(): String =
    joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

data class MidiControlRange(val min: Int, val max: Int) {
    init {
        require(min in 0..127) { "min must be between 0 and 127" }
        require(max in 0..127) { "max must be between 0 and 127" }
    }
}

interface MidiValueFormatter {
    fun valToString(value: Int): String
}

class PercentMidiValueFormatter(private var minPercent: Int, private var maxPercent: Int) : MidiValueFormatter {
    override fun valToString(value: Int): String {
        val ratio = value / 127f
        val percent = (ratio * (maxPercent - minPercent)).toInt() + minPercent
        return "${percent}%"
    }
}

class BilateralPercentMidiValueFormatter(private val extreme: Int) : MidiValueFormatter {
    override fun valToString(value: Int): String {
        // for bilateral parameters: 0 is min%, 64 is dead zero, 127 is max%.
        val percentValue = when {
            value < 64  -> -extreme * (1 - value / 64.0)            // 0→min, 64→0
            value > 64  -> extreme * ((value - 64) / 63.0)        // 64→0, 127→max
            else     -> 0.0                            // cc==64
        }
        return String.format("%.02f%%", percentValue)
    }
}

class LinearMidiValueFormatter(private val min: Double, private val max: Double) : MidiValueFormatter {
    override fun valToString(value: Int): String {
        val ratio = value / 127.0
        val actualValue = ratio * (max - min) + min
        return String.format("%.02f", actualValue)
    }
}

class MidiParameter(val name: String, val cc: Int, val default: Int, private val formatter: MidiValueFormatter, val range: MidiControlRange = MidiControlRange(0, 127)) {
    fun valToString(value: Int): String = formatter.valToString(value)
    fun defaultValue(): Int = default
}

fun percentMidiParameter(name: String, cc: Int, defaultMidiVal: Int = 0, maxPercent: Int = 0): MidiParameter {
    return MidiParameter(name, cc, defaultMidiVal, PercentMidiValueFormatter(0, maxPercent))
}

fun bilateralMidiParameter(name: String, cc: Int, extreme: Int): MidiParameter {
    return MidiParameter(name, cc, 64, BilateralPercentMidiValueFormatter(extreme))
}

fun linearMidiParameter(name: String, cc: Int, min: Double, max: Double, defaultMidiVal: Int = 0): MidiParameter {
    return MidiParameter(name, cc, defaultMidiVal, LinearMidiValueFormatter(min, max))
}

class NoteRangeFormatter : MidiValueFormatter {
    // Notes go from C-1 to G9 (128 notes total)
    // Stupid how some notes are called with # and some with b.
    // But that's how they appear on the device's display :|
    val notes = listOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")
    override fun valToString(value: Int): String = "${notes[value % notes.size]}${value / 12 - 1}"
}

fun noteRangeParameter(name: String, cc: Int, default: Int): MidiParameter {
    return MidiParameter(name, cc, default, NoteRangeFormatter())
}

class TransposeFormatter : MidiValueFormatter {
    // 64 is 0, 0 is -64 semitones, 127 is 63 semitones.
    override fun valToString(value: Int): String = "${value - 64} semitones"
}

fun buttonGrid(context: Context, labels: Collection<String>, nCols: Int, clickCallback: (Int) -> Unit): View {
    val buttons = labels.mapIndexed { idx, label ->
        Button(context).apply {
            text = label
            setOnClickListener { clickCallback(idx) }
        }
    }
    val layout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }
    buttons.chunked(nCols).forEach { chunk ->
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        chunk.forEach { row.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
        layout.addView(row)
    }
    return layout
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

    val seek = MidiSeekBar(ContextThemeWrapper(context, R.style.CustomSeekBar), theremidi, parameter.cc, parameter.range).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            48.dpToPx(context)
        ).apply { topMargin = 8.dpToPx(context) }
    }
    theremidi.setParamCallback(parameter.cc, {
        seek.progress = it?:0
        val humanReadable = it?.let { parameter.valToString(it) } ?: "???"
        labelView.text = "${parameter.name} (CC ${parameter.cc}): ${humanReadable} (min: ${parameter.valToString(parameter.range.min)}, max: ${parameter.valToString(parameter.range.max)})"
    })
    theremidi.setParam(parameter.cc, theremidi.getCachedValue(parameter.cc))

    val resetBtn = Button(context).apply {
        text = "Default (${parameter.valToString(parameter.defaultValue())})"
        setOnClickListener {
            theremidi.setParam(parameter.cc, parameter.defaultValue())
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

data class MidiMessageLog(val send: Boolean, val bytes: ByteArray, val timestampMillis: Long)

class MidiLogBuffer(private val capacity: Int) {
    private val buffer = ArrayDeque<MidiMessageLog>(capacity)
    private var logCallback: ((MidiMessageLog) -> Unit)? = null

    fun log(entry: MidiMessageLog) {
        if (buffer.size == capacity) {
            buffer.removeFirst()
        }
        logCallback?.invoke(entry)
        buffer.addLast(entry)
    }

    // The callback is invoked _before_ the log entry is added into the buffer.
    fun setLogCallback(cb: ((MidiMessageLog) -> Unit)?) {
        logCallback = cb
    }

    fun getLogs(): Collection<MidiMessageLog> {
        return buffer
    }
}

class ThereminiConnection(
    private val midiIn: MidiInputPort,
    private val midiOut: MidiOutputPort,
) {
    private val TAG = ThereminiConnection::class.java.simpleName ?: "ThereminiConnection"
    private val logBuffer = MidiLogBuffer(100)

    init {
        val receiver = object : MidiReceiver() {
            override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
                // Timestamp passed in as parameter is not wall time, it is boot time in ns.
                logBuffer.log(MidiMessageLog(false, msg.copyOfRange(offset, offset + count), System.currentTimeMillis()))
            }
        }
        midiOut.connect(receiver)
    }

    fun sendCC(cc: Int, value: Int) {
        val msg = byteArrayOf(0xB0.toByte(), cc.toByte(), value.toByte())
        try {
            midiIn.send(msg, 0, msg.size) ?: throw Exception("MIDI port not initialized")
            logBuffer.log(MidiMessageLog(true, msg, System.currentTimeMillis()))
        } catch (e: Exception) {
            Log.e(TAG, "Error sending MIDI CC $cc: ${e.message}")
            throw e
        }
    }

    fun getMidiLogBuffer(): MidiLogBuffer {
        return logBuffer
    }

    fun close() {
        midiIn.close()
        midiOut.close()
    }
}

class ThereminiState(private val connection: ThereminiConnection) {
    private val midiParams = mutableMapOf<Int, Int?>()
    private val callbacks = mutableMapOf<Int, ((value: Int?) -> Unit)?>()

    fun setParam(cc: Int, value: Int?) {
        value?.let { connection.sendCC(cc, it) }
        midiParams[cc] = value
        callbacks[cc]?.invoke(value)
    }

    fun getAllParams(): Map<Int, Int?> = midiParams

    fun getCachedValue(cc: Int): Int? {
        return midiParams[cc]
    }

    fun close() {
        connection.close()
    }

    fun getConnection(): ThereminiConnection {
        return connection
    }

    fun setParamCallback(cc: Int, cb: ((value: Int?) -> Unit)?) {
        callbacks[cc] = cb
    }
}

fun MidiSeekBar (
    context: Context,
    theremidi: ThereminiState,
    cc: Int,
    range: MidiControlRange,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.seekBarStyle
): SeekBar {
    var seekbar = SeekBar(context, attrs, defStyleAttr).apply {
        min = range.min
        max = range.max
    }

    seekbar.setOnSeekBarChangeListener (object: OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
            if (fromUser) {
                try {
                    theremidi.setParam(cc, p)
                } catch (e: Exception) {
                    Toast.makeText(context, "Failed to send MIDI CC $cc", Toast.LENGTH_SHORT).show()
                }
            }
        }
        override fun onStartTrackingTouch(sb: SeekBar?) {
        }
        override fun onStopTrackingTouch(sb: SeekBar?) {
        }
    })
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
                        text = "Device $index: $name"
                        setOnClickListener {
                            midiManager.openDevice(device, { dev ->
                                if (dev == null) {
                                    toast("Failed to open device.")
                                    return@openDevice
                                }
                                midiDevice = dev
                                var midiIn = dev.openInputPort(0)
                                if (midiIn == null) {
                                    toast("Failed to open device's MIDI input port.")
                                    return@openDevice
                                }
                                var midiOut = dev.openOutputPort(0)
                                if (midiOut == null) {
                                    toast("Failed to open device's MIDI output port.")
                                    return@openDevice
                                }
                                theremidi = ThereminiState(ThereminiConnection(midiIn, midiOut))
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
            MidiControlPage("Waveform", {WaveformFragment(this, theremidi!!)}),
            MidiControlPage("Filter", {FilterFragment(this, theremidi!!)}),
            MidiControlPage("Volume Antenna", {VolumeAntennaFragment(this, theremidi!!)}),
            MidiControlPage("Pitch Antenna", {PitchAntennaFragment(this, theremidi!!)}),
            MidiControlPage("Mod Targeting", {ModTargetFragment(this, theremidi!!)}),
            MidiControlPage("Scan/Wavetable", {ScanFragment(this, theremidi!!)}),
            MidiControlPage("Delay", {DelayFragment(this, theremidi!!)}),
            MidiControlPage("Preset", {PresetFragment(this, theremidi!!)}),
            MidiControlPage("MIDI log", {MidiLogFragment(this, theremidi!!)}),
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
            layout.addView(labeledSliderView(context, theremidi, percentMidiParameter("Master Volume", 7, 127)))
            layout.addView(labeledSliderView(context, theremidi, noteRangeParameter("Low Note", 87, 12 * 3 /* C2 */)))
            layout.addView(labeledSliderView(context, theremidi, noteRangeParameter("High Note", 88, 12 * 8 /* C7 */ )))
            layout.addView(labeledSliderView(
                    context,
                    theremidi,
                    MidiParameter("Transpose", 102, 64 /* No transposition */, TransposeFormatter())
            ))
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
            layout.addView(buttonGrid(context, listOf("C","C#","D","D#","E","F","F#","G","G#","A","A#","B"), 4, {
                theremidi.setParam(86, it)
            }))
            layout.addView(TextView(context).apply { text = "Scale (CC 85)" })
            layout.addView(buttonGrid(context, listOf(
                "Chromatic","Ionian","Dorian","Phrygian","Lydian","Mixolydian","Aeolian",
                "Locrian","Maj Blues","Min Blues","Dim","Maj Penta","Min Penta","Spanish",
                "Gypsy","Arabian","Egyptian","Ryukyu","Wholetone","Maj 3rd","Min 3rd","5th"
            ), 3, {
                theremidi.setParam(85, it)
            }))
            return ScrollView(context).apply { addView(layout) }
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
            layout.addView(labeledSliderView(context, theremidi, bilateralMidiParameter("Filter Cutoff Pitch Tracking", 29, 800)))
            layout.addView(labeledSliderView(context, theremidi, bilateralMidiParameter("Filter Resonance", 30, 400)))
            layout.addView(labeledSliderView(context, theremidi, bilateralMidiParameter("Wavetable Scan Amount", 24, 400)))
            layout.addView(labeledSliderView(context, theremidi, bilateralMidiParameter("Wavetable Scan Frequency", 22, 400)))
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
            layout.addView(labeledSliderView(context, theremidi, bilateralMidiParameter("Filter Cutoff", 27, 100)))
            layout.addView(labeledSliderView(context, theremidi, bilateralMidiParameter("Filter Resonance", 28, 200)))
            layout.addView(labeledSliderView(context, theremidi, percentMidiParameter("Volume", 26, 8 /* 100% */, 1600)))
            layout.addView(labeledSliderView(context, theremidi, bilateralMidiParameter("Wavetable Scan Amount", 25, 400)))
            layout.addView(labeledSliderView(context, theremidi, bilateralMidiParameter("Wavetable Scan Frequency", 23, 400)))
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
                override fun valToString(value: Int): String {
                    var f = String.format("%.02f", value / 127f * 32)
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

class MidiLogFragment(private val context: Context, private val theremidi: ThereminiState) : Fragment() {
    private val timestampFmt = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
    private val logBuffer = theremidi.getConnection().getMidiLogBuffer()

    private fun formatLog(log: MidiMessageLog): String {
        val direction = if (log.send) ">" else "<"
        val timestamp = timestampFmt.format(Instant.ofEpochMilli(log.timestampMillis))
        val msg = log.bytes.toHexString()
        return "[${timestamp}] ${direction} ${msg}"
    }

    override fun onCreateView(
        i: LayoutInflater, c: ViewGroup?, s: Bundle?
    ): View {
        val view = TextView(context).apply {
            val logs = logBuffer.getLogs()
            if (logs.isEmpty()) {
                text = "<No MIDI logs available>"
            } else {
                text = logs.joinToString("\n") { formatLog(it) }
            }
        }
        logBuffer.setLogCallback({ log ->
            view.post {
                // If we had the "No MIDI logs" message, reset it.
                if (logBuffer.getLogs().isEmpty()) {
                    view.text = ""
                }
                view.append("\n${formatLog(log)}")
            }
        })
        return ScrollView(context).apply { addView(view) }
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

class PresetDB(context: Context) {
    private val presets = context.getSharedPreferences("midi_presets", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun savePreset(name: String, params: Map<Int, Int>) {
        presets.edit()
            .putString(name, gson.toJson(params))
            .apply()
    }

    fun getPresetNames(): Collection<String> =
        presets.all.keys.sorted()

    fun loadPreset(name: String): Map<Int, Int>? {
        val json = presets.getString(name, null) ?: return null
        val type = object : TypeToken<Map<Int, Int>>() {}.type
        return gson.fromJson(json, type)
    }

    fun deletePreset(name: String) {
        presets.edit()
            .remove(name)
            .apply()
    }
}

class PresetFragment(context: Context, private var theremidi: ThereminiState) : Fragment() {
    private var db = PresetDB(context)
    private var presetsContainer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            MATCH_PARENT, WRAP_CONTENT
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val saveLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                MATCH_PARENT, WRAP_CONTENT
            ).apply { bottomMargin = 16.dp }
        }
        val nameEt = EditText(context).apply {
            hint = "Preset name"
            layoutParams = LinearLayout.LayoutParams(
                0, WRAP_CONTENT, 1f
            )
        }
        val saveBtn = Button(context).apply {
            text = "SAVE"
            layoutParams = LinearLayout.LayoutParams(
                WRAP_CONTENT, WRAP_CONTENT
            ).apply { marginStart = 8.dp }
        }
        saveLayout.addView(nameEt)
        saveLayout.addView(saveBtn)
        layout.addView(saveLayout)

        layout.addView(TextView(context).apply { text = "Load a preset:" })
        layout.addView(presetsContainer)

        saveBtn.setOnClickListener {
            val name = nameEt.text.toString().trim()
            if (name.isEmpty()) return@setOnClickListener
            if (db.getPresetNames().contains(name)) {
                AlertDialog.Builder(requireContext())
                    .setTitle("Overwrite Preset?")
                    .setMessage("A preset named '$name' already exists. Overwrite?")
                    .setPositiveButton("Yes") { _, _ -> saveAndRefresh(name) }
                    .setNegativeButton("No", null)
                    .show()
            } else saveAndRefresh(name)
        }

        loadPresets()
        return layout
    }

    private fun saveAndRefresh(name: String) {
        val params = theremidi.getAllParams().mapNotNull { (k, v) -> v?.let { k to it } }.toMap()
        db.savePreset(name, params)
        loadPresets()
    }

    private fun loadPresets() {
        presetsContainer.removeAllViews()
        db.getPresetNames().forEach { name ->
            Button(requireContext()).apply {
                text = name
                layoutParams = LinearLayout.LayoutParams(
                    MATCH_PARENT, WRAP_CONTENT
                ).apply { topMargin = 8.dp }
                setOnClickListener {
                    db.loadPreset(name)?.let { loadPreset(it) }
                }
                setOnLongClickListener {
                    AlertDialog.Builder(requireContext())
                        .setTitle("Delete Preset")
                        .setMessage("Are you sure you want to delete '$name'?")
                        .setPositiveButton("Delete") { _, _ ->
                            db.deletePreset(name)
                            loadPresets()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                    true
                }
            }.also { presetsContainer.addView(it) }
        }
    }

    private fun loadPreset(params: Map<Int, Int>) {
        params.forEach { cc, value ->
            theremidi.setParam(cc, value)
        }
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()
}
