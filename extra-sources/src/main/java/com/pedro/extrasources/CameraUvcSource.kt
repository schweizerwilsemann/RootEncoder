/*
 *
 *  * Copyright (C) 2024 pedroSG94.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  * http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package com.pedro.extrasources

import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.util.Log
import android.view.Surface
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.pedro.encoder.input.sources.OrientationConfig
import com.pedro.encoder.input.sources.OrientationForced
import com.pedro.encoder.input.sources.video.VideoSource
import com.serenegiant.usb.UVCControl


class CameraUvcSource(
  private val preferredDeviceName: String? = null,
  private var zoom: Float = 1f,
  private var exposureCompensation: Int = 0,
  private var autoFocusEnabled: Boolean = true,
) : VideoSource() {

  private var cameraHelper: CameraHelper? = null
  private var running = false
  private var surface: Surface? = null
  private var selectedDeviceName: String? = null
  private var uvcControl: UVCControl? = null

  override fun create(width: Int, height: Int, fps: Int, rotation: Int): Boolean {
    return true
  }

  override fun start(surfaceTexture: SurfaceTexture) {
    this.surfaceTexture = surfaceTexture
    surface = Surface(surfaceTexture)
    cameraHelper = CameraHelper()
    cameraHelper?.setStateCallback(stateCallback)
    cameraHelper?.registerCallback()
    running = true
  }

  override fun stop() {
    cameraHelper?.unregisterCallback()
    surface?.let { cameraHelper?.removeSurface(it) }
    surface?.release()
    surface = null
    uvcControl = null
    cameraHelper?.release()
    cameraHelper = null
    running = false
  }

  override fun release() {
  }

  override fun isRunning(): Boolean = running

  override fun getOrientationConfig() = OrientationConfig(forced = OrientationForced.LANDSCAPE)

  fun setZoom(level: Float) {
    zoom = level
    applyZoom()
  }

  fun setExposureCompensation(value: Int) {
    exposureCompensation = value
    applyExposure()
  }

  fun setAutoFocus(enabled: Boolean) {
    autoFocusEnabled = enabled
    applyAutoFocus()
  }

  private fun applyZoom() {
    val control = uvcControl ?: return
    try {
      if (control.isZoomAbsoluteEnable) {
        control.updateZoomAbsoluteLimit()
        val limits = control.updateZoomAbsoluteLimit()
        val minZoom = limits[0]
        val maxZoom = limits[1]
        val percent = ((zoom - 1f) / 3f * 100f).toInt().coerceIn(0, 100)
        val actualValue = minZoom + (maxZoom - minZoom) * percent / 100
        control.setZoomAbsolute(actualValue)
        Log.d("CameraUvcSource", "Zoom set to $actualValue (percent=$percent, zoom=$zoom)")
      } else if (control.isZoomRelativeEnable) {
        val percent = ((zoom - 1f) / 3f * 100f).toInt().coerceIn(0, 100)
        control.setZoomRelative(percent)
        Log.d("CameraUvcSource", "Zoom relative set to $percent")
      }
    } catch (e: Exception) {
      Log.e("CameraUvcSource", "Failed to apply zoom: ${e.message}")
    }
  }

  private fun applyExposure() {
    val control = uvcControl ?: return
    try {
      if (control.isAutoExposureModeEnable) {
        if (exposureCompensation == 0) {
          control.setAutoExposureMode(UVCControl.UVC_AUTO_EXPOSURE_MODE_AUTO)
        } else {
          control.setAutoExposureMode(UVCControl.UVC_AUTO_EXPOSURE_MODE_MANUAL)
          if (control.isExposureTimeAbsoluteEnable) {
            control.updateExposureTimeAbsoluteLimit()
            val limits = control.updateExposureTimeAbsoluteLimit()
            val minExp = limits[0]
            val maxExp = limits[1]
            val normalized = (exposureCompensation + 6f) / 12f
            val actualValue = minExp + ((maxExp - minExp) * normalized).toInt()
            control.setExposureTimeAbsolute(actualValue)
            Log.d("CameraUvcSource", "Exposure set to $actualValue (compensation=$exposureCompensation)")
          }
        }
      }
    } catch (e: Exception) {
      Log.e("CameraUvcSource", "Failed to apply exposure: ${e.message}")
    }
  }

  private fun applyAutoFocus() {
    val control = uvcControl ?: return
    try {
      if (control.isFocusAutoEnable) {
        control.setFocusAuto(autoFocusEnabled)
        Log.d("CameraUvcSource", "AutoFocus set to $autoFocusEnabled")
      }
    } catch (e: Exception) {
      Log.e("CameraUvcSource", "Failed to apply autofocus: ${e.message}")
    }
  }

  private fun applyAllSettings() {
    applyAutoFocus()
    applyZoom()
    applyExposure()
  }

  private val stateCallback: ICameraHelper.StateCallback = object : ICameraHelper.StateCallback {
    override fun onAttach(device: UsbDevice) {
      if (
        preferredDeviceName == null ||
        device.deviceName == preferredDeviceName ||
        selectedDeviceName == null
      ) {
        selectedDeviceName = device.deviceName
        cameraHelper?.selectDevice(device)
      }
    }

    override fun onDeviceOpen(device: UsbDevice, isFirstOpen: Boolean) {
      cameraHelper?.openCamera()
    }

    override fun onCameraOpen(device: UsbDevice) {
      uvcControl = cameraHelper?.getUVCControl()
      cameraHelper?.startPreview()
      surface?.let { cameraHelper?.addSurface(it, false) }
      applyAllSettings()
    }

    override fun onCameraClose(device: UsbDevice) {
      uvcControl = null
    }

    override fun onDeviceClose(device: UsbDevice) {}

    override fun onDetach(device: UsbDevice) {}

    override fun onCancel(device: UsbDevice) {}
  }
}
