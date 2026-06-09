package kr.ac.kopo.talkti.app.errorhandling

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * 예외 처리 #1: 팝업 및 광고 감지 → 자동 닫기 (Auto-Close 방어막)
 *
 * 팝업이 감지되면 다음 우선순위에 따라 닫기를 시도합니다:
 *   1순위 - 명시적 텍스트: "닫기", "건너뛰기", "X" 등의 텍스트를 가진 클릭 가능 노드
 *   2순위 - 이미지 X 버튼: 텍스트 없이 클릭 가능한 ImageView/ImageButton (팝업 우측 상단)
 *   3순위 - 수동 닫기 유도: 버튼을 도저히 찾을 수 없을 때 (오류 방지)
 */
class PopupDetector {

    sealed class PopupResult {
        object NoPopup : PopupResult()
        data class RequireManualClose(val popupRect: Rect, val isExactButton: Boolean) : PopupResult()
    }

    companion object {
        private const val TAG = "PopupDetector"

        /**
         * 팝업 닫기 버튼에서 흔히 나타나는 텍스트 키워드 목록
         */
        private val CLOSE_KEYWORDS = listOf(
            "닫기", "close", "X", "x", "×",
            "건너뛰기", "skip",
            "오늘 하루 보지 않기", "오늘 보지 않기", "오늘 그만 보기",
            "다음에 하기", "나중에", "괜찮습니다",
            "아니요", "아니오", "취소",
            "다시 보지 않기", "7일간 보지 않기", "30일간 보지 않기",
            "광고 닫기", "SKIP", "Skip"
        )

        /**
         * 팝업/다이얼로그로 의심되는 Window 타입 목록
         */
        private val POPUP_WINDOW_TYPES = setOf(
            AccessibilityWindowInfo.TYPE_APPLICATION,
            AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
        )
    }

    /**
     * 현재 화면에서 팝업을 감지하고 자동으로 닫기를 시도합니다.
     *
     * @param service 접근성 서비스 인스턴스 (windows 접근용)
     * @return PopupResult (처리 결과)
     */
    fun detectAndClosePopup(service: AccessibilityService): PopupResult {
        val allWindows = service.windows ?: return PopupResult.NoPopup

        // 활성 윈도우(메인 앱)보다 위에 떠 있는 팝업성 윈도우를 탐색
        val popupWindows = findPopupWindows(allWindows)
        if (popupWindows.isEmpty()) return PopupResult.NoPopup

        Log.d(TAG, "팝업성 윈도우 ${popupWindows.size}개 감지됨")

        for (popupWindow in popupWindows) {
            val rootNode = popupWindow.root ?: continue

            // 1순위: 텍스트 기반 닫기 버튼 탐색
            val textCloseNode = findCloseButtonByText(rootNode)
            if (textCloseNode != null) {
                val rect = Rect()
                textCloseNode.getBoundsInScreen(rect)
                Log.d(TAG, "텍스트 기반 닫기 버튼 감지됨 -> 수동 제어 유도: ${textCloseNode.text ?: textCloseNode.contentDescription}")
                return PopupResult.RequireManualClose(rect, isExactButton = true)
            }

            // 2순위: 이미지(X 아이콘) 기반 닫기 버튼 탐색
            val imageCloseNode = findCloseButtonByImage(rootNode, popupWindow)
            if (imageCloseNode != null) {
                val rect = Rect()
                imageCloseNode.getBoundsInScreen(rect)
                Log.d(TAG, "이미지 기반 닫기 버튼 감지됨 -> 수동 제어 유도")
                return PopupResult.RequireManualClose(rect, isExactButton = true)
            }

            // 닫기 버튼이 명확하지 않은 작은 윈도우는 정상적인 UI(예: 플로팅 버튼, 하단 탭)일 확률이 높으므로 
            // 팝업으로 간주하여 강제로 차단하지 않고 무시합니다.
        }

        return PopupResult.NoPopup
    }

    /**
     * 전체 윈도우 목록에서 팝업/다이얼로그로 의심되는 윈도우를 필터링합니다.
     *
     * 판별 기준:
     * - 활성(focused) 상태가 아닌 상위 레이어 윈도우
     * - 전체 화면보다 작은 크기의 윈도우 (모달 다이얼로그)
     * - layer 값이 높은 윈도우 (메인 앱 위에 떠 있는 오버레이)
     */
    private fun findPopupWindows(allWindows: List<AccessibilityWindowInfo>): List<AccessibilityWindowInfo> {
        val result = mutableListOf<AccessibilityWindowInfo>()

        // 가장 큰 윈도우(메인 앱)의 크기를 기준으로 삼음
        var mainWindowArea = 0L
        for (w in allWindows) {
            val rect = Rect()
            w.getBoundsInScreen(rect)
            val area = (rect.width().toLong()) * rect.height().toLong()
            if (area > mainWindowArea) {
                mainWindowArea = area
            }
        }

        for (window in allWindows) {
            val root = window.root ?: continue

            // 키보드(입력창), 시스템 UI(내비게이션바, 상태바), 똑띠 자체 오버레이는 팝업 검사에서 제외
            if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
            if (window.type == AccessibilityWindowInfo.TYPE_SYSTEM) continue
            
            val pkgName = root.packageName?.toString() ?: ""
            if (pkgName.contains("kr.ac.kopo.talkti")) continue
            if (pkgName.contains("com.android.systemui")) continue

            val windowRect = Rect()
            window.getBoundsInScreen(windowRect)
            val windowArea = windowRect.width().toLong() * windowRect.height().toLong()

            // 메인 윈도우보다 작은 윈도우 = 팝업/다이얼로그일 가능성 높음
            val isSmaller = mainWindowArea > 0 && windowArea < mainWindowArea * 0.95

            // 다이얼로그 내부에 닫기 관련 텍스트가 있으면 팝업으로 확정
            val hasCloseHint = hasAnyCloseKeyword(root)

            if (isSmaller || hasCloseHint) {
                result.add(window)
                Log.d(TAG, "팝업 후보: pkg=$pkgName, area=$windowArea (메인=$mainWindowArea), hasCloseHint=$hasCloseHint")
            }
        }

        return result
    }

