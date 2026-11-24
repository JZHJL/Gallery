package org.fossify.gallery.services

import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import org.fossify.gallery.activities.SearchActivity

class FloatingBallService : Service() {

    private var windowManager: WindowManager? = null
    private var ball: View? = null
    private var params: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        addBall()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (ball == null) {
            addBall()
        }
        return START_STICKY
    }

    private fun startForegroundNotification() {
        val channelId = "ai_floating_ball"
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(channelId, "悬浮球", NotificationManager.IMPORTANCE_MIN)
            )
        }
        val notification: Notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
                .setContentTitle("相册检索")
                .setContentText("悬浮球运行中，点击可搜索照片")
                .setSmallIcon(android.R.drawable.ic_menu_search)
                .build()
        } else {
            Notification.Builder(this)
                .setContentTitle("相册检索")
                .setSmallIcon(android.R.drawable.ic_menu_search)
                .build()
        }
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun addBall() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm
        val density = resources.displayMetrics.density
        val size = (56 * density).toInt()

        val view = TextView(this)
        view.text = "搜"
        view.setTextColor(Color.WHITE)
        view.textSize = 18f
        view.gravity = Gravity.CENTER
        val background = GradientDrawable()
        background.shape = GradientDrawable.OVAL
        background.setColor(Color.parseColor("#4C8DFF"))
        view.background = background

        val layoutParams = WindowManager.LayoutParams(
            size,
            size,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        layoutParams.gravity = Gravity.TOP or Gravity.START
        layoutParams.x = (16 * density).toInt()
        layoutParams.y = (resources.displayMetrics.heightPixels * 0.35f).toInt()

        var downTime = 0L
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragged = false

        view.setOnTouchListener { _, event ->
            val lp = view.layoutParams as WindowManager.LayoutParams
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downTime = System.currentTimeMillis()
                    downX = event.rawX
                    downY = event.rawY
                    startX = lp.x
                    startY = lp.y
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (Math.abs(dx) > 12 || Math.abs(dy) > 12) {
                        dragged = true
                    }
                    if (dragged) {
                        lp.x = startX + dx
                        lp.y = startY + dy
                        windowManager?.updateViewLayout(view, lp)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) {
                        val longPress = System.currentTimeMillis() - downTime > 450
                        if (longPress) {
                            showMenu()
                        } else {
                            openSearch()
                        }
                    }
                    true
                }
                else -> false
            }
        }

        try {
            wm.addView(view, layoutParams)
            ball = view
            params = layoutParams
        } catch (e: Exception) {
            stopSelf()
        }
    }

    private fun showMenu() {
        try {
            val dialog = AlertDialog.Builder(this)
                .setItems(arrayOf("搜索照片", "继续索引 500 张", "关闭悬浮球")) { _, which ->
                    when (which) {
                        0 -> openSearch()
                        1 -> AiIndexer.start(this, AiPrefs(this), 500)
                        else -> stopSelf()
                    }
                }
                .create()
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            dialog.show()
        } catch (e: Exception) {
            openSearch()
        }
    }

    private fun openSearch() {
        val intent = Intent(this, SearchActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(intent)
    }

    override fun onDestroy() {
        ball?.let {
            try {
                windowManager?.removeView(it)
            } catch (ignored: Exception) {
            }
        }
        ball = null
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 4101
    }
}
