/*
 * Copyright (C) 2024 pedroSG94.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.pedro.rtmp.rtmp

import android.util.Base64
import android.util.Log
import com.pedro.common.AudioCodec
import com.pedro.common.ConnectChecker
import com.pedro.common.VideoCodec
import com.pedro.common.base.BaseSender
import com.pedro.common.frame.MediaFrame
import com.pedro.common.onMainThread
import com.pedro.common.validMessage
import com.pedro.rtmp.flv.BasePacket
import com.pedro.rtmp.flv.FlvPacket
import com.pedro.rtmp.flv.FlvType
import com.pedro.rtmp.flv.audio.packet.AacPacket
import com.pedro.rtmp.flv.audio.packet.G711Packet
import com.pedro.rtmp.flv.video.packet.Av1Packet
import com.pedro.rtmp.flv.video.packet.H264Packet
import com.pedro.rtmp.flv.video.packet.H265Packet
import com.pedro.rtmp.utils.socket.RtmpSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.nio.ByteBuffer

/**
 * Created by pedro on 8/04/21.
 */
class RtmpSender(
  connectChecker: ConnectChecker,
  private val commandsManager: CommandsManager,
) : BaseSender(connectChecker, "RtmpSender") {

  private var audioPacket: BasePacket = AacPacket()
  private var videoPacket: BasePacket = H264Packet()
  var socket: RtmpSocket? = null

  override fun setVideoInfo(sps: ByteBuffer, pps: ByteBuffer?, vps: ByteBuffer?) {
    fun printBytes(data: ByteBuffer, tag: String) {
      val temp = data.duplicate()
      val byteArray = ByteArray(temp.remaining())
      temp.get(byteArray)
      fun extractSpsRbsp(bytes: ByteArray): ByteArray {
        val offset =
          (if (bytes.size >= 4 && bytes[0] == 0.toByte() && bytes[1] == 0.toByte() && bytes[2] == 0.toByte() && bytes[3] == 1.toByte()) 4 else if (bytes.size >= 3 && bytes[0] == 0.toByte() && bytes[1] == 0.toByte() && bytes[2] == 1.toByte()) 3 else 0) + 1 // skip nal unit type

        return bytes.copyOfRange(offset, bytes.size)
      }

      val base64String = Base64.encodeToString(extractSpsRbsp(byteArray), Base64.NO_WRAP)
      Log.d(tag, base64String)
      Log.d(tag, byteArray.joinToString(separator = "") { String.format("%02X", it) })
    }
    videoPacket = when (commandsManager.videoCodec) {
      VideoCodec.H265 -> {
        if (vps == null || pps == null) throw IllegalArgumentException("pps or vps can't be null with h265")
        printBytes(data = sps, tag = "SPS-H265")
        printBytes(data = pps, tag = "PPS-H265")
        printBytes(data = vps, tag = "VPS-H265")
        H265Packet().apply { sendVideoInfo(sps, pps, vps) }
      }

      VideoCodec.AV1 -> {
        printBytes(data = sps, tag = "SPS-AV1")
        Av1Packet().apply { sendVideoInfo(sps) }
      }

      else -> {
        if (pps == null) throw IllegalArgumentException("pps can't be null with h264")
        printBytes(data = sps, tag = "SPS-H264")
        printBytes(data = pps, tag = "PPS-H264")

        H264Packet().apply { sendVideoInfo(sps, pps) }
      }
    }
  }

  override fun setAudioInfo(sampleRate: Int, isStereo: Boolean) {
    audioPacket = when (commandsManager.audioCodec) {
      AudioCodec.G711 -> G711Packet().apply { sendAudioInfo() }
      AudioCodec.AAC -> AacPacket().apply { sendAudioInfo(sampleRate, isStereo) }
      AudioCodec.OPUS -> {
        throw IllegalArgumentException("Unsupported codec: ${commandsManager.audioCodec.name}")
      }
    }
  }

  override suspend fun onRun() {
    while (scope.isActive && running) {
      val error = runCatching {
        val mediaFrame = runInterruptible { queue.take() }
        getFlvPacket(mediaFrame) { flvPacket ->
          var size = 0
          if (flvPacket.type == FlvType.VIDEO) {
            videoFramesSent++
            socket?.let { socket ->
              size = commandsManager.sendVideoPacket(flvPacket, socket)
              if (isEnableLogs) {
                Log.i(TAG, "wrote Video packet, size $size")
              }
            }
          } else {
            audioFramesSent++
            socket?.let { socket ->
              size = commandsManager.sendAudioPacket(flvPacket, socket)
              if (isEnableLogs) {
                Log.i(TAG, "wrote Audio packet, size $size")
              }
            }
          }
          bytesSend += size
          bytesSendPerSecond += size
        }
      }.exceptionOrNull()
      if (error != null) {
        onMainThread {
          connectChecker.onConnectionFailed("Error send packet, ${error.validMessage()}")
        }
        Log.e(TAG, "send error: ", error)
        running = false
        return
      }
    }
  }

  override suspend fun stopImp(clear: Boolean) {
    audioPacket.reset(clear)
    videoPacket.reset(clear)
  }

//  val job = CoroutineScope(Dispatchers.IO) + SupervisorJob()

  private suspend fun getFlvPacket(mediaFrame: MediaFrame?, callback: suspend (FlvPacket) -> Unit) {
    if (mediaFrame == null) return
    // Chỉ loại bỏ SEI trước IDR cho VIDEO keyframe, KHÔNG áp dụng cho audio
    if (mediaFrame.type == MediaFrame.Type.VIDEO && mediaFrame.info.isKeyFrame) {
      val stripped = tryRemoveSeiBeforeIdr(mediaFrame.data)
      val newMediaFrame = MediaFrame(
        data = stripped, info = mediaFrame.info, type = mediaFrame.type
      )
      videoPacket.createFlvPacket(newMediaFrame) { callback(it) }
      return
    }
    when (mediaFrame.type) {
      MediaFrame.Type.VIDEO -> videoPacket.createFlvPacket(mediaFrame) { callback(it) }
      MediaFrame.Type.AUDIO -> audioPacket.createFlvPacket(mediaFrame) { callback(it) }
    }
  }

  /**
   * Dynamically removes vendor-specific SEI NAL units that precede the IDR NAL
   * in H.264 keyframes. Returns the original buffer if no SEI is found.
   *
   * Scans for start codes (00 00 00 01 or 00 00 01) and checks NAL type:
   * - Type 6 = SEI → skip it
   * - Type 5 = IDR → return from this position onward
   *
   * @param buffer Raw encoded H.264 keyframe data
   * @return ByteBuffer starting at IDR NAL, or original buffer if no SEI found
   */
  fun tryRemoveSeiBeforeIdr(buffer: ByteBuffer): ByteBuffer {
    val dup = buffer.duplicate()
    val remaining = dup.remaining()
    if (remaining < 5) return buffer

    // Read into byte array for scanning
    val bytes = ByteArray(remaining)
    val startPos = dup.position()
    dup.get(bytes)
    dup.position(startPos)

    // Find all NAL start code positions
    var i = 0
    var idrOffset = -1
    while (i < bytes.size - 4) {
      val is4Byte = bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte() &&
          bytes[i + 2] == 0.toByte() && bytes[i + 3] == 1.toByte()
      val is3Byte = bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte() &&
          bytes[i + 2] == 1.toByte()

      if (is4Byte || is3Byte) {
        val nalTypeOffset = if (is4Byte) i + 4 else i + 3
        if (nalTypeOffset < bytes.size) {
          val nalType = (bytes[nalTypeOffset].toInt() and 0x1F)
          when (nalType) {
            5 -> {
              // IDR NAL found
              idrOffset = i
              break
            }
            6 -> {
              // SEI NAL — continue scanning to find IDR after it
            }
            7, 8 -> {
              // SPS/PPS — these should be kept, don't strip before them
            }
          }
        }
      }
      i++
    }

    if (idrOffset > 0) {
      // Found SEI before IDR — strip everything before IDR
      Log.d(TAG, "removeSeiBeforeIdr: stripping $idrOffset bytes of SEI before IDR (total=$remaining)")
      dup.position(startPos + idrOffset)
      return dup.slice()
    }

    // No SEI found before IDR, return original buffer untouched
    return buffer
  }

  /**
   * @deprecated Use tryRemoveSeiBeforeIdr instead
   */
  @Deprecated("Use tryRemoveSeiBeforeIdr instead", replaceWith = ReplaceWith("tryRemoveSeiBeforeIdr(buffer)"))
  fun removeSeiBeforeIdr(
    buffer: ByteBuffer,
    headerSize: Int,
  ): ByteBuffer {
    val dup = buffer.duplicate()
    require(dup.remaining() > headerSize) {
      "Buffer too small: remaining=${dup.remaining()}, headerSize=$headerSize"
    }
    dup.position(dup.position() + headerSize)
    return dup.slice()
  }

  /**
   * Dumps the current video frame to a temporary H.264 file if it is an IDR (keyframe).
   *
   * asynchronously writes the raw H.264 byte payload to the app's cache directory.
   *
   * Intended strictly for debugging and low-level inspection of encoder output.
   *
   * ⚠ Side effects:
   * - Performs disk I/O on Dispatchers.IO
   * - Spawns a new CoroutineScope per invocation
   * - Writes raw H.264 data without container or metadata
   *
   * @param mediaFrame Video frame containing encoded H.264 data.
   */
  fun dumpIdrFrameToCache(mediaFrame: MediaFrame) {
    val job = CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, _ -> })

    if (mediaFrame.info.isKeyFrame) {
      job.launch {
        val file = File(
          "/data/data/app.smartsports.sst.vn.dev/cache/" + "${System.currentTimeMillis()}_frame.h264"
        )
        file.outputStream().use { output ->
          val buffer = mediaFrame.data.duplicate()
          val bytes = ByteArray(buffer.remaining())
          buffer.get(bytes)
          output.write(bytes)
        }
      }
    }
  }
}