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
 * 앱 파트: 드래그 가능한 플로팅 버튼 및 드롭다운 메뉴 관리
 */
class FloatingMenuManager(
    private val context: Context,
    private val onAppGuideClick: () -> Unit,
    private val onKioskModeClick: () -> Unit,
    private val onOpenAppClick: () -> Unit
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var rootLayout: LinearLayout? = null
    private var subMenuLayout: LinearLayout? = null
    private var isMenuOpen = false

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

        // 1. 메인 원형 버튼
        mainButton = createCircleButton(
            icon = "…",
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
                                toggleMenu()
                            }
                            return true
                        }
                    }
                    return false
                }
            })
        }

        // 2. 서브 메뉴 레이아웃
        subMenuLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
            setPadding(0, 0, 0, dp(8))

            addView(createIconMenuItem("🎤") { onAppGuideClick() })
            addView(createIconMenuItem("⚙️") { onOpenAppClick() })
            addView(createIconMenuItem("📖") { onKioskModeClick() })
        }

        // 메뉴가 위에 뜨고, 메인 버튼이 아래에 오도록 추가
        rootLayout?.addView(subMenuLayout)
        rootLayout?.addView(mainButton)

        windowManager.addView(rootLayout, params)
    }

    private fun toggleMenu() {
        isMenuOpen = !isMenuOpen
        subMenuLayout?.visibility = if (isMenuOpen) View.VISIBLE else View.GONE
    }

    /**
     * 상태에 따라 메인 버튼의 텍스트와 배경색을 업데이트합니다.
     * 원형 배경을 유지하기 위해 GradientDrawable의 색상을 변경합니다.
     */
    fun updateMainButtonStatus(isListening: Boolean) {
        mainButton?.post {
            if (isListening) {
                mainButton?.text = "듣는 중"
                mainButton?.textSize = 14f
                updateCircleColor(mainButton, Color.parseColor("#34A853"))
            } else {
                mainButton?.text = "…"
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
            windowManager.removeView(it)
            rootLayout = null
        }
    }

    private fun createIconMenuItem(
        icon: String,
        onClick: () -> Unit
    ): TextView {
        return createCircleButton(
            icon = icon,
            sizeDp = 52,
            backgroundColor = Color.WHITE,
            iconColor = Color.parseColor("#80A867"),
            iconTextSize = 24f
        ).apply {
            setOnClickListener {
                onClick()
                toggleMenu()
            }
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
