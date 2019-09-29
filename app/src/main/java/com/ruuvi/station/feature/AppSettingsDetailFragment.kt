package com.ruuvi.station.feature

import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import androidx.fragment.app.Fragment
import com.koushikdutta.ion.Ion
import com.ruuvi.station.R
import com.ruuvi.station.feature.main.MainActivity
import com.ruuvi.station.model.ScanEvent
import com.ruuvi.station.util.BackgroundScanModes
import com.ruuvi.station.util.DeviceIdentifier
import com.ruuvi.station.util.Preferences
import kotlinx.android.synthetic.main.fragment_app_settings_detail.*

private const val ARG_SETTING_RES = "arg_setting_res"

class AppSettingsDetailFragment : Fragment() {
    private var res: Int? = null
    lateinit var prefs: Preferences
    private var gatewayUrl = ""
    private var deviceId = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Preferences(this.context!!)
        arguments?.let {
            res = it.getInt(ARG_SETTING_RES)
        }
    }

    override fun onPause() {
        super.onPause()
        prefs.gatewayUrl = gatewayUrl
        prefs.deviceId = deviceId
        //MainActivity.setBackgroundScanning(activity)
    }

    override fun onResume() {
        super.onResume()
        if (gatewayUrl.isEmpty()) gatewayUrl = prefs.gatewayUrl
        if (deviceId.isEmpty()) deviceId = prefs.deviceId
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_app_settings_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (res == R.string.pref_bgscan) {
            //scan_layout_container.visibility = View.VISIBLE
            //(activity as AppSettingsActivity).setScanSwitchLayout(view)

            radio_setting_title.text = getString(res!!)
            radio_layout.visibility = View.VISIBLE
            val list = listOf(
                    getString(R.string.no_background_scanning),
                    getString(R.string.continuous_background_scanning),
                    getString(R.string.lazy_background_scanning)
            )
            var current = prefs.backgroundScanMode
            setBackgroundScanText(current)
            list.forEachIndexed { index, option ->
                val rb = RadioButton(activity)
                rb.id = index
                rb.text = option
                rb.isChecked = (index == current.value)
                radio_group.addView(rb)
            }

            radio_group.setOnCheckedChangeListener { radioGroup, i ->
                current = BackgroundScanModes.fromInt(i)!!
                prefs.backgroundScanMode = current
                setBackgroundScanText(current)
            }
        } else if (res == R.string.background_scan_interval) {
            duration_picker.visibility = View.VISIBLE
            val current = prefs.backgroundScanInterval

            var min = current / 60
            var sec = current - min * 60

            duration_minute.maxValue = 59
            duration_second.maxValue = 59

            if (min == 0) duration_second.minValue = 10

            var minuteMinValue = 0
            if (prefs.backgroundScanMode == BackgroundScanModes.BACKGROUND) {
                minuteMinValue = 15
                if (min <= minuteMinValue) {
                    min = 15
                    sec = 0
                }
            }
            duration_minute.minValue = minuteMinValue

            duration_minute.value = min
            duration_second.value = sec

            duration_minute.setOnValueChangedListener { numberPicker, old, new ->
                if (new == 0) {
                    duration_second.minValue = 10
                    if (duration_second.value < 10) duration_second.value = 10
                } else {
                    duration_second.minValue = 0
                }
                prefs.backgroundScanInterval = new * 60 + duration_second.value
            }

            duration_second.setOnValueChangedListener { numberPicker, old, new ->
                prefs.backgroundScanInterval = duration_minute.value * 60 + new
            }

            settings_info.text = getString(R.string.settings_background_scan_interval_details)

            ignore_battery_layout.setOnClickListener {
                MainActivity.requestIgnoreBatteryOptimization(context)
            }
        } else if (res == R.string.temperature_unit) {
            radio_layout.visibility = View.VISIBLE
            radio_setting_title.text = getString(res!!)
            val current = prefs.temperatureUnit
            val options = resources.getStringArray(R.array.list_preference_temperature_unit_titles)
            val values = resources.getStringArray(R.array.list_preference_temperature_unit_values)
            options.forEachIndexed { index, option ->
                val rb = RadioButton(activity)
                rb.id = index
                rb.text = option
                rb.isChecked = (values[index] == current)
                radio_group.addView(rb)
            }

            radio_group.setOnCheckedChangeListener { radioGroup, i ->
                prefs.temperatureUnit = values[i]
            }
            settings_info.text = getString(R.string.settings_temperature_unit_details)
        } else if (res == R.string.gateway_url) {
            input_layout.visibility = View.VISIBLE
            input_setting_title.text = getString(res!!)
            input_setting.setText(prefs.gatewayUrl)
            input_setting.hint = "https://your.gateway/..."
            input_setting.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {
                }

                override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {
                }

                override fun afterTextChanged(p0: Editable?) {
                    gatewayUrl = p0.toString()
                }
            })
            //settings_info.text = getString(R.string.settings_gateway_details)
            settings_info.movementMethod = LinkMovementMethod.getInstance()
            device_identifier_layout.visibility = View.VISIBLE
            device_identifier_input.setText(prefs.deviceId)
            device_identifier_input.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {
                }

                override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {
                }

                override fun afterTextChanged(p0: Editable?) {
                    deviceId = p0.toString()
                }
            })
            wakelock_layout_container.visibility = View.VISIBLE
            wakelock_switch.isChecked = prefs.serviceWakelock
            wakelock_switch.setOnCheckedChangeListener { switch, checked ->
                prefs.serviceWakelock = checked
            }
            gateway_tester_layout.visibility = View.VISIBLE
            gateway_test_button.setOnClickListener { _ ->
                gateway_test_result.text = "Testing.."
                gateway_test_result.setTextColor(Color.DKGRAY)
                val scanEvent = ScanEvent(context)
                scanEvent.deviceId = device_identifier_input.text.toString()
                Ion.with(context)
                        .load(input_setting.text.toString())
                        .setJsonPojoBody(scanEvent)
                        .asJsonObject()
                        .withResponse()
                        .setCallback { e, result ->
                            when {
                                e != null -> {
                                    gateway_test_result.setTextColor(Color.RED)
                                    gateway_test_result.text = "Nope, did not work. Is the URL correct?"
                                }
                                result.headers.code() != 200 -> {
                                    gateway_test_result.setTextColor(Color.RED)
                                    gateway_test_result.text = "Nope, did not work. Response code: " + result.headers.code()
                                }
                                else -> {
                                    gateway_test_result.setTextColor(Color.GREEN)
                                    gateway_test_result.text = "Gateway works! Response code: " + result.headers.code()
                                }
                            }
                        }
            }
        } else if (res == R.string.device_identifier) {
            input_layout.visibility = View.VISIBLE
            input_setting_title.text = getString(res!!)
            input_setting.setText(prefs.deviceId)
            input_setting.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {
                }

                override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {
                    deviceId = p0.toString()
                    if (p0.toString().isEmpty()) {
                        deviceId = DeviceIdentifier.generateId()
                    }
                }

                override fun afterTextChanged(p0: Editable?) {
                }
            })
            settings_info.text = getString(R.string.settings_device_identifier_details)
        }
    }

    fun setBackgroundScanText(mode: BackgroundScanModes) {
        when (mode) {
            BackgroundScanModes.BACKGROUND -> settings_info.text = getString(R.string.settings_background_scan_details_lazy)
            BackgroundScanModes.FOREGROUND -> settings_info.text = getString(R.string.settings_background_scan_details_continuous)
            else -> settings_info.text = getString(R.string.settings_background_scan_details)
        }
    }

    companion object {
        @JvmStatic
        fun newInstance(res: Int) =
                AppSettingsDetailFragment().apply {
                    arguments = Bundle().apply {
                        putInt(ARG_SETTING_RES, res)
                    }
                }
    }
}
