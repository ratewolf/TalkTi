package kr.ac.kopo.talkti.app.guide

import android.util.Log
import kotlinx.coroutines.*
import kr.ac.kopo.talkti.models.UiElement
import kotlinx.serialization.json.Json

/**
 * UI Tree 변경 감지기.
 *
 * AccessibilityEvent 가 발생할 때마다 현재 UI Tree 의 "의미 있는 해시"를 계산하고,
 * 이전 해시와 다를 때만 [onMeaningfulChange] 를 호출한다.
 *
 * 의미 있는 변경의 기준:
 *  - clickable 요소의 개수가 달라졌거나
 *  - clickable 요소의 텍스트 집합이 달라졌을 때
 *
 * 디바운싱(800ms)을 적용하여 빠른 연속 이벤트를 하나로 묶는다.
 */
class UiChangeDetector(
    private val debounceMs: Long = 800L
) {
    companion object {
        private const val TAG = "UiChangeDetector"
    }

    /** 이전 해시값 */
    private var previousHash: String = ""

    /** 이전 clickable 텍스트 집합 */
    private var previousClickableTexts: Set<String> = emptySet()

    /** 디바운스용 Job */
    private var debounceJob: Job? = null

    /** 변경이 감지되었을 때 콜백 */
    var onMeaningfulChange: ((uiTreeJson: String) -> Unit)? = null

    /** 현재 분석 중인지 여부 — 서버 응답 대기 중에는 중복 요청을 방지 */
    var isAnalyzing: Boolean = false

    /**
     * 새 UI Tree JSON을 받아 의미 있는 변경 여부를 판단한다.
     * 변경이 감지되면 디바운싱 후 콜백을 호출한다.
     *
     * @param uiTreeJson extractScreenTree()의 결과물
     * @param scope 디바운스 코루틴이 실행될 스코프
     */
    fun onNewUiTree(uiTreeJson: String, scope: CoroutineScope) {
        // 서버 분석 대기 중이면 스킵
        if (isAnalyzing) {
            Log.d(TAG, "[디버그] UI 변경 감지: 서버 응답 대기(isAnalyzing=true) 상태로 이벤트를 스킵합니다.")
            return
        }

        val elements = try {
            Json.decodeFromString<List<UiElement>>(uiTreeJson)
        } catch (e: Exception) {
            Log.e(TAG, "[디버그] UI 변경 감지: UI Tree JSON 파싱 에러: ${e.message}")
            return
        }

        val clickableTexts = extractClickableTexts(elements)
        val currentHash = computeHash(clickableTexts)

        // 해시가 동일하면 의미 있는 변경 없음
        if (currentHash == previousHash) {
            Log.d(TAG, "[디버그] UI 변경 감지: 변경 없음 (해시가 이전과 동일: $currentHash)")
            return
        }

        Log.d(TAG, "[디버그] UI 변경 감지: 의미 있는 UI 변경 발생! clickable 텍스트 개수: ${previousClickableTexts.size}개 → ${clickableTexts.size}개 (디바운싱 대기 시작)")

        previousHash = currentHash
        previousClickableTexts = clickableTexts

        // 디바운싱: 이전 대기 중인 호출 취소 후 새로 예약
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(debounceMs)
            Log.d(TAG, "[디버그] UI 변경 감지: 디바운싱 완료 — onMeaningfulChange 호출")
            onMeaningfulChange?.invoke(uiTreeJson)
        }
    }

    /**
     * 상태를 초기화한다.
     * 가이드 시작 시 호출하여 최초 UI를 "새로운 것"으로 인식하게 한다.
     */
    fun reset() {
        Log.d(TAG, "[디버그] UiChangeDetector 초기화 (reset)")
        previousHash = ""
        previousClickableTexts = emptySet()
        isAnalyzing = false
        debounceJob?.cancel()
        debounceJob = null
    }

    /**
     * 리소스 정리.
     */
    fun destroy() {
        debounceJob?.cancel()
        debounceJob = null
        onMeaningfulChange = null
    }

    /**
     * clickable + enabled + visibleToUser 요소의 텍스트와 좌표 정보를 추출한다.
     */
    private fun extractClickableTexts(elements: List<UiElement>): Set<String> {
        return elements
            .filter { it.clickable && it.enabled && it.visibleToUser }
            .map { element ->
                val text = element.text.trim()
                val contentDesc = element.contentDescription.trim()
                val bounds = element.bounds
                "${text}_${contentDesc}_${bounds.left}_${bounds.top}_${bounds.right}_${bounds.bottom}"
            }
            .toSet()
    }

    /**
     * clickable 텍스트 집합으로부터 해시값을 계산한다.
     */
    private fun computeHash(texts: Set<String>): String {
        return texts.sorted().joinToString("|").hashCode().toString()
    }
}
