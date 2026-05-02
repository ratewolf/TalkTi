package kr.ac.kopo.talkti

import kr.ac.kopo.talkti.models.UiElement

expect class ScreenInspector() {
    fun getUiTree(): List<UiElement>
}
