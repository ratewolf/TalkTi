package kr.ac.kopo.talkti.app.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import kr.ac.kopo.talkti.models.Candidate

/**
 * 범용 후보 선택 오버레이 매니저
 *
 * 후보 목록의 min/max bounds를 계산하여 하나의 전체 영역을 빨간 테두리로 감싸고,
 * 상단에 "원하는 항목을 선택하세요" 배지를 표시한다.
 *
 * FLAG_NOT_TOUCHABLE을 적용하여 터치를 가로채지 않고,
 * 사용자가 실제 앱 리스트를 직접 클릭할 수 있도록 한다.
 */
class CandidateOverlayManager(
    private val context: Context
) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val overlayViews = mutableListOf<View>()

    /**
     * 후보 목록 표시
     */
    fun showCandidates(
        candidates: List<Candidate>,
        onCandidateSelected: (Candidate) -> Unit
    ) {

        clearOverlays()

        Log.d(
            "CandidateOverlay",
            "showCandidates 호출됨. 후보 개수: ${candidates.size}"
        )

        val validCandidates = candidates.filter { c ->
            val b = c.bounds
            b.left < b.right && b.top < b.bottom
        }

        if (validCandidates.isEmpty()) {
            Log.d("CandidateOverlay", "유효한 후보가 없어 표시하지 않습니다.")
            return
        }

        val minLeft = validCandidates.minOf { it.bounds.left }
        val minTop = validCandidates.minOf { it.bounds.top }
        val maxRight = validCandidates.maxOf { it.bounds.right }
        val maxBottom = validCandidates.maxOf { it.bounds.bottom }


        val containerWidth = (maxRight - minLeft).coerceAtLeast(dp(10))
        val containerHeight = (maxBottom - minTop).coerceAtLeast(dp(10))

        val root = FrameLayout(context).apply {
            isClickable = false
            isFocusable = false
        }

        // ────────────────────────────────────────────────
        // 1. 후보 목록 전체 영역 테두리
        // ────────────────────────────────────────────────
        val borderView = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.TRANSPARENT)
                setStroke(dp(3), Color.parseColor("#FF3B30")) // 빨간 테두리
                cornerRadius = dp(8).toFloat()
            }
        }

        val borderParams = FrameLayout.LayoutParams(
            containerWidth,
            containerHeight
        ).apply {
            gravity = Gravity.CENTER
        }

        root.addView(borderView, borderParams)

        // ────────────────────────────────────────────────
        // 3. WindowManager 레이아웃 파라미터 (FLAG_NOT_TOUCHABLE 설정)
        // ────────────────────────────────────────────────
        val params = WindowManager.LayoutParams(
            containerWidth,
            containerHeight,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {

            gravity = Gravity.TOP or Gravity.START

            x = minLeft
            y = minTop

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        try {
            windowManager.addView(root, params)
            overlayViews.add(root)
            Log.d("CandidateOverlay", "전체 후보 영역 강조 오버레이 추가 성공")
        } catch (e: Exception) {
            Log.e("CandidateOverlay", "오버레이 추가 실패: ${e.message}", e)
        }
    }

    /**
     * 모든 오버레이 제거
     */
    fun clearOverlays() {

        overlayViews.forEach { view ->

            try {

                windowManager.removeView(view)

            } catch (e: Exception) {

                Log.e(
                    "CandidateOverlay",
                    "오버레이 제거 실패: ${e.message}"
                )
            }
        }

        overlayViews.clear()

        Log.d(
            "CandidateOverlay",
            "모든 후보 선택 오버레이 제거 완료"
        )
    }

    /**
     * dp → px 변환
     */
    private fun dp(value: Int): Int {
        return (
                value *
                        context.resources.displayMetrics.density
                ).toInt()
    }
}