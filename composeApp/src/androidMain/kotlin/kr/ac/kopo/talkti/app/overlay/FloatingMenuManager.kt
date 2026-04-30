package kr.ac.kopo.talkti.app.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.util.TypedValue

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

    private var mainButton: Button? = null

    fun show() {
        if (rootLayout != null) return

        rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // 1. 메인 버튼 (똑띠 아이콘 + 드롭다운 화살표 역할)
        mainButton = Button(context).apply {
            text = "똑띠 ▼"
            setBackgroundColor(Color.parseColor("#FEE500"))
            setTextColor(Color.BLACK)
            setPadding(20, 10, 20, 10)
            elevation = 15f
            
            // 드래그 및 클릭 처리
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
                            if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                                isMoving = true
                                params.x = initialX + dx
                                params.y = initialY + dy
                                windowManager.updateViewLayout(rootLayout, params)
                            }
                            return true
                        }
                        MotionEvent.ACTION_UP -> {
                            if (!isMoving) {
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
            visibility = View.GONE
            setBackgroundColor(Color.parseColor("#F0F0F0"))
            setPadding(10, 10, 10, 10)
            
            addView(createSubButton("📱 앱 사용 안내") { onAppGuideClick() })
            addView(createSubButton("🏪 키오스크 안내") { onKioskModeClick() })
            addView(createSubButton("⚙️ 똑띠 앱 열기") { onOpenAppClick() })
        }

        rootLayout?.addView(mainButton)
        rootLayout?.addView(subMenuLayout)

        windowManager.addView(rootLayout, params)
    }

    private fun createSubButton(text: String, onClick: () -> Unit): Button {
        return Button(context).apply {
            this.text = text
            this.isAllCaps = false
            this.textSize = 14f
            setBackgroundColor(Color.WHITE)
            setTextColor(Color.BLACK)
            val margin = 5
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, margin, 0, margin)
            }
            layoutParams = params
            
            setOnClickListener {
                onClick()
                toggleMenu() // 클릭 후 메뉴 닫기
            }
        }
    }

    private fun toggleMenu() {
        isMenuOpen = !isMenuOpen
        subMenuLayout?.visibility = if (isMenuOpen) View.VISIBLE else View.GONE
    }

    fun updateMainButtonStatus(isListening: Boolean) {
        mainButton?.post {
            if (isListening) {
                mainButton?.text = "듣는 중..."
                mainButton?.setBackgroundColor(Color.parseColor("#34A853"))
            } else {
                mainButton?.text = "똑띠 ▼"
                mainButton?.setBackgroundColor(Color.parseColor("#FEE500"))
            }
        }
    }

    fun hide() {
        rootLayout?.let {
            windowManager.removeView(it)
            rootLayout = null
        }
    }
}
