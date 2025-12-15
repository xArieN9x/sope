package com.example.sopee

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.*
import android.widget.ImageView
import android.widget.TextView
import kotlinx.coroutines.*
import androidx.core.content.ContextCompat

class FloatingWidgetService : Service() {

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var bubbleImage: ImageView? = null
    private var bubbleText: TextView? = null

    companion object {
        private var instance: FloatingWidgetService? = null
        fun isRunning() = instance != null
        fun updateBubbleColor(isActive: Boolean) {
            instance?.updateColor(isActive)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this

        floatingView = LayoutInflater.from(this).inflate(R.layout.widget_layout, null)
        bubbleImage = floatingView?.findViewById(R.id.floatingBubble)
        bubbleText = floatingView?.findViewById(R.id.bubbleText)

        val layoutFlag =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 100

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager?.addView(floatingView, params)

        // Drag + click listener
        floatingView?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
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
                        windowManager?.updateViewLayout(floatingView, params)
                        return true
                    }

                    MotionEvent.ACTION_UP -> {
                        val diffX = Math.abs(event.rawX - initialTouchX)
                        val diffY = Math.abs(event.rawY - initialTouchY)

                        if (diffX < 10 && diffY < 10) openMainActivity()
                        return true
                    }
                }
                return false
            }
        })

        startStatusMonitor()
    }

    private fun updateColor(isActive: Boolean) {
        bubbleImage?.setImageResource(
            if (isActive) R.drawable.green_smooth else R.drawable.red_smooth
        )
    }

    private fun startStatusMonitor() {
        CoroutineScope(Dispatchers.IO).launch {
            while (isRunning()) {
                val active = AppMonitorVPNService.isPandaActive()
                withContext(Dispatchers.Main) { updateColor(active) }
                delay(1500)
            }
        }
    }

    private fun openMainActivity() {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        startActivity(intent)
    }

    override fun onDestroy() {
        floatingView?.let { windowManager?.removeView(it) }
        instance = null
        super.onDestroy()
    }
}
