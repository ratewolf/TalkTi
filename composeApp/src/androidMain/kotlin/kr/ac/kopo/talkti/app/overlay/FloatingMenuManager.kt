package kr.ac.kopo.talkti.app.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * 앱 파트: 드래그 가능한 플로팅 버튼 관리 (마이크 버튼 하나로 통합)
 */
class FloatingMenuManager(
    private val context: Context,
    private val onAppGuideClick: () -> Unit,
    // 아래 인자들은 확장을 대비해 남겨두거나, 당장 필요 없으면 생략 가능하지만 
    // 기존 호출부와의 호환성을 위해 유지합니다.
    private val onTextInputClick: () -> Unit = {},
    private val onKioskModeClick: () -> Unit = {},
    private val onOpenAppClick: () -> Unit = {}
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var rootLayout: LinearLayout? = null

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 100
        y = 300
    }

    private var mainButton: TextView? = null

    fun show() {
        if (rootLayout != null) return

        rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // 메인 마이크 버튼
        mainButton = createCircleButton(
            icon = "🎤",
            sizeDp = 64,
            backgroundColor = Color.parseColor("#f9e000"),
            iconColor = Color.BLACK,
            iconTextSize = 32f
        ).apply {
            setOnTouchListener(object : View.OnTouchListener {
                private var initialX = 0
                private var initialY = 0
                private var initialTouchX = 0f
                private var initialTouchY = 0f
                private var isMoving = false

                override fun onTouch(v: View, event: MotionEvent): Boolean {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialX = params.x
                            initialY = params.y
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            isMoving = false
                            return true
                        }

                        MotionEvent.ACTION_MOVE -> {
                            val dx = (event.rawX - initialTouchX).toInt()
                            val dy = (event.rawY - initialTouchY).toInt()

                            if (abs(dx) > 10 || abs(dy) > 10) {
                                isMoving = true
                                params.x = initialX + dx
                                params.y = initialY + dy
                                windowManager.updateViewLayout(rootLayout, params)
                            }
                            return true
                        }

                        MotionEvent.ACTION_UP -> {
                            if (!isMoving) {
                                v.performClick()
                                onAppGuideClick() // 바로 마이크 기능 실행
                            }
                            return true
                        }
                    }
                    return false
                }
            })
        }

        rootLayout?.addView(mainButton)
        windowManager.addView(rootLayout, params)
    }

    fun updateMainButtonStatus(isListening: Boolean) {
        mainButton?.post {
            if (isListening) {
                mainButton?.text = "듣는 중"
                mainButton?.textSize = 14f
                updateCircleColor(mainButton, Color.parseColor("#34A853"))
            } else {
                mainButton?.text = "🎤"
                mainButton?.textSize = 32f
                updateCircleColor(mainButton, Color.parseColor("#f9e000"))
            }
        }
    }

    private fun updateCircleColor(view: View?, color: Int) {
        (view?.background as? GradientDrawable)?.setColor(color)
    }

    fun hide() {
        rootLayout?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
            }
            rootLayout = null
        }
    }

    private fun createCircleButton(
        icon: String,
        sizeDp: Int,
        backgroundColor: Int,
        iconColor: Int,
        iconTextSize: Float
    ): TextView {
        return TextView(context).apply {
            text = icon
            textSize = iconTextSize
            setTextColor(iconColor)
            gravity = Gravity.CENTER
            includeFontPadding = false
            background = createCircleDrawable(backgroundColor)
            isClickable = true
            isFocusable = true

            layoutParams = LinearLayout.LayoutParams(
                dp(sizeDp),
                dp(sizeDp)
            ).apply {
                setMargins(0, dp(6), 0, dp(6))
            }
        }
    }

    private fun createCircleDrawable(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(dp(2), Color.WHITE)
        }
    }

    private fun dp(value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }
}
