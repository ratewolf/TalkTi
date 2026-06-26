package kr.ac.kopo.talkti

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ImageView
import android.annotation.SuppressLint

@SuppressLint("StaticFieldLeak", "SetTextI18n")
object LlmLoadingOverlay {
    private var overlayView: View? = null
    private var windowManager: WindowManager? = null
    private var animator: ValueAnimator? = null

    val isShowing: Boolean get() = overlayView != null

    fun show(context: Context) {
        // 이미 보여지고 있는 경우 중복 생성 방지
        if (overlayView != null) return

        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // 전체 화면을 덮는 반투명 배경 프레임
        val frameLayout = FrameLayout(context).apply {
            setBackgroundColor(Color.parseColor("#80000000")) // 50% 투명도의 검은색
            // 백그라운드 터치 방지 (작업 흐름 단절 방지)
            isClickable = true
            isFocusable = true
        }

        // 중앙 로딩 카드 레이아웃
        val cardLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FFFDF6")) // 따뜻한 배경색
                cornerRadius = dpToPx(context, 24f)
            }
            val pad = dpToPx(context, 32f).toInt()
            setPadding(pad, pad, pad, pad)
        }

        // 캐릭터 영역 (ImageView)
        val characterImageView = ImageView(context).apply {
            setImageResource(R.drawable.loading_character)
            scaleType = ImageView.ScaleType.FIT_CENTER
            val width = dpToPx(context, 120f).toInt()
            val height = dpToPx(context, 80f).toInt()
            layoutParams = LinearLayout.LayoutParams(width, height).apply {
                bottomMargin = dpToPx(context, 24f).toInt()
            }
        }
        cardLayout.addView(characterImageView)

        // 고령층을 위한 큰 텍스트 메시지
        val messageText = TextView(context).apply {
            text = "어떻게 도와드릴지\n찾는 중이에요"
            textSize = 24f
            setTextColor(Color.parseColor("#202124"))
            gravity = Gravity.CENTER
            setLineSpacing(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, resources.displayMetrics), 1.0f)
            typeface = Typeface.DEFAULT_BOLD
        }
        cardLayout.addView(messageText)

        val cardParams = FrameLayout.LayoutParams(
            dpToPx(context, 320f).toInt(),
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
        }
        frameLayout.addView(cardLayout, cardParams)

        // 점(...)이 움직이는 애니메이션 효과 (스피너 대체)
        animator = ValueAnimator.ofInt(0, 4).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { anim ->
                val dots = ".".repeat(anim.animatedValue as Int)
                messageText.text = "어떻게 도와드릴지\n찾는 중이에요$dots"
            }
        }
        animator?.start()

        overlayView = frameLayout

        // WindowManager 레이아웃 파라미터 (오버레이)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, // 터치는 가로채되 포커스는 뺏지 않음
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        try {
            windowManager?.addView(overlayView, params)
        } catch (e: Exception) {
            e.printStackTrace()
            hide()
        }
    }

    fun hide() {
        animator?.cancel()
        animator = null
        try {
            overlayView?.let {
                windowManager?.removeView(it)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        overlayView = null
        windowManager = null
    }

    private fun dpToPx(context: Context, dp: Float): Float {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, context.resources.displayMetrics)
    }
}
