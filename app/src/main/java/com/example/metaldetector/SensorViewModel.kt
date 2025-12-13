package com.example.metaldetector

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import kotlin.math.sqrt

class SensorViewModel: ViewModel() {
    private val _magnitude = MutableLiveData<Double>(0.0)
    val magnitude: LiveData<Double> = _magnitude

    // baseline offset for calibration
    private val _baseline = MutableLiveData<Double>(0.0)
    val baseline: LiveData<Double> = _baseline

    fun updateFromSensor(x: Float, y: Float, z: Float) {
        // compute magnitude
        // M = sqrt(x^2 + y^2 + z^2)
        val mx = x.toDouble()
        val my = y.toDouble()
        val mz = z.toDouble()
        val m = sqrt(mx * mx + my * my + mz * mz)
        _magnitude.postValue(m)
    }

    fun setBaseline(value: Double) {
        _baseline.value = value
    }
}