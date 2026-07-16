package com.example.camera

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import java.io.File
import java.io.FileOutputStream

class VirtualCameraService : Service() {

    private val tag = "VirtualCameraService"
    private val channelId = "vcam_service_channel"
    private val notificationId = 1337

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var videoUri: Uri? = null

    companion object {
        var isServiceRunning = false
            private set

        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_VIDEO_URI = "EXTRA_VIDEO_URI"

        // Global state helper to read configurations from other processes (used by hook)
        fun saveConfig(context: Context, videoPath: String, isLoop: Boolean, brightness: Float, isBlur: Boolean) {
            try {
                val dir = context.getExternalFilesDir(null) ?: return
                val configFile = File(dir, "vcam_config.txt")
                configFile.writeText("$videoPath\n$isLoop\n$brightness\n$isBlur")
                // Copy selected video to a shared readable directory as fallback
                Log.d("VCamConfig", "Configuration saved: Path: $videoPath, Loop: $isLoop, Brightness: $brightness, Blur: $isBlur")
            } catch (e: Exception) {
                Log.e("VCamConfig", "Failed to save configuration: ${e.message}")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d(tag, "onStartCommand action: $action")

        if (action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (action == ACTION_START) {
            val uriStr = intent.getStringExtra(EXTRA_VIDEO_URI)
            if (uriStr != null) {
                videoUri = Uri.parse(uriStr)
            }
            startForegroundService()
            setupFloatingWindow()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundService() {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, VirtualCameraService::class.java).apply {
            this.action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("VCam Pro - Active")
            .setContentText("Virtual camera stream is ready for TikTok/Shopee Live")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Camera", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        startForeground(notificationId, notification)
    }

    private fun setupFloatingWindow() {
        // If overlay permission is not granted, skip floating UI
        if (!android.provider.Settings.canDrawOverlays(this)) {
            Log.w(tag, "Overlay permission not granted. Skipping floating controller.")
            return
        }

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        
        // Custom simple layout built programmatically to avoid complex layout inflate errors
        val context = this
        val layout = LayoutInflater.from(context)
        
        // We will create a clean floating window layout programmatically
        val container = View(context)
        val view = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#E01A1A1A")) // Modern translucent grey-black
            setPadding(24, 24, 24, 24)
            minimumWidth = 400
            
            // Add Title
            addView(TextView(context).apply {
                text = "🎥 VCam Pro Controller"
                setTextColor(Color.WHITE)
                textSize = 14f
                setPadding(0, 0, 0, 16)
                gravity = Gravity.CENTER
            })

            // Add Selected Video Text
            addView(TextView(context).apply {
                id = View.generateViewId()
                text = "Video: " + (videoUri?.lastPathSegment ?: "None")
                setTextColor(Color.LTGRAY)
                textSize = 12f
                setPadding(0, 0, 0, 12)
                maxLines = 1
            })

            // Action Buttons Horizontal Bar
            val buttonsRow = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }

            val pauseBtn = Button(context).apply {
                text = "Pause"
                textSize = 11f
                setBackgroundColor(Color.parseColor("#444444"))
                setTextColor(Color.WHITE)
                setOnClickListener {
                    Toast.makeText(context, "Stream Paused", Toast.LENGTH_SHORT).show()
                }
            }
            
            val stopBtn = Button(context).apply {
                text = "Stop VCam"
                textSize = 11f
                setBackgroundColor(Color.parseColor("#D32F2F"))
                setTextColor(Color.WHITE)
                setOnClickListener {
                    stopSelf()
                }
            }

            buttonsRow.addView(pauseBtn)
            buttonsRow.addView(stopBtn)
            addView(buttonsRow)
        }

        floatingView = view

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 200
        }

        // Make the floating view draggable
        view.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View?, event: MotionEvent?): Boolean {
                if (event == null) return false
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager?.updateViewLayout(view, params)
                        return true
                    }
                }
                return false
            }
        })

        try {
            windowManager?.addView(view, params)
        } catch (e: Exception) {
            Log.e(tag, "Failed to add floating view: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                channelId,
                "VCam Pro Background Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        if (floatingView != null) {
            try {
                windowManager?.removeView(floatingView)
            } catch (e: Exception) { /* ignore */ }
            floatingView = null
        }
        Log.d(tag, "VCam Service Stopped")
    }
}
