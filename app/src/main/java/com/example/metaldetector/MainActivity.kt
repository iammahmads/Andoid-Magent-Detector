package com.example.metaldetector

import android.content.Context
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Observer
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet
import kotlin.math.round

class MainActivity : ComponentActivity(), SensorEventListener {
    private lateinit var sensorManager: SensorManager
    private var magnetometer: Sensor? = null
    private var isScanning = true

    private val viewModel: SensorViewModel by viewModels()

    private lateinit var tvMagnitude: TextView
    private lateinit var lineChart: LineChart // Change from ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var seekSensitivity: SeekBar
    private lateinit var tvThresholdValue: TextView
    private lateinit var btnCalibrate: Button
    private lateinit var btnToggleSensor: Button
    private lateinit var btnThresholdInfo: ImageButton // New
    private lateinit var rootView: View

    private var threshold = 80.0       // default threshold in microtesla (µT)
    private var baseline = 0.0         // calibration baseline
    private var lastVibrationTime = 0L
    private val vibrationCooldownMs = 500L

    private var vibrator: Vibrator? = null

    // For the Line Chart
    private var chartEntryIndex = 0f
    private val maxVisiblePoints = 60 // Number of data points to show on screen

    // Colors
    private val colorNeonGreen = Color.parseColor("#00FF41")
    private val colorNeonRed = Color.parseColor("#FF0041")
    private val colorCyan = Color.parseColor("#00FFFF")
    private val colorDisabled = Color.parseColor("#555555")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        tvMagnitude = findViewById(R.id.tvMagnitude)
        lineChart = findViewById(R.id.lineChart) // LineChart initialization
        tvStatus = findViewById(R.id.tvStatus)
        seekSensitivity = findViewById(R.id.seekSensitivity)
        tvThresholdValue = findViewById(R.id.tvThresholdValue)
        btnCalibrate = findViewById(R.id.btnCalibrate)
        btnToggleSensor = findViewById(R.id.btnToggleSensor)
        btnThresholdInfo = findViewById(R.id.btnThresholdInfo) // New
        rootView = findViewById(R.id.root)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        // Initialize Line Chart appearance
        setupLineChart()


