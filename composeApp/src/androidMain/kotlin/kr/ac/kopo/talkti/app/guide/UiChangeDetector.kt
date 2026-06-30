package kr.ac.kopo.talkti.app.guide

import android.util.Log
import kotlinx.coroutines.*
import kr.ac.kopo.talkti.models.UiElement
import kotlinx.serialization.json.Json

/**
 * UI Tree 변경 감지기.
 *
 * AccessibilityEvent 가 발생할 때마다 화면의 Y좌표 상단 15% 타이틀 변경 여부 및
 * 전체 클릭 가능 요소들의 텍스트 Jaccard 변화율을 비교하여 스크롤과 화면 전환을 구별한다.
 *
 * 화면 전환으로 확인되면 즉시 [onUiChangeDetected]를 호출하여 이전 작업을 취소하고,
 * 400ms 디바운스 대기 후 [onMeaningfulChange]를 호출하여 새 분석을 진행한다.
 */
class UiChangeDetector(
    private val debounceMs: Long = 400L,
    private val getLatestUiTree: (() -> String)? = null
) {
    companion object {
        private const val TAG = "UiChangeDetector"
    }

    /** 이전 clickable 텍스트 집합 */
    private var previousClickableTexts: Set<String> = emptySet()

    /** 이전 헤더 텍스트 집합 */
    private var previousHeaderTexts: Set<String> = emptySet()

    /** 디바운스용 Job */
    private var debounceJob: Job? = null

    /** 변경이 감지되었을 때 콜백 */
    var onMeaningfulChange: (() -> Unit)? = null

    /** 변경이 감지되기 시작한 즉시 호출되는 콜백 (오버레이 조기 제거용) */
    var onUiChangeDetected: (() -> Unit)? = null

    /** 마지막으로 사용된 코루틴 스코프 */
    private var lastScope: CoroutineScope? = null

    /** 현재 분석 중인지 여부 (단순 상태 플래그) */
    var isAnalyzing: Boolean = false

    /** 가이드가 활성 상태인지 외부에서 주입하는 체크 함수 */
    var isGuideActive: (() -> Boolean)? = null

    /** 마지막 클릭 발생 시점 */
    private var lastClickTime: Long = 0L

    /** 앱 실행(런치) 중인지 여부 (앱 로딩 및 1.2초 초기 가이드 대기 시간 동안 이벤트 감지 무시용) */
    var isAppLaunching: Boolean = false

    /**
     * 클릭이 발생했음을 감지기에 알린다.
     */
    fun notifyClick() {
        lastClickTime = System.currentTimeMillis()
        Log.d(TAG, "[디버그] 클릭 발생 기록: lastClickTime=$lastClickTime")
    }

    /**
     * 새 UI Tree JSON을 받아 의미 있는 변경 여부를 판단한다.
     * 변경이 감지되면 디바운싱 후 콜백을 호출한다.
     */
    fun onNewUiTree(
        uiTreeJson: String,
        scope: CoroutineScope,
        eventType: Int,
        screenHeight: Int,
        immediate: Boolean = false
    ) {
        if (isAppLaunching) {
            Log.d(TAG, "[디버그] 앱 실행 중이므로 UI 변경 감지 이벤트를 무시합니다.")
            return
        }
        lastScope = scope

        val elements = try {
            Json.decodeFromString<List<UiElement>>(uiTreeJson)
        } catch (e: Exception) {
            Log.e(TAG, "[디버그] UI 변경 감지: UI Tree JSON 파싱 에러: ${e.message}")
            return
        }

        // 1. TYPE_WINDOW_STATE_CHANGED 이벤트인지 검사 (AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED = 32)
        val isWindowStateChanged = eventType == 32 || immediate

        // 2. 상단 헤더 영역 텍스트 추출 (Y좌표 상단 15% 이내)
        val headerTexts = elements
            .filter { it.visibleToUser && it.bounds.top < screenHeight * 0.15 }
            .map { "${it.text}_${it.contentDescription}".trim() }
            .filter { it.isNotEmpty() }
            .toSet()

        // 3. 클릭 가능한 요소들의 텍스트 추출 (좌표 제외, 오직 텍스트만)
        val clickableTexts = elements
            .filter { it.clickable && it.enabled && it.visibleToUser }
            .map { "${it.text}_${it.contentDescription}".trim() }
            .filter { it.isNotEmpty() }
            .toSet()

        // 4. 화면 전환 여부 결정
        var isScreenTransition = false
        var reason = ""

        if (isWindowStateChanged) {
            isScreenTransition = true
            reason = "WINDOW_STATE_CHANGED (앱 전환 또는 다이얼로그 노출)"
        } else {
            // 상단 헤더 영역 Jaccard 변화율 검사 (미세 변화 필터링)
            val headerChangeRate = computeJaccardDistance(previousHeaderTexts, headerTexts)
            val headerChanged = previousHeaderTexts.isNotEmpty() && headerChangeRate >= 0.20
            if (headerChanged) {
                isScreenTransition = true
                reason = "상단 헤더 변경 감지 (변화율: ${(headerChangeRate * 100).toInt()}%)"
            } else {
                // Jaccard 변화율 검사
                val changeRate = computeJaccardDistance(previousClickableTexts, clickableTexts)
                if (previousClickableTexts.isNotEmpty() && changeRate >= 0.20) {
                    isScreenTransition = true
                    reason = "클릭 가능 요소 변화율 임계값 초과 (변화율: ${(changeRate * 100).toInt()}%)"
                }
            }
        }

        // 최초 실행 시에는 화면 전환으로 인정하되 즉시 실행
        val isFirstRun = previousClickableTexts.isEmpty() && previousHeaderTexts.isEmpty()

        // 이전 상태 업데이트
        previousHeaderTexts = headerTexts
        previousClickableTexts = clickableTexts

        if (!isScreenTransition && !isFirstRun) {
            Log.d(TAG, "[디버그] UI 변경 감지: 단순 변경(스크롤 등)으로 판단하여 무시합니다.")
            return
        }

        Log.d(TAG, "[디버그] UI 변경 감지: 의미 있는 화면 전환 감지! 사유: $reason")

        // 화면 전환이 확정되었으므로 즉시 기존 오버레이 제거 및 활성 분석 취소
        onUiChangeDetected?.invoke()

        val timeSinceClick = System.currentTimeMillis() - lastClickTime
        val actualDebounceMs = if (timeSinceClick < 1500L) {
            1000L // 클릭 직후 과도기에는 정착을 위해 1000ms 추가 디바운스 대기
        } else {
            debounceMs
        }

        debounceJob?.cancel()
        if (immediate || isFirstRun) {
            Log.d(TAG, "[디버그] UI 변경 감지: 즉시 실행 — onMeaningfulChange 호출")
            onMeaningfulChange?.invoke()
        } else {
            debounceJob = scope.launch {
                // 클릭 직후 과도기에는 로딩바가 화면에 올라오기까지 최소 시간(500ms) 선지연
                if (timeSinceClick < 1500L) {
                    Log.d(TAG, "[디버그] 클릭 후 과도기 감지 -> 500ms 선지연 적용 (timeSinceClick=${timeSinceClick}ms)")
                    delay(500)
                }

                var currentElements = elements
                var attempts = 0
                while (attempts < 10) { // 최대 3초 (10 * 300ms) 대기
                    val hasProgress = currentElements.any { 
                        it.visibleToUser && (
                            it.className.contains("ProgressBar", ignoreCase = true) || 
                            it.className.contains("Progress", ignoreCase = true) || 
                            it.id.contains("progress", ignoreCase = true) ||
                            it.text.contains("경로를 찾고", ignoreCase = true) ||
                            it.text.contains("로딩", ignoreCase = true)
                        )
                    }
                    if (!hasProgress) {
                        break
                    }
                    Log.d(TAG, "[디버그] 화면에 로딩바 감지됨 -> 300ms 추가 대기 (시도: ${attempts + 1}/10)")
                    delay(300)
                    
                    val latestUiTree = getLatestUiTree?.invoke()
                    if (latestUiTree != null) {
                        currentElements = try {
                            Json.decodeFromString<List<UiElement>>(latestUiTree)
                        } catch (e: Exception) {
                            currentElements
                        }
                    }
                    attempts++
                }

                delay(actualDebounceMs)
                // 가이드가 이미 종료됐으면 분석 호출 차단
                if (isGuideActive?.invoke() == false) {
                    Log.d(TAG, "[디버그] debounce 완료 시점에 가이드 비활성 확인 → onMeaningfulChange 호출 차단")
                    return@launch
                }
                Log.d(TAG, "[디버그] UI 변경 감지: 디바운싱 완료 (대기시간: ${actualDebounceMs}ms) — onMeaningfulChange 호출")
                onMeaningfulChange?.invoke()
            }
        }
    }

    private fun computeJaccardDistance(setA: Set<String>, setB: Set<String>): Double {
        if (setA.isEmpty() && setB.isEmpty()) return 0.0
        if (setA.isEmpty() || setB.isEmpty()) return 1.0
        val intersection = setA.intersect(setB).size
        val union = setA.union(setB).size
        return 1.0 - (intersection.toDouble() / union.toDouble())
    }

    /**
     * 상태를 초기화한다.
     * 가이드 시작 시 호출하여 최초 UI를 "새로운 것"으로 인식하게 한다.
     */
    fun reset() {
        Log.d(TAG, "[디버그] UiChangeDetector 초기화 (reset)")
        previousClickableTexts = emptySet()
        previousHeaderTexts = emptySet()
        isAnalyzing = false
        isAppLaunching = false
        lastClickTime = 0L
        lastScope = null
        debounceJob?.cancel()
        debounceJob = null
    }

    /**
     * 현재 화면을 기준으로 Baseline을 강제 업데이트한다.
     * 이후 발생하는 UI 변경은 이 시점의 화면과 비교된다.
     */
    fun updateBaseline(uiTreeJson: String, screenHeight: Int) {
        val elements = try {
            Json.decodeFromString<List<UiElement>>(uiTreeJson)
        } catch (e: Exception) {
            return
        }

        previousHeaderTexts = elements
            .filter { it.visibleToUser && it.bounds.top < screenHeight * 0.15 }
            .map { "${it.text}_${it.contentDescription}".trim() }
            .filter { it.isNotEmpty() }
            .toSet()

        previousClickableTexts = elements
            .filter { it.clickable && it.enabled && it.visibleToUser }
            .map { "${it.text}_${it.contentDescription}".trim() }
            .filter { it.isNotEmpty() }
            .toSet()

        Log.d(TAG, "[디버그] UI Baseline 강제 업데이트 완료 (header=${previousHeaderTexts.size}, clickable=${previousClickableTexts.size})")
    }

    /**
     * 리소스 정리.
     */
    fun destroy() {
        debounceJob?.cancel()
        debounceJob = null
        onMeaningfulChange = null
        onUiChangeDetected = null
        lastScope = null
    }
}