    /**
     * 1순위: 노드 트리에서 닫기 관련 텍스트를 가진 클릭 가능 노드를 탐색합니다.
     */
    private fun findCloseButtonByText(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = mutableListOf(root)
        var bestMatch: AccessibilityNodeInfo? = null

        while (queue.isNotEmpty()) {
            val node = queue.removeAt(0)

            val nodeText = node.text?.toString()?.trim() ?: ""
            val nodeDesc = node.contentDescription?.toString()?.trim() ?: ""
            val combinedText = "$nodeText $nodeDesc".lowercase()

            for (keyword in CLOSE_KEYWORDS) {
                if (combinedText.contains(keyword.lowercase())) {
                    // 클릭 가능한 노드를 찾거나, 부모 중 클릭 가능한 노드를 찾음
                    val clickable = findClickableAncestor(node)
                    if (clickable != null) {
                        Log.d(TAG, "닫기 텍스트 발견: '$combinedText' → keyword='$keyword'")
                        // "오늘 하루 보지 않기" 같은 더 구체적인 텍스트를 우선시
                        if (bestMatch == null || keyword.length > 2) {
                            bestMatch = clickable
                        }
                    }
                    break
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }

        return bestMatch
    }

    /**
     * 2순위: 텍스트 없이 클릭 가능한 ImageView/ImageButton을 팝업 우측 상단에서 탐색합니다.
     * "X" 아이콘은 보통 팝업의 우측 상단 영역에 위치합니다.
     */
    private fun findCloseButtonByImage(root: AccessibilityNodeInfo, window: AccessibilityWindowInfo): AccessibilityNodeInfo? {
        val windowRect = Rect()
        window.getBoundsInScreen(windowRect)

        // 팝업의 우측 상단 1/4 영역을 X 버튼 존재 가능 구역으로 설정
        val targetRight = windowRect.right
        val targetLeft = windowRect.left + (windowRect.width() * 0.5).toInt()
        val targetTop = windowRect.top
        val targetBottom = windowRect.top + (windowRect.height() * 0.35).toInt()

        val candidates = mutableListOf<AccessibilityNodeInfo>()
        val queue = mutableListOf(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeAt(0)
            val className = node.className?.toString() ?: ""

            // ImageView 또는 ImageButton이면서 클릭 가능
            val isImageWidget = className.contains("ImageView") || className.contains("ImageButton")
            val hasNoText = node.text.isNullOrBlank() && node.contentDescription.isNullOrBlank()

            if (isImageWidget && hasNoText) {
                val clickable = findClickableAncestor(node) ?: if (node.isClickable) node else null
                if (clickable != null) {
                    val nodeRect = Rect()
                    clickable.getBoundsInScreen(nodeRect)

                    // 팝업 우측 상단 영역에 위치하는지 확인
                    if (nodeRect.left >= targetLeft && nodeRect.right <= targetRight + 20 &&
                        nodeRect.top >= targetTop && nodeRect.bottom <= targetBottom
                    ) {
                        candidates.add(clickable)
                    }
                }
            }

            // contentDescription이 "닫기", "close" 등인 이미지도 포함
            if (isImageWidget && !node.contentDescription.isNullOrBlank()) {
                val desc = node.contentDescription.toString().lowercase()
                if (desc.contains("닫기") || desc.contains("close") || desc == "x" || desc == "×") {
                    val clickable = findClickableAncestor(node)
                    if (clickable != null) return clickable
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }

        // 가장 우측 상단에 가까운 후보를 반환
        return candidates.minByOrNull { node ->
            val rect = Rect()
            node.getBoundsInScreen(rect)
            rect.top + (windowRect.right - rect.right)
        }
    }

    /**
     * 노드 트리 내에 닫기 관련 키워드가 하나라도 있는지 빠르게 검사합니다.
     */
    private fun hasAnyCloseKeyword(root: AccessibilityNodeInfo): Boolean {
        val queue = mutableListOf(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeAt(0)
            val text = "${node.text ?: ""} ${node.contentDescription ?: ""}".lowercase()
            if (CLOSE_KEYWORDS.any { text.contains(it.lowercase()) }) return true
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return false
    }

    /**
     * 해당 노드 또는 가장 가까운 클릭 가능한 부모 노드를 반환합니다.
     */
    private fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) return current
            current = current.parent
        }
        return null
    }
}
