
package com.example.poolaimassist

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat

class OverlayService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        private const val CHANNEL_ID = "pool_aim_assist_channel"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "PoolAimAssist"
    }

    private var mediaProjection: MediaProjection? = null
    private var captureEngine: CaptureEngine? = null
    private var overlayView: OverlayView? = null
    private var controlPanel: ControlPanelView? = null
    private var calibrationOverlay: CalibrationOverlay? = null
    private var windowManager: WindowManager? = null
    private val detector = BallDetector()
    private var calibrating = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
            Log.i(TAG, "Foreground service started")

            val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
            val resultData: Intent? =
                if (Build.VERSION.SDK_INT >= 33) {
                    intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent?.getParcelableExtra(EXTRA_RESULT_DATA)
                }

            if (resultCode == -1 || resultData == null) {
                throw IllegalStateException("Missing screen capture permission data")
            }

            val projectionManager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

            mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)
                ?: throw IllegalStateException("MediaProjection is null")

            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.w(TAG, "MediaProjection stopped")
                    stopSelf()
                }
            }, Handler(Looper.getMainLooper()))

            Log.i(TAG, "MediaProjection created")

            addOverlayWindow()
            Log.i(TAG, "OverlayView added")

            addControlPanel()
            Log.i(TAG, "ControlPanel added")

            if (!CalibrationStore.hasCalibration(this)) {
                startCalibration()
                Log.i(TAG, "Calibration started")
            }

            captureEngine = CaptureEngine(this, mediaProjection!!) { bitmap ->
                try {
                    processFrame(bitmap)
                } catch (e: Exception) {
                    Log.e(TAG, "Frame processing error", e)
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
            }

            captureEngine?.start()
            Log.i(TAG, "Capture engine started")

            return START_NOT_STICKY

        } catch (e: Exception) {
            Log.e(TAG, "SERVICE START FAILED", e)
            showError(e)
            stopSelf()
            return START_NOT_STICKY
        }
    }

    private fun showError(e: Exception) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(
                applicationContext,
                "PoolAimAssist: ${e.javaClass.simpleName}: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun processFrame(bitmap: android.graphics.Bitmap) {
        try {
            if (calibrating) return

            val balls = detector.detectBalls(bitmap)
            val cue = balls.firstOrNull { it.isCueBall }
            val objectBalls = balls.filterNot { it.isCueBall }
            val pockets = CalibrationStore.loadPockets(this)

            val guides = if (cue != null && pockets.isNotEmpty()) {
                TrajectoryCalculator.computeShotGuides(cue, objectBalls, pockets)
            } else emptyList()

            overlayView?.setPockets(pockets)
            overlayView?.update(cue, guides)

        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun addOverlayWindow() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val view = OverlayView(this)
        overlayView = view

        val overlayType =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START
        windowManager?.addView(view, params)
    }

    private fun addControlPanel() {
        val wm = windowManager ?: error("WindowManager unavailable")

        val overlayType =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START
        params.x = 24
        params.y = 120

        val panel = ControlPanelView(
            this, wm, params,
            onCalibrate = { startCalibration() },
            onStop = { stopSelf() }
        )

        controlPanel = panel
        wm.addView(panel, params)
    }

    private fun startCalibration() {
        if (calibrating) return
        calibrating = true

        val wm = windowManager ?: error("WindowManager unavailable")

        val overlayType =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START

        val calib = CalibrationOverlay(this) { pockets ->
            CalibrationStore.savePockets(this, pockets)
            stopCalibration()
        }

        calibrationOverlay = calib
        wm.addView(calib, params)
    }

    private fun stopCalibration() {
        calibrating = false
        calibrationOverlay?.let { windowManager?.removeView(it) }
        calibrationOverlay = null
        overlayView?.setPockets(CalibrationStore.loadPockets(this))
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Pool Aim Assist",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Pool Aim Assist is running")
            .setContentText("Showing shot guides over the table")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        captureEngine?.stop()
        overlayView?.let { runCatching { windowManager?.removeView(it) } }
        controlPanel?.let { runCatching { windowManager?.removeView(it) } }
        calibrationOverlay?.let { runCatching { windowManager?.removeView(it) } }
        mediaProjection?.stop()
        super.onDestroy()
        Log.i(TAG, "Service destroyed")
    }
}