        // Vibrator manager for SDK >= 31
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }


        // Observe ViewModel LiveData
        viewModel.magnitude.observe(this, Observer { m ->
            // apply baseline offset
            val adjusted = (m - baseline).coerceAtLeast(0.0)
            val rounded = round(adjusted * 10.0) / 10.0
            tvMagnitude.text = "MAGNITUDE: $rounded \u00B5T"

            // Update Line Chart
            addEntryToChart(adjusted.toFloat())

            // Check threshold
            if (adjusted > threshold) {
                // spike detected
                tvStatus.text = "[STATUS: WARNING! MAGNET DETECTED]"
                tvStatus.setTextColor(colorNeonRed)
                // Hacking theme background flash - less aggressive than a full white flash
                rootView.setBackgroundColor(Color.parseColor("#150000"))

                val now = System.currentTimeMillis()
                if (now - lastVibrationTime > vibrationCooldownMs) {
                    doVibrate()
                    lastVibrationTime = now
                }
            } else {
                tvStatus.text = "[STATUS: IDLE]"
                tvStatus.setTextColor(colorNeonGreen)
                rootView.setBackgroundColor(Color.BLACK)
            }
        })

        // Seekbar sensitivity changes threshold
        seekSensitivity.progress = threshold.toInt()
        tvThresholdValue.text = "${threshold.toInt()} \u00B5T"
        seekSensitivity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                threshold = progress.toDouble().coerceAtLeast(1.0)
                tvThresholdValue.text = "${threshold.toInt()} \u00B5T"
                updateChartThresholdLine() // Update the chart's limit line
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnCalibrate.setOnClickListener {
            // Set current reading as baseline
            val current = viewModel.magnitude.value ?: 0.0
            baseline = current
            viewModel.setBaseline(baseline)
            Toast.makeText(this, "BASELINE SET: ${round(baseline*10)/10.0} \u00B5T", Toast.LENGTH_SHORT).show()
        }
        btnToggleSensor.setOnClickListener {
            toggleScanning()
        }

        // 'Eye' button for threshold info
        btnThresholdInfo.setOnClickListener {
            showThresholdInfoDialog()
        }

        // If sensor missing: show message
        if (magnetometer == null) {
            Toast.makeText(this, "Magnetometer not available on this device", Toast.LENGTH_LONG).show()
            tvStatus.text = "[ERROR: SENSOR NOT FOUND]"
            tvStatus.setTextColor(colorNeonRed)
        }

    }

    private fun toggleScanning() {
        if (isScanning) {
            // Currently scanning, so stop it
            sensorManager.unregisterListener(this)
            isScanning = false
            btnToggleSensor.text = "> RESUME SCAN"
            btnToggleSensor.setBackgroundColor(colorNeonGreen)

            // Update UI to reflect stopped state
            tvStatus.text = "[STATUS: SCANNING PAUSED]"
            tvStatus.setTextColor(colorDisabled)
            tvMagnitude.text = "MAGNITUDE: -- \u00B5T"

            // Optionally clear the chart or freeze it
            // lineChart.clear() // Uncomment if you want to clear the graph when paused

        } else {
            // Currently stopped, so resume scanning
            magnetometer?.also { sensor ->
                sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
            }
            isScanning = true
            btnToggleSensor.text = "> STOP SCAN"
            btnToggleSensor.setBackgroundColor(colorCyan) // Changed tint to Cyan for active state
        }

        // Disable calibration/seekbar when paused
        btnCalibrate.isEnabled = isScanning
        seekSensitivity.isEnabled = isScanning
    }

    // --- Line Chart Methods ---

    private fun setupLineChart() {
        // General Chart settings
        lineChart.description.isEnabled = false
        lineChart.setTouchEnabled(true)
        lineChart.isDragEnabled = true
        lineChart.setScaleEnabled(true)
        lineChart.setPinchZoom(true)
        lineChart.setDrawGridBackground(false)
        lineChart.setBackgroundColor(Color.BLACK)

        // Data setup
        val data = LineData()
        lineChart.data = data

        // Legend setup (top right box)
        val l: Legend = lineChart.legend
        l.isEnabled = false // Disable legend box

        // X-Axis (Bottom) - Time/Index
        val xl: XAxis = lineChart.xAxis
        xl.textColor = colorCyan
        xl.setDrawGridLines(true)
        xl.setAvoidFirstLastClipping(true)
        xl.isEnabled = true
        xl.position = XAxis.XAxisPosition.BOTTOM
        xl.gridColor = Color.parseColor("#3300FF41")
        xl.textSize = 10f

        // Left Y-Axis (Values)
        val leftAxis = lineChart.axisLeft
        leftAxis.textColor = colorCyan
        leftAxis.axisMinimum = 0f // Start from 0
        leftAxis.gridColor = Color.parseColor("#3300FF41")
        leftAxis.textSize = 10f
        updateChartThresholdLine() // Initial threshold limit line

        // Right Y-Axis (Disabled)
        lineChart.axisRight.isEnabled = false
    }

    private fun getOrCreateDataSet(): LineDataSet {
        var set = lineChart.data?.getDataSetByIndex(0)
        if (set == null) {
            // Create the dataset if it doesn't exist
            set = LineDataSet(null, "Magnetic Field (µT)")
            set.axisDependency = com.github.mikephil.charting.components.YAxis.AxisDependency.LEFT
            set.color = colorNeonGreen
            set.setCircleColor(colorNeonGreen)
            set.lineWidth = 2f
            set.setDrawCircles(false)
            set.setDrawValues(false) // Hide value text on points

            val data = lineChart.data
            data.addDataSet(set)
        }
        return set as LineDataSet
    }

    private fun addEntryToChart(magnitude: Float) {
        val data = lineChart.data ?: return
        val set = getOrCreateDataSet()

        val entry = Entry(chartEntryIndex, magnitude)
        data.addEntry(entry, 0)

        // Increase index for next point
        chartEntryIndex++

        // Let the chart know it's data has changed
        data.notifyDataChanged()

        // Limit the number of visible points and move the viewport
        lineChart.notifyDataSetChanged()

        // set the number of visible entries
        lineChart.setVisibleXRangeMaximum(maxVisiblePoints.toFloat())

        // move to the latest entry
        lineChart.moveViewToX(data.entryCount.toFloat() - maxVisiblePoints)
    }

    private fun updateChartThresholdLine() {
        val leftAxis = lineChart.axisLeft
        leftAxis.removeAllLimitLines() // Clear old line

        val limitLine = com.github.mikephil.charting.components.LimitLine(threshold.toFloat(), "THRESHOLD")
        limitLine.lineWidth = 1f
        limitLine.enableDashedLine(10f, 10f, 0f)
        limitLine.lineColor = colorNeonRed
        limitLine.textColor = colorNeonRed
        limitLine.textSize = 10f
        limitLine.labelPosition = com.github.mikephil.charting.components.LimitLine.LimitLabelPosition.RIGHT_TOP

        leftAxis.addLimitLine(limitLine)
        lineChart.invalidate() // Redraw chart
    }

    // --- Threshold Info Dialog ---
    private fun showThresholdInfoDialog() {
        val builder = AlertDialog.Builder(this, R.style.AlertDialogCustom) // Use a custom style for dark theme
        builder.setTitle("What is the Threshold?")
            .setMessage(
                "The **Threshold** is the minimum magnetic field magnitude that must be exceeded to trigger a 'Metal Detected!' warning and vibration.\n\n" +
                        "**How to use:**\n" +
                        "1. Tap **CALIBRATE** in an empty area to set the baseline.\n" +
                        "2. Drag the slider to set the sensitivity. A **lower** threshold makes the app **more sensitive** to small magnetic changes.\n" +
                        "3. The red line on the graph shows the current threshold value."
            )
            .setPositiveButton("CLOSE") { dialog, _ ->
                dialog.dismiss()
            }
            .create()
            .show()
    }


    // --- Lifecycle and Sensor methods remain largely the same ---

    override fun onResume() {
        super.onResume()
        if (isScanning) {
            // If the app was running and not stopped by the user, ensure sensor is registered
            magnetometer?.also { sensor ->
                sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
            }
        } else {
            // If the user stopped it, ensure the UI is in the 'stopped' state
            toggleScanning() // Call to set the stopped state UI
            toggleScanning() // Call it twice to flip it to the correct initial state on resume
        }
        lineChart.invalidate()
    }
    override fun onPause() {
        super.onPause()
        // Always unregister when app loses focus, regardless of the user's scan state
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        // Only process sensor data if isScanning is true
        if (isScanning && event?.sensor?.type == Sensor.TYPE_MAGNETIC_FIELD) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            viewModel.updateFromSensor(x, y, z)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { /* ignore */ }

    private fun doVibrate() {
        // ... (Vibration code remains the same)
        try {
            if (vibrator != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(120)
                }
            }
        } catch (e: Exception) {
            // ignore vibration exceptions
        }
    }
}