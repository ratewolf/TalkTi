package kr.ac.kopo.talkti

import kr.ac.kopo.talkti.models.UiElement

actual class ScreenInspector actual constructor() {
    actual fun getUiTree(): List<UiElement> {
        // 안드로이드는 AccessibilityService에서 직접 처리하므로 여기서는 빈 리스트를 반환하거나
        // 필요한 경우 서비스와의 브릿지 로직을 작성합니다.
        return emptyList()
    }
}
