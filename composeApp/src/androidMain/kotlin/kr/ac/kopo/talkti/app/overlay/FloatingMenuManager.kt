package kr.ac.kopo.talkti.app.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.Animation
import android.view.animation.ScaleAnimation
import android.widget.ImageView
import android.widget.LinearLayout
import kr.ac.kopo.talkti.R
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
    private val onOpenAppClick: () -> Unit = {},
    private val onLongClick: () -> Unit = {},
    private val onSuccessClick: () -> Unit = {},
    private val onFailClick: () -> Unit = {}
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

    private var mainButton: ImageView? = null
    private var successButton: android.widget.TextView? = null
    private var failButton: android.widget.TextView? = null
    private var isProcessing = false

    fun show() {
        if (rootLayout != null) return

        rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            // 그림자가 잘 보이도록 패딩 추가
            val padding = dp(8)
            setPadding(padding, padding, padding, padding)
        }

        // 메인 마이크 버튼
        mainButton = createCircleButton(
            iconRes = R.drawable.ic_mic,
            sizeDp = 70, // 크기를 60에서 70으로 키움
            backgroundColor = Color.parseColor("#FFE000"), // 카카오 느낌의 노란색
            iconTint = Color.BLACK
        ).apply {
            setOnTouchListener(object : View.OnTouchListener {
                private var initialX = 0
                private var initialY = 0
                private var initialTouchX = 0f
                private var initialTouchY = 0f
                private var isMoving = false
                private var isLongPress = false
                private val longPressRunnable = Runnable {
                    isLongPress = true
                    onLongClick()
                }

                override fun onTouch(v: View, event: MotionEvent): Boolean {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialX = params.x
                            initialY = params.y
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            isMoving = false
                            isLongPress = false
                            v.postDelayed(longPressRunnable, 800) // 0.8초 롱클릭
                            v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(100).start()
                            return true
                        }

                        MotionEvent.ACTION_MOVE -> {
                            val dx = (event.rawX - initialTouchX).toInt()
                            val dy = (event.rawY - initialTouchY).toInt()

                            if (abs(dx) > 10 || abs(dy) > 10) {
                                isMoving = true
                                v.removeCallbacks(longPressRunnable)
                                params.x = initialX + dx
                                params.y = initialY + dy
                                windowManager.updateViewLayout(rootLayout, params)
                            }
                            return true
                        }

                        MotionEvent.ACTION_UP -> {
                            v.removeCallbacks(longPressRunnable)
                            v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                            if (!isMoving && !isLongPress) {
                                v.performClick()
                                onAppGuideClick() 
                            }
                            return true
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            v.removeCallbacks(longPressRunnable)
                            v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                            return true
                        }
                    }
                    return false
                }
            })
        }

        rootLayout?.addView(mainButton)

        // 성공 버튼 (기본 숨김)
        successButton = android.widget.TextView(context).apply {
            text = "✅"
            textSize = 28f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#4CAF50"))
                setStroke(dp(2), Color.WHITE)
            }
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)).apply {
                topMargin = dp(8)
            }
            setOnClickListener { onSuccessClick() }
            visibility = View.GONE
        }

        // 실패 버튼 (기본 숨김)
        failButton = android.widget.TextView(context).apply {
            text = "❌"
            textSize = 28f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#F44336"))
                setStroke(dp(2), Color.WHITE)
            }
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)).apply {
                topMargin = dp(8)
            }
            setOnClickListener { onFailClick() }
            visibility = View.GONE
        }

        rootLayout?.addView(successButton)
        rootLayout?.addView(failButton)
        windowManager.addView(rootLayout, params)
    }

    private var pulseAnimation: Animation? = null

    fun updateMainButtonStatus(isListening: Boolean) {
        if (isProcessing) return // 로딩 중일 때는 음성 인식 상태 업데이트 무시

        mainButton?.post {
            if (isListening) {
                updateCircleColor(mainButton, Color.parseColor("#FF5252")) // 빨간색으로 변경
                mainButton?.setColorFilter(Color.WHITE)
                
                // 펄스 애니메이션 시작
                pulseAnimation = ScaleAnimation(
                    1.0f, 1.2f, 1.0f, 1.2f,
                    Animation.RELATIVE_TO_SELF, 0.5f,
                    Animation.RELATIVE_TO_SELF, 0.5f
                ).apply {
                    duration = 500
                    repeatMode = Animation.REVERSE
                    repeatCount = Animation.INFINITE
                }
                mainButton?.startAnimation(pulseAnimation)
            } else {
                mainButton?.clearAnimation()
                updateCircleColor(mainButton, Color.parseColor("#FFE000"))
                mainButton?.setColorFilter(Color.BLACK)
            }
        }
    }

    fun updateLoadingStatus(isLoading: Boolean) {
        isProcessing = isLoading
        mainButton?.post {
            if (isLoading) {
                mainButton?.clearAnimation()
                // 중지/취소 느낌을 주기 위해 어두운 회색 배경에 흰색 아이콘으로 변경
                updateCircleColor(mainButton, Color.parseColor("#757575")) 
                mainButton?.setColorFilter(Color.WHITE)
                // 아이콘을 살짝 돌리거나 해서 변화를 줌 (선택사항)
                mainButton?.rotation = 45f 
            } else {
                mainButton?.rotation = 0f
                updateCircleColor(mainButton, Color.parseColor("#FFE000"))
                mainButton?.setColorFilter(Color.BLACK)
            }
        }
    }

    private fun updateCircleColor(view: View?, color: Int) {
        (view?.background as? GradientDrawable)?.setColor(color)
    }

    fun bringToFront() {
        rootLayout?.let {
            try {
                windowManager.removeView(it)
                windowManager.addView(it, params)
            } catch (e: Exception) {
            }
        }
    }

    /**
     * 가이드 세션 중일 때 성공/실패 버튼을 표시한다.
     */
    fun showFeedbackButtons() {
        successButton?.post {
            successButton?.visibility = View.VISIBLE
            failButton?.visibility = View.VISIBLE
        }
    }

    /**
     * 성공/실패 버튼을 숨긴다.
     */
    fun hideFeedbackButtons() {
        successButton?.post {
            successButton?.visibility = View.GONE
            failButton?.visibility = View.GONE
        }
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
        iconRes: Int,
        sizeDp: Int,
        backgroundColor: Int,
        iconTint: Int
    ): ImageView {
        return ImageView(context).apply {
            setImageResource(iconRes)
            setColorFilter(iconTint)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            
            val padding = dp(12) // 패딩을 16에서 12로 줄여 아이콘을 더 크게 보이게 함
            setPadding(padding, padding, padding, padding)
            
            background = createCircleDrawable(backgroundColor)
            
            isClickable = true
            isFocusable = true

            layoutParams = LinearLayout.LayoutParams(
                dp(sizeDp),
                dp(sizeDp)
            )
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
