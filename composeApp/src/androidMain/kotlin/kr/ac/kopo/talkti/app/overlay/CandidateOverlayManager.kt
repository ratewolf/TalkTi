package kr.ac.kopo.talkti.app.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import kr.ac.kopo.talkti.models.Candidate
import kr.ac.kopo.talkti.models.RectDto

/**
 * 범용 후보 선택 오버레이 매니저
 *
 * 특정 앱의 비즈니스 로직(목적지, 경로, 채팅 등)에 종속되지 않고
 * 화면의 Candidate 리스트를 받아 물리 터치 가능한 오버레이를 표시한다.
 *
 * 사용자가 특정 오버레이를 터치하면
 * 모든 오버레이를 제거하고 선택된 Candidate를 콜백으로 반환한다.
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

        val arrowHeight = dp(12)
        val spacing = dp(4)
        val totalOffset = arrowHeight + spacing
        val color = Color.parseColor("#FF3B30")

        candidates.forEach { candidate ->
            val bounds = candidate.bounds

            val boxWidth = (bounds.right - bounds.left).coerceAtLeast(dp(40))
            val boxHeight = (bounds.bottom - bounds.top).coerceAtLeast(dp(30))
            val showArrowAbove = bounds.top - totalOffset >= 0

            // Container frame (Clickable)
            val container = FrameLayout(context).apply {
                isClickable = true
                isFocusable = true
            }

            // 1. Box Highlight View (Semi-transparent background + Solid Stroke)
            val boxView = View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    val alpha = 38 // ~15% alpha
                    setColor(Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color)))
                    setStroke(dp(3), color)
                    cornerRadius = dp(6).toFloat()
                }
            }

            val boxParams = FrameLayout.LayoutParams(boxWidth, boxHeight).apply {
                gravity = if (showArrowAbove) Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL else Gravity.TOP or Gravity.CENTER_HORIZONTAL
            }
            container.addView(boxView, boxParams)

            // 2. Arrow View (pointing down if above, pointing up if below)
            val arrowView = ArrowView(context, color, pointingDown = showArrowAbove)
            val arrowParams = FrameLayout.LayoutParams(dp(16), arrowHeight).apply {
                gravity = if (showArrowAbove) Gravity.TOP or Gravity.CENTER_HORIZONTAL else Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            }
            container.addView(arrowView, arrowParams)

            // Click listener
            container.setOnClickListener {
                Log.d(
                    "CandidateOverlay",
                    "후보 선택: ${candidate.id} - ${candidate.text}"
                )
                clearOverlays()
                onCandidateSelected(candidate)
            }

            // Window parameters
            val windowY = if (showArrowAbove) bounds.top - totalOffset else bounds.top
            val windowHeight = boxHeight + totalOffset

            val params = WindowManager.LayoutParams(
                boxWidth,
                windowHeight,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = bounds.left
                y = windowY

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }

            try {
                windowManager.addView(
                    container,
                    params
                )
                overlayViews.add(container)
            } catch (e: Exception) {
                Log.e(
                    "CandidateOverlay",
                    "오버레이 추가 실패: ${e.message}",
                    e
                )
            }
        }
    }

    /**
     * [NEW] 범용 다중 후보 강조 (터치 불가능한 패시브 하이라이트)
     */
    fun showMultipleCandidates(
        boundsList: List<RectDto>,
        color: Int = Color.parseColor("#FF3B30"),
        showArrows: Boolean = true
    ) {
        clearOverlays()
        Log.d("CandidateOverlay", "showMultipleCandidates 호출됨. 대상 개수: ${boundsList.size}")

        val arrowHeight = dp(12)
        val spacing = dp(4)
        val totalOffset = if (showArrows) arrowHeight + spacing else 0

        boundsList.forEach { bounds ->
            val boxWidth = (bounds.right - bounds.left).coerceAtLeast(dp(40))
            val boxHeight = (bounds.bottom - bounds.top).coerceAtLeast(dp(20))
            val showArrowAbove = bounds.top - totalOffset >= 0

            val container = FrameLayout(context).apply {
                isClickable = false
                isFocusable = false
            }

            val boxView = View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    val alpha = 38 // ~15% alpha
                    setColor(Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color)))
                    setStroke(dp(3), color)
                    cornerRadius = dp(6).toFloat()
                }
            }

            val boxParams = FrameLayout.LayoutParams(boxWidth, boxHeight).apply {
                gravity = if (showArrowAbove && showArrows) Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL else Gravity.TOP or Gravity.CENTER_HORIZONTAL
            }
            container.addView(boxView, boxParams)

            if (showArrows) {
                val arrowView = ArrowView(context, color, pointingDown = showArrowAbove)
                val arrowParams = FrameLayout.LayoutParams(dp(16), arrowHeight).apply {
                    gravity = if (showArrowAbove) Gravity.TOP or Gravity.CENTER_HORIZONTAL else Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                }
                container.addView(arrowView, arrowParams)
            }

            val windowY = if (showArrowAbove && showArrows) bounds.top - totalOffset else bounds.top
            val windowHeight = boxHeight + totalOffset

            val params = WindowManager.LayoutParams(
                boxWidth,
                windowHeight,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = bounds.left
                y = windowY

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }

            try {
                windowManager.addView(container, params)
                overlayViews.add(container)
            } catch (e: Exception) {
                Log.e("CandidateOverlay", "오버레이 추가 실패: ${e.message}", e)
            }
        }
    }

    /**
     * [NEW] 다중 후보 하이라이트 동적 업데이트
     */
    fun updateOverlayTargets(
        boundsList: List<RectDto>,
        color: Int = Color.parseColor("#FF3B30"),
        showArrows: Boolean = true
    ) {
        showMultipleCandidates(boundsList, color, showArrows)
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

    private class ArrowView(
        context: Context,
        private val color: Int,
        private val pointingDown: Boolean
    ) : View(context) {
        private val paint = android.graphics.Paint().apply {
            this.color = this@ArrowView.color
            style = android.graphics.Paint.Style.FILL
            isAntiAlias = true
        }
        private val path = android.graphics.Path()

        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            path.reset()
            if (pointingDown) {
                path.moveTo(0f, 0f)
                path.lineTo(width.toFloat(), 0f)
                path.lineTo(width.toFloat() / 2, height.toFloat())
            } else {
                path.moveTo(width.toFloat() / 2, 0f)
                path.lineTo(0f, height.toFloat())
                path.lineTo(width.toFloat(), height.toFloat())
            }
            path.close()
            canvas.drawPath(path, paint)
        }
    }
}
