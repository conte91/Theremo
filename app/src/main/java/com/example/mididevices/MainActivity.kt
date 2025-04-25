// vim: set filetype=kotlin ts=4 sw=4 et:
package com.example.mididevices

import android.view.LayoutInflater
import android.media.midi.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

class MainActivity : AppCompatActivity() {
    private var midiPort: MidiInputPort? = null
    private var midiReady = false
    private var midiDevice: MidiDevice? = null
    private var devicePollHandler: Handler? = null
    private var devicePollRunnable: Runnable? = null

    data class MidiControlPage(val title: String, val fragmentClass: Class<out Fragment>)

    companion object {
        private const val MIDI_MAX_VALUE = 127
        private val TAG = MainActivity::class.java.simpleName ?: "MainActivity"
        lateinit var instance: MainActivity

        fun labeledSliderView(
            context: MainActivity,
            cc: Int,
            min: Int,
            max: Int,
            label: String
        ): View {
            val layout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val labelView = TextView(context).apply { text = "$label: ??? (min: $min, max: $max)" }
            val seek = SeekBar(context).apply {
                this.max = max
                progress = 0
                isEnabled = context.midiReady
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                        context.sendCC(cc, p)
                        labelView.text = "$label: $p (min: $min, max: $max)"
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }
            layout.addView(labelView)
            layout.addView(seek)
            return layout
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        showDeviceSelection()
    }

    private fun showDeviceSelection() {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val midiManager = getSystemService(MIDI_SERVICE) as MidiManager
        val deviceContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        layout.addView(TextView(this).apply { text = "Select a MIDI device:" })
        layout.addView(deviceContainer)

        fun updateDeviceList() {
            deviceContainer.removeAllViews()
            val devices = midiManager.devices
            if (devices.isEmpty()) {
                deviceContainer.addView(TextView(this).apply { text = "No MIDI devices found" })
            } else {
                devices.forEachIndexed { index, device ->
                    deviceContainer.addView(Button(this).apply {
                        text = "Device $index"
                        setOnClickListener {
                            midiManager.openDevice(device, { dev ->
                                if (dev == null) {
                                    toast("Failed to open device")
                                    return@openDevice
                                }
                                midiDevice = dev
                                midiPort = dev.openInputPort(0)
                                midiReady = midiPort != null
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
            MidiControlPage("Volume", VolumeFragment::class.java),
            MidiControlPage("Scale", ScaleFragment::class.java),
            MidiControlPage("Transpose", TransposeFragment::class.java),
            MidiControlPage("Waveform", WaveformFragment::class.java),
            MidiControlPage("Filter", FilterFragment::class.java),
            MidiControlPage("Effect Mix", EffectMixFragment::class.java),
            MidiControlPage("Modulation 1", Mod1Fragment::class.java),
            MidiControlPage("Modulation 2", Mod2Fragment::class.java),
            MidiControlPage("Mod Targeting", ModTargetFragment::class.java),
            MidiControlPage("Scan/Wavetable", ScanFragment::class.java),
            MidiControlPage("Delay", DelayFragment::class.java),
            MidiControlPage("Pitch Correction", PitchCorrectionFragment::class.java),
            MidiControlPage("Preset", PresetFragment::class.java),
            MidiControlPage("Back to Setup", DeviceSetupFragment::class.java)
        )
        viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = pages.size
            override fun createFragment(position: Int): Fragment =
                pages[position].fragmentClass.newInstance()
        }
        TabLayoutMediator(tabLayout, viewPager) { tab, pos ->
            tab.text = pages[pos].title
        }.attach()
    }

    class VolumeFragment : Fragment() {
        override fun onCreateView(
            inflater: LayoutInflater, c: ViewGroup?, s: Bundle?
        ): View = ScrollView(requireContext()).apply {
            addView(
                labeledSliderView(
                    requireActivity() as MainActivity,
                    7, 0, MIDI_MAX_VALUE,
                    "Master Volume"
                )
            )
        }
    }

    class ScaleFragment : Fragment() {
        override fun onCreateView(
            i: LayoutInflater, c: ViewGroup?, s: Bundle?
        ): View {
            val layout = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
            layout.addView(TextView(requireContext()).apply { text = "Scale (CC 85)" })
            listOf(
                "Chromatic","Ionian","Dorian","Phrygian","Lydian","Mixolydian","Aeolian",
                "Locrian","Maj Blues","Min Blues","Dim","Maj Penta","Min Penta","Spanish",
                "Gypsy","Arabian","Egyptian","Ryukyu","Wholetone","Maj 3rd","Min 3rd","5th"
            ).forEachIndexed { idx, name ->
                layout.addView(Button(requireContext()).apply {
                    text = "$idx: $name"
                    isEnabled = MainActivity.instance.midiReady
                    setOnClickListener { MainActivity.instance.sendCC(85, idx) }
                })
            }
            return ScrollView(requireContext()).apply { addView(layout) }
        }
    }

    class TransposeFragment : Fragment() {
        override fun onCreateView(
            i: LayoutInflater, c: ViewGroup?, s: Bundle?
        ): View = ScrollView(requireContext()).apply {
            addView(
                labeledSliderView(
                    requireActivity() as MainActivity,
                    102, 0, MIDI_MAX_VALUE,
                    "Transpose (CC 102)"
                )
            )
        }
    }

    class WaveformFragment : Fragment() {
        override fun onCreateView(
            i: LayoutInflater, c: ViewGroup?, s: Bundle?
        ): View {
            val layout = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
            layout.addView(TextView(requireContext()).apply { text = "Waveform (CC 90)" })
            listOf("Sine","Triangle","Super Saw","Animoog 1","Animoog 2","Animoog 3","Etherwave")
                .forEachIndexed { idx, name ->
                    layout.addView(Button(requireContext()).apply {
                        text = "$idx: $name"
                        isEnabled = MainActivity.instance.midiReady
                        setOnClickListener { MainActivity.instance.sendCC(90, idx) }
                    })
                }
            return ScrollView(requireContext()).apply { addView(layout) }
        }
    }

    class FilterFragment : Fragment() {
        override fun onCreateView(
            i: LayoutInflater, c: ViewGroup?, s: Bundle?
        ): View {
            val a = requireActivity() as MainActivity
            val layout = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
            layout.addView(labeledSliderView(a, 74, 0, MIDI_MAX_VALUE, "Filter Cutoff (CC 74)"))
            layout.addView(labeledSliderView(a, 71, 0, MIDI_MAX_VALUE, "Filter Resonance (CC 71)"))
            layout.addView(TextView(requireContext()).apply { text = "Filter Type (CC 80)" })
            listOf("Bypass", "Lowpass", "Bandpass", "Highpass", "Notch", "Animoog 3", "Etherwave")
                .forEachIndexed { idx, name ->
                    layout.addView(Button(requireContext()).apply {
                        text = "$idx: $name"
                        isEnabled = MainActivity.instance.midiReady
                        setOnClickListener { MainActivity.instance.sendCC(80, idx) }
                    })
                }
            return ScrollView(requireContext()).apply { addView(layout) }
        }
    }

    class EffectMixFragment : Fragment() {
        override fun onCreateView(
            i: LayoutInflater, c: ViewGroup?, s: Bundle?
        ): View = ScrollView(requireContext()).apply {
            addView(labeledSliderView(requireActivity() as MainActivity, 91, 0, MIDI_MAX_VALUE, "Effect Mix (CC 91)"))
        }
    }

    class Mod1Fragment : Fragment() {
        override fun onCreateView(
            i: LayoutInflater, c: ViewGroup?, s: Bundle?
        ): View {
            val a = requireActivity() as MainActivity
            val layout = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
            layout.addView(labeledSliderView(a, 22, 0, MIDI_MAX_VALUE, "Pitch Mod Scan Freq (CC 22)"))
            layout.addView(labeledSliderView(a, 24, 0, MIDI_MAX_VALUE, "Pitch Mod Amount (CC 24)"))
            layout.addView(labeledSliderView(a, 30, 0, MIDI_MAX_VALUE, "Pitch Mod Resonance (CC 30)"))
            return ScrollView(requireContext()).apply { addView(layout) }
        }
    }

    class Mod2Fragment : Fragment() {
        override fun onCreateView(
            i: LayoutInflater, c: ViewGroup?, s: Bundle?
        ): View {
            val a = requireActivity() as MainActivity
            val layout = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
            layout.addView(labeledSliderView(a, 23, 0, MIDI_MAX_VALUE, "Vol Mod Scan Freq (CC 23)"))
            layout.addView(labeledSliderView(a, 25, 0, MIDI_MAX_VALUE, "Vol Mod Amount (CC 25)"))
            layout.addView(labeledSliderView(a, 26, 0, MIDI_MAX_VALUE, "Vol Mod Volume (CC 26)"))
            return ScrollView(requireContext()).apply { addView(layout) }
        }
    }

    class ModTargetFragment : Fragment() {
        override fun onCreateView(
            i: LayoutInflater, c: ViewGroup?, s: Bundle?
        ): View {
            val a = requireActivity() as MainActivity
            val layout = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
            layout.addView(labeledSliderView(a, 27, 0, MIDI_MAX_VALUE, "Vol Mod Cutoff (CC 27)"))
            layout.addView(labeledSliderView(a, 28, 0, MIDI_MAX_VALUE, "Vol Mod Resonance (CC 28)"))
            layout.addView(labeledSliderView(a, 29, 0, MIDI_MAX_VALUE, "Filter Pitch Tracking (CC 29)"))
            return ScrollView(requireContext()).apply { addView(layout) }
        }
    }

    class ScanFragment : Fragment() {
        override fun onCreateView(
            i: LayoutInflater, c: ViewGroup?, s: Bundle?
        ): View {
            val a = requireActivity() as MainActivity
            val layout = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
            layout.addView(labeledSliderView(a, 9, 0, MIDI_MAX_VALUE, "Wavetable Scan Rate (CC 9)"))
            layout.addView(labeledSliderView(a, 20, 0, MIDI_MAX_VALUE, "Scan Amount (CC 20)"))
            layout.addView(labeledSliderView(a, 21, 0, MIDI_MAX_VALUE, "Scan Position (CC 21)"))
            return ScrollView(requireContext()).apply { addView(layout) }
        }
    }

    class DelayFragment : Fragment() {
        override fun onCreateView(
            i: LayoutInflater, c: ViewGroup?, s: Bundle?
        ): View {
            val a = requireActivity() as MainActivity
            val layout = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
            layout.addView(labeledSliderView(a, 12, 0, MIDI_MAX_VALUE, "Delay Time (CC 12)"))
            layout.addView(labeledSliderView(a, 14, 0, MIDI_MAX_VALUE, "Delay Feedback (CC 14)"))
            return ScrollView(requireContext()).apply { addView(layout) }
        }
    }

    class PitchCorrectionFragment : Fragment() {
        override fun onCreateView(
            i: LayoutInflater, c: ViewGroup?, s: Bundle?
        ): View = ScrollView(requireContext()).apply {
            val a = requireActivity() as MainActivity
            val layout = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
            addView(labeledSliderView(requireActivity() as MainActivity, 84, 0, MIDI_MAX_VALUE, "Pitch Correction (CC 84)"))
            layout.addView(TextView(requireContext()).apply { text = "Root Note (CC 86)" })
            listOf("C","C#","D","D#","E","F","F#","G","G#","A","A#","B")
                .forEachIndexed { idx, name ->
                    layout.addView(Button(requireContext()).apply {
                        text = name
                        isEnabled = a.midiReady
                        setOnClickListener { a.sendCC(86, idx) }
                    })
                }
            layout.addView(labeledSliderView(a, 87, 0, MIDI_MAX_VALUE, "Low Note (CC 87)"))
            layout.addView(labeledSliderView(a, 88, 0, MIDI_MAX_VALUE, "High Note (CC 88)"))
            return ScrollView(requireContext()).apply { addView(layout) }
        }
    }

    class FilterTypeFragment : Fragment() {
        override fun onCreateView(
            i: LayoutInflater, c: ViewGroup?, s: Bundle?
        ): View {            val layout = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
            return ScrollView(requireContext()).apply { addView(layout) }
        }
    }

    class PresetFragment : Fragment() {
        override fun onCreateView(
            i: LayoutInflater, c: ViewGroup?, s: Bundle?
        ): View {
            val a = requireActivity() as MainActivity
            val layout = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
            layout.addView(labeledSliderView(a, 103, 0, MIDI_MAX_VALUE, "Preset Volume (CC 103)"))
            layout.addView(Button(requireContext()).apply {
                text = "Save Preset (CC 119)"
                isEnabled = MainActivity.instance.midiReady
                setOnClickListener { MainActivity.instance.sendCC(119, 0) }
            })
            return ScrollView(requireContext()).apply { addView(layout) }
        }
    }

    class DeviceSetupFragment : Fragment() {
        override fun onCreateView(
            i: LayoutInflater, c: ViewGroup?, s: Bundle?
        ): View = ScrollView(requireContext()).apply {
            addView(LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                addView(Button(requireContext()).apply {
                    text = "Choose MIDI Device"
                    setOnClickListener { (activity as? MainActivity)?.showDeviceSelection() }
                })
            })
        }
    }

    fun sendCC(cc: Int, value: Int) {
        val msg = byteArrayOf(0xB0.toByte(), cc.toByte(), value.toByte())
        try {
            midiPort?.send(msg, 0, msg.size) ?: throw Exception("MIDI port not initialized")
        } catch (e: Exception) {
            toast("Failed to send MIDI CC $cc")
            Log.e(TAG, "Error sending MIDI CC $cc: ${e.message}")
        }
    }

    private fun toast(msg: String) = runOnUiThread {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        stopDevicePolling()
        midiPort?.close()
        midiDevice?.close()
        super.onDestroy()
    }
}

