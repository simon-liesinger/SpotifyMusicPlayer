package com.musicdownloader.app.player

import android.app.PendingIntent
import android.content.Intent
import android.media.audiofx.LoudnessEnhancer
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.musicdownloader.app.MainActivity

/**
 * Background music playback service using Media3.
 *
 * Provides:
 * - Background audio playback via ExoPlayer
 * - MediaSession for system integration (lock screen, notification controls)
 * - Bluetooth AVRCP support (play/pause/skip from headphones)
 * - Audio focus management (auto-duck/pause for calls, navigation, etc.)
 * - Audio becoming noisy handling (pause when headphones disconnected)
 * - Loudness normalization via LoudnessEnhancer
 */
class MusicPlayerService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var normalizeEnabled = false

    companion object {
        const val CMD_SET_NORMALIZE = "SET_NORMALIZE"
        const val CMD_SET_LOUDNESS = "SET_LOUDNESS"
        const val KEY_ENABLED = "enabled"
        const val KEY_LOUDNESS_DB = "loudness_db"
        // RMS target: -14 LUFS ≈ -20 dB RMS for typical music
        const val TARGET_LOUDNESS_DB = -20.0f
        const val MAX_ATTENUATION_DB = 6.0f // Never reduce by more than 6 dB
        const val MAX_BOOST_DB = 12.0f      // Never boost by more than 12 dB
    }

    override fun onCreate() {
        super.onCreate()

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true // handleAudioFocus - auto-manages focus for calls, nav, etc.
            )
            .setHandleAudioBecomingNoisy(true) // Auto-pause when headphones disconnected
            .build()

        // Set up LoudnessEnhancer for normalization
        try {
            loudnessEnhancer = LoudnessEnhancer(player.audioSessionId).apply {
                enabled = false
            }
        } catch (_: Exception) {
            // LoudnessEnhancer not available on this device
        }

        // PendingIntent to launch the app when user taps the notification
        val sessionActivityIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val callback = object : MediaSession.Callback {
            override fun onConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo
            ): MediaSession.ConnectionResult {
                val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                    .add(SessionCommand(CMD_SET_NORMALIZE, Bundle.EMPTY))
                    .add(SessionCommand(CMD_SET_LOUDNESS, Bundle.EMPTY))
                    .build()
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(commands)
                    .build()
            }

            override fun onCustomCommand(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                customCommand: SessionCommand,
                args: Bundle
            ): ListenableFuture<SessionResult> {
                when (customCommand.customAction) {
                    CMD_SET_NORMALIZE -> {
                        normalizeEnabled = args.getBoolean(KEY_ENABLED, false)
                        if (!normalizeEnabled) {
                            loudnessEnhancer?.setTargetGain(0)
                            loudnessEnhancer?.enabled = false
                            session.player.volume = 1.0f
                        }
                    }
                    CMD_SET_LOUDNESS -> {
                        if (normalizeEnabled) {
                            val trackLoudness = args.getFloat(KEY_LOUDNESS_DB, TARGET_LOUDNESS_DB)
                            applyNormalization(trackLoudness, session.player)
                        }
                    }
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
        }

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivityIntent)
            .setCallback(callback)
            .build()
    }

    private fun applyNormalization(trackLoudnessDb: Float, player: Player) {
        val rawGainDb = TARGET_LOUDNESS_DB - trackLoudnessDb

        // Clamp gain to safe range
        val gainDb = rawGainDb.coerceIn(-MAX_ATTENUATION_DB, MAX_BOOST_DB)

        if (gainDb >= 0) {
            // Quiet track: boost with LoudnessEnhancer, full player volume
            player.volume = 1.0f
            loudnessEnhancer?.apply {
                setTargetGain((gainDb * 100).toInt()) // dB to millibels
                enabled = true
            }
        } else {
            // Loud track: attenuate with player volume, no enhancer boost
            loudnessEnhancer?.apply {
                setTargetGain(0)
                enabled = false
            }
            // Convert dB attenuation to linear: 10^(gainDb/20)
            val linearGain = Math.pow(10.0, gainDb.toDouble() / 20.0).toFloat()
            player.volume = linearGain.coerceIn(0.5f, 1.0f)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        // Stop service if nothing is playing
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        loudnessEnhancer?.release()
        loudnessEnhancer = null
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
