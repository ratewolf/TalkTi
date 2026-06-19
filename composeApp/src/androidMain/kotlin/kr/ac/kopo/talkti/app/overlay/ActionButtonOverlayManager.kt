package kr.ac.kopo.talkti.app.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import kr.ac.kopo.talkti.models.RectDto

/**
 * 범용 액션 버튼 오버레이 매니저 (ActionButtonOverlayManager)
 *
 * 특정 앱의 도메인(도착, 호출, 전송 등)을 알지 못하며,
 * 상위 서비스로부터 [bounds]와 [label]만 전달받아
 * 해당 위치에 터치 관통형(FLAG_NOT_TOUCHABLE) 고대비 강조 테두리와
 * 사용자에게 터치를 유도하는 가이드 배지를 표시하는 순수 UI 컴포넌트입니다.
 *
 * 특정 버튼 텍스트 탐색 로직 없음
 * performAction / AccessibilityNodeInfo 클릭 없음
 * TTS 처리 없음
 * 상위 서비스가 결정한 위치와 라벨만 받아 시각화
 */
class ActionButtonOverlayManager(
    private val context: Context
) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    /**
     * 현재 화면에 표시된 오버레이 루트 뷰.
     * null이면 오버레이가 없는 상태입니다.
     */
    private var overlayRootView: View? = null

    /**
     * 지정된 [bounds] 위치에 터치 관통형 고대비 강조 테두리와
     * "[label] 누르기" 가이드 배지를 표시합니다.
     *
     * 기존에 오버레이가 표시 중이면 먼저 제거하고 새로 렌더링합니다.
     *
     * @param bounds 버튼의 화면상 좌표 (스크린 절대 좌표 기준)
     * @param label 배지에 표시할 버튼 이름
     *              (예: "도착", "호출", "전송")
     */
    fun showActionButtonHighlight(
        bounds: RectDto,
        label: String,
        onActionSelected: (() -> Unit)? = null
    ) {

        clearHighlight()

        Log.d(
            "ActionButtonOverlay",
            "showActionButtonHighlight 호출: label=$label, bounds=$bounds"
        )

        // ────────────────────────────────────────────────
        // 1. 루트 컨테이너
        // ────────────────────────────────────────────────


        val buttonWidth =
            (bounds.right - bounds.left)
                .coerceAtLeast(dp(60))

        val buttonHeight =
            (bounds.bottom - bounds.top)
                .coerceAtLeast(dp(36))

        val root = FrameLayout(context).apply {
            isFocusable = false
        }

        // ────────────────────────────────────────────────
        // 2. 버튼 강조 테두리
        // ────────────────────────────────────────────────

        val borderView = View(context).apply {
            background = createBorderDrawable()
        }

        val borderParams = FrameLayout.LayoutParams(
            buttonWidth,
            buttonHeight
        ).apply {
            gravity = Gravity.CENTER
        }

        root.addView(
            borderView,
            borderParams
        )

        // ────────────────────────────────────────────────
        // 4. WindowManager.LayoutParams (터치 관통을 위해 FLAG_NOT_TOUCHABLE 설정)
        // ────────────────────────────────────────────────

        val params = WindowManager.LayoutParams(
            buttonWidth,
            buttonHeight,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {

            gravity = Gravity.TOP or Gravity.START

            // 접근성 API의 getBoundsInScreen()은 상태바 포함 절대 좌표를 반환한다.
            // TYPE_ACCESSIBILITY_OVERLAY + FLAG_LAYOUT_IN_SCREEN 조합에서는
            // WindowManager의 (x, y)도 상태바 포함 절대 좌표로 해석되므로 그대로 사용.
            val statusBarHeight = getStatusBarHeight()
            Log.d("ActionButtonOverlay", "상태바 높이: ${statusBarHeight}px, 입력 bounds=(${bounds.left}, ${bounds.top}, ${bounds.right}, ${bounds.bottom})")

            x = bounds.left
            y = bounds.top

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        // ────────────────────────────────────────────────
        // 5. WindowManager 등록
        // ────────────────────────────────────────────────

        try {

            windowManager.addView(
                root,
                params
            )

            overlayRootView = root

            Log.d(
                "ActionButtonOverlay",
                "오버레이 추가 성공: label=$label"
            )

        } catch (e: Exception) {

            Log.e(
                "ActionButtonOverlay",
                "오버레이 추가 실패: ${e.message}",
                e
            )
        }
    }

    /**
     * 현재 화면에 표시된 액션 버튼 가이드 오버레이를 제거합니다.
     *
     * 서비스 종료, 새 오버레이 렌더링,
     * 취소 등 정리 목적으로만 호출합니다.
     */
    fun clearHighlight() {

        overlayRootView?.let { view ->

            try {

                windowManager.removeView(view)

                Log.d(
                    "ActionButtonOverlay",
                    "오버레이 제거 완료"
                )

            } catch (e: Exception) {

                Log.e(
                    "ActionButtonOverlay",
                    "오버레이 제거 실패: ${e.message}"
                )
            }
        }

        overlayRootView = null
    }

    // ────────────────────────────────────────────────────────
    // Private Helpers
    // ────────────────────────────────────────────────────────

    /**
     * 버튼 강조 테두리용 Drawable
     *
     * 내부: 완전 투명
     * 외곽: 4dp 빨간색 + 1dp 검은색
     */
    private fun createBorderDrawable(): LayerDrawable {

        val outer = GradientDrawable().apply {

            shape = GradientDrawable.RECTANGLE

            setColor(Color.TRANSPARENT)

            setStroke(
                dp(5),
                Color.BLACK
            )

            cornerRadius = dp(6).toFloat()
        }

        val inner = GradientDrawable().apply {

            shape = GradientDrawable.RECTANGLE

            setColor(Color.TRANSPARENT)

            setStroke(
                dp(4),
                Color.parseColor("#FF3B30")
            )

            cornerRadius = dp(6).toFloat()
        }

        return LayerDrawable(
            arrayOf(
                outer,
                inner
            )
        ).apply {
            setLayerInset(
                1,
                dp(1),
                dp(1),
                dp(1),
                dp(1)
            )
        }
    }

    /**
     * 가이드 배지용 Drawable
     *
     * 100% 불투명 빨간색 배경
     * 검은색 1dp 테두리
     * 둥근 모서리 6dp
     */
    private fun createBadgeDrawable(): GradientDrawable {

        return GradientDrawable().apply {

            shape = GradientDrawable.RECTANGLE

            setColor(
                Color.parseColor("#FF3B30")
            )

            setStroke(
                dp(1),
                Color.BLACK
            )

            cornerRadius = dp(6).toFloat()
        }
    }

    /**
     * DP → PX 변환
     */
    private fun dp(value: Int): Int {
        return (
                value *
                        context.resources.displayMetrics.density
                ).toInt()
    }

    /**
     * 상태바 높이(픽셀) 반환
     * 모드별로 상태바 크기가 다르므로 리소스에서 실제 값을 계산한다.
     */
    private fun getStatusBarHeight(): Int {
        val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) context.resources.getDimensionPixelSize(resourceId) else 0
    }
}