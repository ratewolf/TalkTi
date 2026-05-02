package kr.ac.kopo.talkti

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "TalkTi",
    ) {
        App(
            onStartSetupClick = {
                // 데스크톱에서는 안드로이드 접근성 설정 대신 다른 알림이나 설정을 띄울 수 있습니다.
                println("Desktop: Setup button clicked")
            }
        )
    }
}
