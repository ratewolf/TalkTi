package kr.ac.kopo.talkti.app.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
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
     * 유니코드 원형 숫자 반환
     */
    private fun getCircledNumber(index: Int): String {

        val circledNumbers = arrayOf(
            "①", "②", "③", "④", "⑤",
            "⑥", "⑦", "⑧", "⑨", "⑩",
            "⑪", "⑫", "⑬", "⑭", "⑮",
            "⑯", "⑰", "⑱", "⑲", "⑳"
        )

        return if (index in circledNumbers.indices) {
            circledNumbers[index]
        } else {
            "(${index + 1})"
        }
    }

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

        candidates.forEachIndexed { index, candidate ->

            val bounds = candidate.bounds

            val container = FrameLayout(context).apply {

                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#FFE000"))
                    setStroke(dp(3), Color.BLACK)
                    cornerRadius = dp(8).toFloat()
                }

                isClickable = true
                isFocusable = true
            }

            val textView = TextView(context).apply {

                text =
                    "${getCircledNumber(index)} ${candidate.text}"

                setTextColor(Color.BLACK)
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD

                gravity = Gravity.CENTER

                val padding = dp(6)
                setPadding(
                    padding,
                    padding,
                    padding,
                    padding
                )

                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }

            val childParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply {
                gravity = Gravity.CENTER
            }

            container.addView(
                textView,
                childParams
            )

            container.setOnClickListener {

                Log.d(
                    "CandidateOverlay",
                    "후보 선택: ${candidate.id} - ${candidate.text}"
                )

                clearOverlays()

                onCandidateSelected(candidate)
            }

            val width =
                (bounds.right - bounds.left)
                    .coerceAtLeast(dp(40))

            val height =
                (bounds.bottom - bounds.top)
                    .coerceAtLeast(dp(30))

            val params = WindowManager.LayoutParams(
                width,
                height,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {

                gravity = Gravity.TOP or Gravity.START

                x = bounds.left
                y = bounds.top

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