/*
 * Copyright (C) 2020 The exTHmUI Open Source Project
 * Copyright (C) 2021 AOSP-Krypton Project
 * Copyright (C) 2022 Nameless-AOSP Project
 * Copyright (C) 2022-2024 crDroid Android Project
 * Copyright (C) 2023 the risingOS Android Project
 * Copyright (C) 2025 AxionOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.voltage.gamespace.gamebar

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ServiceScoped
import com.voltage.gamespace.R
import com.voltage.gamespace.data.AppSettings
import com.voltage.gamespace.gamebar.lifecycle.repeatWhenAttached
import com.voltage.gamespace.utils.dp as extDp
import javax.inject.Inject
import kotlinx.coroutines.*

@ServiceScoped
class CallListener @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appSettings: AppSettings
) {
    private val telephonyManager = context.getSystemService(TelephonyManager::class.java)!!
    private val telecomManager = context.getSystemService(TelecomManager::class.java)!!
    private val windowManager = context.getSystemService(WindowManager::class.java)!!

    private var preCallHeadsUpState: Int? = null
    private var isTempHeadsUpDisabled = false

    private val callsMode = appSettings.callsMode
    private val callOverlayEnabled = appSettings.callOverlayEnabled

    private var ringerOverlay: ComposeView? = null
    private var isOverlayShowing = false

    private val phoneStateListener = object: PhoneStateListener() {
        override fun onCallStateChanged(state: Int, incomingNumber: String?) {
            if (state == TelephonyManager.CALL_STATE_RINGING && callOverlayEnabled) {
                showRingerOverlay(incomingNumber ?: "")
            }
        }
    }

    private val telephonyCallback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) {
            when (state) {
                TelephonyManager.CALL_STATE_RINGING -> {
                    handleIncomingCall()
                }
                TelephonyManager.CALL_STATE_OFFHOOK -> handleOffhookState()
                TelephonyManager.CALL_STATE_IDLE -> handleIdleState()
            }
        }
    }

    fun init() {
        telephonyManager.registerTelephonyCallback(context.mainExecutor, telephonyCallback)
        telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
    }

    fun destroy() {
        GameCallService.cancelPendingSpeaker()
        telephonyManager.unregisterTelephonyCallback(telephonyCallback)
        dismissRingerOverlay(immediate = true)
        telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
    }

    private fun handleIncomingCall() {
        if (callsMode == 0) return

        when (callsMode) {
            1 -> {
                answerIncomingCall(preferSpeaker = true)
                Toast.makeText(
                    context,
                    context.getString(R.string.in_game_calls_received_number, ""),
                    Toast.LENGTH_SHORT
                ).show()
            }
            2 -> {
                telecomManager.endCall()
                Toast.makeText(
                    context,
                    context.getString(R.string.in_game_calls_rejected_number, ""),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun handleOffhookState() {
        dismissRingerOverlay()
    }

    private fun handleIdleState() {
        dismissRingerOverlay()
    }

    private fun answerIncomingCall(preferSpeaker: Boolean) {
        try {
            if (!GameCallService.answerIncomingCall(preferSpeaker)) {
                telecomManager.acceptRingingCall()
            }
            dismissRingerOverlay()
        } catch (e: RuntimeException) {
            Log.w("GameSpaceCalls", "Unable to answer incoming call", e)
        }
    }

    private fun showRingerOverlay(incomingNumber: String) {
        if (isOverlayShowing) return

        try {
            preCallHeadsUpState = Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.HEADS_UP_NOTIFICATIONS_ENABLED,
                1
            )
            if (preCallHeadsUpState == 1) {
                Settings.Global.putInt(
                    context.contentResolver,
                    Settings.Global.HEADS_UP_NOTIFICATIONS_ENABLED,
                    0
                )
                isTempHeadsUpDisabled = true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val sidebarX = appSettings.x

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = if (sidebarX < 0)
                Gravity.TOP or Gravity.END
            else
                Gravity.TOP or Gravity.START

            x = 1
            y = appSettings.y - 71.extDp
        }

        val callerPhoto = loadContactPhoto(context, incomingNumber)

        ringerOverlay = ComposeView(context).apply {
            repeatWhenAttached {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    setViewCompositionStrategy(
                        ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
                    )
                    setContent {
                        val isDark = isSystemInDarkTheme()
                        val scheme = if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
                        MaterialTheme(colorScheme = scheme) {
                            CallOverlay(
                                onAccept = {
                                    answerIncomingCall(
                                        preferSpeaker = appSettings.callOverlaySpeakerphone
                                    )
                                },
                                onReject = {
                                    telecomManager.endCall()
                                    dismissRingerOverlay()
                                },
                                onDismiss = { dismissRingerOverlay() },
                                alignRight = sidebarX < 0,
                                onDismissAnimation = { dismissRingerOverlay() },
                                callerPhoto = callerPhoto
                            )
                        }
                    }
                }
            }
        }

        try {
            windowManager.addView(ringerOverlay, layoutParams)
            isOverlayShowing = true
        } catch (e: Exception) {
            e.printStackTrace()
            isOverlayShowing = false
        }
    }

    private fun dismissRingerOverlay(immediate: Boolean = false) {
        if (isTempHeadsUpDisabled) {
            preCallHeadsUpState?.let { state ->
                try {
                    Settings.Global.putInt(
                        context.contentResolver,
                        Settings.Global.HEADS_UP_NOTIFICATIONS_ENABLED,
                        state
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            isTempHeadsUpDisabled = false
            preCallHeadsUpState = null
        }

        ringerOverlay?.let { overlay ->
            try {
                if (overlay.isAttachedToWindow) {
                    if (immediate) {
                        windowManager.removeViewImmediate(overlay)
                    } else {
                        overlay.postDelayed({
                            try {
                                windowManager.removeViewImmediate(overlay)
                            } catch (_: Exception) { }
                        }, 300)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                ringerOverlay = null
                isOverlayShowing = false
            }
        }
    }

    fun loadContactPhoto(context: Context, phoneNumber: String): ImageBitmap? {
        val resolver = context.contentResolver
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(phoneNumber)
        )

        resolver.query(uri, arrayOf(ContactsContract.PhoneLookup.PHOTO_URI), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val photoUri = cursor.getString(0) ?: return null
                resolver.openInputStream(Uri.parse(photoUri))?.use { stream ->
                    return BitmapFactory.decodeStream(stream)?.asImageBitmap()
                }
            }
        }
        return null
    }
}

@Composable
fun CallOverlay(
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onDismiss: () -> Unit,
    alignRight: Boolean,
    onDismissAnimation: suspend () -> Unit,
    callerPhoto: ImageBitmap? = null
) {
    var isVisible by remember { mutableStateOf(false) }
    var isDismissing by remember { mutableStateOf(false) }
    var btnPressed by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(
        targetValue = when {
            isDismissing -> 0f
            isVisible -> 1f
            else -> 0f
        },
        animationSpec = tween(250, easing = FastOutSlowInEasing),
        label = "shrink_scale"
    )

    val fadeAlpha by animateFloatAsState(
        targetValue = if (isDismissing) 0f else 1f,
        animationSpec = tween(250),
        label = "fade"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    val buttonScale by animateFloatAsState(
        targetValue = if (btnPressed) 0.85f else 1f,
        animationSpec = tween(100, easing = LinearOutSlowInEasing),
        label = "button_press"
    )

    var showPhoto by remember { mutableStateOf(false) }
    var acceptAlpha by remember { mutableStateOf(1f) }
    var photoAlpha by remember { mutableStateOf(0f) }

    LaunchedEffect(callerPhoto) {
        isVisible = true

        if (callerPhoto != null) {
            while (isVisible) {
                showPhoto = false
                acceptAlpha = 1f
                photoAlpha = 0f
                delay(1500)

                animate(
                    initialValue = 1f,
                    targetValue = 0f,
                    animationSpec = tween(500)
                ) { value, _ -> acceptAlpha = value }
                showPhoto = true

                animate(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = tween(500)
                ) { value, _ -> photoAlpha = value }

                delay(1500)

                animate(
                    initialValue = 1f,
                    targetValue = 0f,
                    animationSpec = tween(500)
                ) { value, _ -> photoAlpha = value }
            }
        } else {
            acceptAlpha = 1f
            photoAlpha = 0f
        }
    }

    LaunchedEffect(isDismissing) {
        if (isDismissing) {
            isVisible = false
            delay(250)
            onDismissAnimation()
        }
    }

    Box(
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                alpha = fadeAlpha
            ),
        contentAlignment = if (alignRight) Alignment.TopEnd else Alignment.TopStart
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(12.dp)
                .shadow(8.dp, CircleShape)
        ) {
            IconButton(
                onClick = {
                    btnPressed = true
                    onReject()
                    isDismissing = true
                },
                modifier = Modifier
                    .size(56.dp)
                    .scale(buttonScale)
                    .clip(CircleShape)
                    .background(Color(0xFFEF5350))
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Reject",
                    tint = Color.White.copy(alpha = pulseAlpha),
                    modifier = Modifier.size(28.dp)
                )
            }

            Box(
                modifier = Modifier.size(56.dp),
                contentAlignment = Alignment.Center
            ) {
                IconButton(
                    onClick = {
                        btnPressed = true
                        onAccept()
                        isDismissing = true
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = acceptAlpha }
                        .scale(buttonScale)
                        .clip(CircleShape)
                        .background(Color(0xFF4CAF50))
                ) {
                    Icon(
                        imageVector = Icons.Filled.Call,
                        contentDescription = "Accept",
                        tint = Color.White.copy(alpha = pulseAlpha),
                        modifier = Modifier.size(28.dp)
                    )
                }

                callerPhoto?.let { photo ->
                    IconButton(
                        onClick = {
                            btnPressed = true
                            onAccept()
                            isDismissing = true
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = photoAlpha }
                            .scale(buttonScale)
                            .clip(CircleShape)
                    ) {
                        Image(
                            bitmap = photo,
                            contentDescription = "Caller photo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape)
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable {
                        isDismissing = true
                        onDismiss()
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = "Dismiss",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
