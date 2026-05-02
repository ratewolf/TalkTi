package kr.ac.kopo.talkti

import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    val screenInspector = remember { ScreenInspector() }
    
    Window(
        onCloseRequest = ::exitApplication,
        title = "TalkTi Desktop",
    ) {
        App(
            onStartSetupClick = {
                println("리눅스 UI 트리 추출 시작...")
                val tree = screenInspector.getUiTree()
                if (tree.isEmpty()) {
                    println("UI 요소를 찾지 못했습니다. (AT-SPI 활성화 여부를 확인하세요)")
                } else {
                    tree.forEach { element ->
                        // 통일된 모델의 className(Linux의 경우 Role)을 출력
                        println("[${element.className}] ${element.text} (clickable: ${element.clickable})")
                        println("  Bounds: ${element.bounds}")
                    }
                    println("총 ${tree.size}개의 요소를 발견했습니다.")
                }
            }
        )
    }
}
