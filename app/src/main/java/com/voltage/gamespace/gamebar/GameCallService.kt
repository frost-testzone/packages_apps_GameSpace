/*
 * Copyright (C) 2026 VoltageOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.voltage.gamespace.gamebar

import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.util.Log

@Suppress("DEPRECATION")
class GameCallService : InCallService() {

    private val handler = Handler(Looper.getMainLooper())
    private val observedCalls = mutableSetOf<Call>()
    private var pendingSpeakerCall: Call? = null

    private val speakerTimeout = Runnable {
        if (pendingSpeakerCall != null) {
            Log.w(TAG, "Speaker request expired")
        }
        clearPendingSpeaker()
    }

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            if (call !== pendingSpeakerCall) return
            when (state) {
                Call.STATE_ACTIVE -> routePendingSpeaker()
                Call.STATE_HOLDING,
                Call.STATE_DISCONNECTING,
                Call.STATE_DISCONNECTED -> clearPendingSpeaker()
            }
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        val binder = super.onBind(intent)
        instance = this
        return binder
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        if (observedCalls.add(call)) {
            call.registerCallback(callCallback, handler)
        }
    }

    override fun onCallRemoved(call: Call) {
        if (call === pendingSpeakerCall) {
            clearPendingSpeaker()
        }
        if (observedCalls.remove(call)) {
            call.unregisterCallback(callCallback)
        }
        super.onCallRemoved(call)
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState) {
        super.onCallAudioStateChanged(audioState)
        routePendingSpeaker()
    }

    override fun onUnbind(intent: Intent): Boolean {
        release()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    private fun answerIncoming(preferSpeaker: Boolean): Boolean {
        val call = calls.firstOrNull {
            it.state == Call.STATE_RINGING &&
                !it.details.hasProperty(Call.Details.PROPERTY_SELF_MANAGED) &&
                !it.details.hasProperty(Call.Details.PROPERTY_IS_EXTERNAL_CALL)
        } ?: run {
            if (preferSpeaker) {
                Log.w(TAG, "Incoming call unavailable; using normal answering")
            }
            return false
        }

        clearPendingSpeaker()

        if (preferSpeaker) {
            pendingSpeakerCall = call
            handler.postDelayed(speakerTimeout, SPEAKER_TIMEOUT_MS)
        }

        return try {
            call.answer(call.details.videoState)
            routePendingSpeaker()
            true
        } catch (e: RuntimeException) {
            clearPendingSpeaker()
            Log.w(TAG, "Unable to answer through in-call service", e)
            false
        }
    }

    private fun routePendingSpeaker() {
        val call = pendingSpeakerCall ?: return
        if (call.state != Call.STATE_ACTIVE) return

        val audioState = callAudioState ?: return
        val externalRoutes =
            CallAudioState.ROUTE_WIRED_HEADSET or CallAudioState.ROUTE_BLUETOOTH

        if ((audioState.supportedRouteMask and externalRoutes) != 0 ||
            (audioState.route and externalRoutes) != 0 ||
            audioState.route == CallAudioState.ROUTE_SPEAKER
        ) {
            clearPendingSpeaker()
            return
        }

        if ((audioState.supportedRouteMask and CallAudioState.ROUTE_SPEAKER) == 0) {
            return
        }

        clearPendingSpeaker()

        try {
            setAudioRoute(CallAudioState.ROUTE_SPEAKER)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Unable to request speaker route", e)
        }
    }

    private fun clearPendingSpeaker() {
        handler.removeCallbacks(speakerTimeout)
        pendingSpeakerCall = null
    }

    private fun release() {
        clearPendingSpeaker()
        observedCalls.forEach { it.unregisterCallback(callCallback) }
        observedCalls.clear()
        if (instance === this) {
            instance = null
        }
    }

    companion object {
        private const val TAG = "GameSpaceCalls"
        private const val SPEAKER_TIMEOUT_MS = 10_000L
        private var instance: GameCallService? = null

        fun answerIncomingCall(preferSpeaker: Boolean): Boolean {
            val service = instance ?: run {
                if (preferSpeaker) {
                    Log.w(TAG, "In-call service unavailable; using normal answering")
                }
                return false
            }
            return service.answerIncoming(preferSpeaker)
        }

        fun cancelPendingSpeaker() {
            instance?.clearPendingSpeaker()
        }
    }
}
