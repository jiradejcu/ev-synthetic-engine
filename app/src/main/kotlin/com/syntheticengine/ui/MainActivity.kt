package com.syntheticengine.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.Toast
import com.syntheticengine.EngineController
import com.syntheticengine.R
import com.syntheticengine.engine.EngineProfile

/**
 * Phone UI: the same tachometer as the car screen, plus engine selection, volume and a
 * demo mode (virtual car driven by an on-screen pedal) for trying it out parked.
 */
class MainActivity : Activity() {

    private lateinit var controller: EngineController
    private lateinit var startStop: Button
    private lateinit var profileSpinner: Spinner
    private lateinit var demoSwitch: Switch
    private val onEngineChanged: () -> Unit = { runOnUiThread { syncUi() } }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        controller = EngineController.get(this)

        startStop = findViewById(R.id.startStop)
        startStop.setOnClickListener {
            if (!controller.running) requestPermissionsIfNeeded()
            controller.toggle()
        }

        profileSpinner = findViewById(R.id.profile)
        profileSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, EngineProfile.ALL.map { it.displayName },
        )
        profileSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = EngineProfile.ALL[position]
                if (selected !== controller.profile) controller.profile = selected
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        findViewById<SeekBar>(R.id.volume).apply {
            progress = (controller.volume * 100).toInt()
            setOnSeekBarChangeListener(seekListener { controller.volume = it / 100.0 })
        }

        val demoControls = findViewById<View>(R.id.demoControls)
        demoSwitch = findViewById(R.id.demo)
        demoSwitch.setOnCheckedChangeListener { _, checked ->
            controller.demoMode = checked
            demoControls.visibility = if (checked) View.VISIBLE else View.GONE
        }
        findViewById<SeekBar>(R.id.pedal).setOnSeekBarChangeListener(seekListener { controller.demoPedal = it / 100.0 })
        findViewById<Button>(R.id.brake).setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> controller.demoBrake = 1.0
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> controller.demoBrake = 0.0
            }
            false
        }
    }

    override fun onStart() {
        super.onStart()
        controller.addListener(onEngineChanged)
        syncUi()
    }

    override fun onStop() {
        controller.removeListener(onEngineChanged)
        super.onStop()
    }

    private fun syncUi() {
        startStop.setText(if (controller.running) R.string.stop else R.string.start)
        val index = EngineProfile.ALL.indexOf(controller.profile)
        if (profileSpinner.selectedItemPosition != index) profileSpinner.setSelection(index)
        if (demoSwitch.isChecked != controller.demoMode) demoSwitch.isChecked = controller.demoMode
    }

    private fun requestPermissionsIfNeeded() {
        val wanted = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), REQUEST_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return
        val i = permissions.indexOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (i >= 0 && grantResults[i] == PackageManager.PERMISSION_GRANTED) {
            controller.onPermissionsChanged()
        } else if (i >= 0) {
            Toast.makeText(this, R.string.need_location, Toast.LENGTH_LONG).show()
        }
    }

    private fun seekListener(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) onChange(progress)
        }
        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
    }

    private companion object {
        const val REQUEST_PERMISSIONS = 1
    }
}
