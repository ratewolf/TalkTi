package kr.ac.kopo.talkti.app.guide

import android.speech.tts.TextToSpeech
import android.os.Bundle
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.*
import kr.ac.kopo.talkti.TalkTiAccessibilityService
import kr.ac.kopo.talkti.models.*
import kr.ac.kopo.talkti.app.overlay.CandidateOverlayManager
import kr.ac.kopo.talkti.app.overlay.ActionButtonOverlayManager

/**
 * 가이드 오케스트레이터.
 *
 * UI Tree 변경 감지 → 서버 전송 → LLM 분석 → 오버레이/TTS 실행을 중앙에서 조율한다.
 *
 * 기존 Rule 기반 로직(startSelectionFlow, showAutoDestinationCandidates)은
 * TalkTiAccessibilityService 에 fallback 으로 남겨두고,
 * 이 오케스트레이터가 LLM 기반 가이드를 우선 실행한다.
 */
class GuideOrchestrator(
    private val client: HttpClient,
    private val candidateOverlayManager: CandidateOverlayManager,
    private val actionButtonOverlayManager: ActionButtonOverlayManager
) {
    companion object {
        private const val TAG = "GuideOrchestrator"
    }

    /** 현재 가이드 상태 */
    var currentState: GuideState = GuideState.IDLE
        private set

    /** 분석 상태 변경 콜백 */
    var onAnalyzeStateChanged: ((Boolean) -> Unit)? = null

    /** 가이드 활성화 상태 관리 플래그 */
    private var guideEnabled: Boolean = false

    /** 가이드가 활성화되어 있는지 여부 */
    val isActive: Boolean get() = guideEnabled

    /** 가이드 종료 예약 여부 (TTS 출력 완료 후 stopGuide 호출용) */
    var isPendingStop: Boolean = false
        private set

    /** 가이드 실행 세대 관리 (중복/이전 응답 방어) */
    private var guideGeneration: Int = 0

    /** 사용자의 원본 요청 */
    private var userCommand: String = ""

    /** 현재 포그라운드 앱 패키지명 */
    private var currentPackageName: String = ""

    /** 서버 기본 URL */
    private var baseUrl: String = "http://guide.aikopo.net"

    /** TTS 인스턴스 */
    private var textToSpeech: TextToSpeech? = null

    /** 분석 중 여부 (중복 요청 방지) */
    private var isAnalyzing: Boolean = false
        set(value) {
            field = value
            onAnalyzeStateChanged?.invoke(value)
        }

    /** 분석 코루틴 Job */
    private var analyzeJob: Job? = null

    /** 현재 서버 응답의 타겟 목록 (오버레이 터치 콜백에서 사용) */
    private var currentTargets: List<GuideTarget> = emptyList()

    /**
     * TTS 인스턴스를 설정한다.
     */
    fun setTts(tts: TextToSpeech?) {
        this.textToSpeech = tts
    }

    /**
     * 서버 URL을 설정한다.
     */
    fun setServerUrl(url: String) {
        this.baseUrl = url.trim().removeSuffix("/")
    }

    /**
     * 가이드를 시작한다.
     * 사용자 명령과 현재 앱 정보를 등록하고 상태를 초기화한다.
     *
     * @param command 사용자의 원본 음성/텍스트 명령
     * @param packageName 현재 포그라운드 앱의 패키지명
     */
    fun startGuide(command: String, packageName: String) {
        analyzeJob?.cancel()
        analyzeJob = null
        guideGeneration++
        userCommand = command
        currentPackageName = packageName
        currentState = GuideState.IDLE
        currentTargets = emptyList()
        isAnalyzing = false
        guideEnabled = true
        isPendingStop = false
        Log.d(TAG, "[디버그] 가이드 활성화 (startGuide - gen=$guideGeneration): command='$command', pkg='$packageName'")
    }

    /** 가이드 종료 시 호출되는 콜백 */
    var onStopGuide: (() -> Unit)? = null

    /**
     * 가이드를 종료하고 상태를 초기화한다.
     */
    fun stopGuide() {
        guideGeneration++
        analyzeJob?.cancel()
        analyzeJob = null
        currentState = GuideState.IDLE
        currentTargets = emptyList()
        isAnalyzing = false
        userCommand = ""
        currentPackageName = ""
        candidateOverlayManager.clearOverlays()
        actionButtonOverlayManager.clearHighlight()
        guideEnabled = false
        isPendingStop = false
        onStopGuide?.invoke()
        Log.d(TAG, "[디버그] 가이드 비활성화 (stopGuide - gen=$guideGeneration)")
    }

    /**
     * 포그라운드 패키지를 업데이트한다.
     */
    fun updatePackageName(packageName: String) {
        currentPackageName = packageName
    }

    /**
     * UI Tree 가 의미 있게 변경되었을 때 호출한다.
     * 서버에 분석을 요청하고 결과에 따라 오버레이/TTS 를 실행한다.
     *
     * @param uiTreeJson 현재 화면의 UI Tree JSON
     * @param scope 코루틴 스코프
     */
    /**
     * 현재 실행 중인 비동기 분석 작업을 취소하고 오버레이를 지운다.
     */
    fun cancelActiveAnalysis() {
        if (isAnalyzing || analyzeJob != null) {
            Log.d(TAG, "[디버그] 가이드 분석 작업 즉시 강제 취소 (cancelActiveAnalysis)")
            analyzeJob?.cancel()
            analyzeJob = null
            isAnalyzing = false
            candidateOverlayManager.clearOverlays()
            actionButtonOverlayManager.clearHighlight()
        }
    }

    fun onUiChanged(uiTreeJson: String, scope: CoroutineScope) {
        if (!isActive) {
            Log.d(TAG, "[디버그] Guide 비활성 상태이므로 분석을 건너뜁니다.")
            return
        }

        // 이전 분석 작업이 돌고 있다면 즉시 취소
        if (isAnalyzing || analyzeJob != null) {
            Log.d(TAG, "[디버그] 새로운 UI 변경 발생 → 진행 중인 이전 분석 강제 취소")
            analyzeJob?.cancel()
        }

        Log.d(TAG, "[디버그] UI 변경 감지 → 분석 프로세스 시작 (isActive=$isActive, userCommand='$userCommand')")
        isAnalyzing = true

        val currentGen = guideGeneration
        analyzeJob = scope.launch {
            try {
                val request = GuideScreenRequest(
                    userCommand = userCommand,
                    uiTreeJson = uiTreeJson,
                    packageName = currentPackageName,
                    previousState = currentState.name
                )

                val guideUrl = "$baseUrl/guide"
                Log.d(TAG, "[디버그] 서버 분석 요청 전송: $guideUrl, state=${currentState.name}, gen=$currentGen")

                val response: GuideScreenResponse = client.post(guideUrl) {
                    contentType(ContentType.Application.Json)
                    header("bypass-tunnel-reminder", "true")
                    setBody(request)
                }.body()

                withContext(Dispatchers.Main) {
                    if (currentGen == guideGeneration) {
                        Log.d(TAG, "[디버그] 서버 분석 응답 수신 (gen=$currentGen): state=${response.state}, unchanged=${response.unchanged}, targets=${response.targets.size}")
                        handleResponse(response)
                    } else {
                        Log.d(TAG, "[디버그] 이전 세대의 가이드 응답이 수신되어 폐기합니다. (수신: $currentGen, 현재: $guideGeneration)")
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) {
                    throw e
                }
                Log.e(TAG, "[디버그] 서버 분석 에러 발생: ${e.message}")
                withContext(Dispatchers.Main) {
                    if (currentGen == guideGeneration) {
                        handleFallback(uiTreeJson)
                    } else {
                        Log.d(TAG, "[디버그] 이전 세대의 Fallback 분석 요청을 건너뜁니다. (수신: $currentGen, 현재: $guideGeneration)")
                    }
                }
            } finally {
                // 레이스 컨디션을 막기 위해, 현재 종료되는 코루틴이 가장 최신의 분석 코루틴인 경우에만 잠금 해제
                if (analyzeJob == coroutineContext[Job]) {
                    isAnalyzing = false
                    Log.d(TAG, "[디버그] 분석 프로세스 종료 (isAnalyzing = false, gen=$currentGen)")
                }
            }
        }
    }

    /**
     * 서버 응답을 처리한다.
     * 상태가 변경된 경우에만 오버레이와 TTS를 실행한다.
     */
    private fun handleResponse(response: GuideScreenResponse) {
        // 상태가 바뀌지 않았으면 오버레이/TTS 재실행 안 함
        if (response.unchanged) {
            Log.d(TAG, "[디버그] 상태 변경 없음 (unchanged=true) — 오버레이 및 TTS를 실행하지 않습니다.")
            return
        }

        val newState = try {
            GuideState.valueOf(response.state)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "[디버그] 알 수 없는 가이드 상태 수신: ${response.state}")
            return
        }

        // 타겟 좌표 최적화 적용 (실제 클릭 가능한 컨테이너 영역으로 매핑)
        val optimizedTargets = response.targets.map { target ->
            val optBounds = TalkTiAccessibilityService.instance?.findOptimizedBounds(target.bounds) ?: target.bounds
            GuideTarget(
                candidateId = target.candidateId,
                text = target.text,
                bounds = optBounds
            )
        }

        // 동일 상태 및 동일 타겟(ID 또는 텍스트 기준) 판단
        val isSameStateAndTargets = newState == currentState && 
            (optimizedTargets.map { it.candidateId } == currentTargets.map { it.candidateId } ||
             optimizedTargets.map { it.text } == currentTargets.map { it.text })

        // 꿀틀거림 방지: 좌표까지 완전 일치한다면 오버레이 재생성 및 TTS 모두 스킵하여 화면 깜빡임 차단
        if (newState == currentState && optimizedTargets == currentTargets) {
            Log.d(TAG, "[디버그] 동일 가이드 상태 및 완전히 동일한 좌표 감지 — 업데이트 건너뜀")
            return
        }

        Log.d(TAG, "[디버그] 가이드 상태 전이: $currentState → $newState, 타겟 개수: ${optimizedTargets.size}")
        currentState = newState
        currentTargets = optimizedTargets

        // 기존 오버레이 정리 및 재배치 (좌표가 변경되었을 수 있으므로 항상 실행)
        candidateOverlayManager.clearOverlays()
        actionButtonOverlayManager.clearHighlight()

        when (newState) {
            GuideState.SELECT_TARGET, GuideState.SELECT_OPTION -> {
                showCandidateOverlays(optimizedTargets)
            }
            GuideState.PRESS_ACTION, GuideState.CONFIRM -> {
                showActionOverlay(optimizedTargets)
            }
            GuideState.COMPLETE -> {
                candidateOverlayManager.clearOverlays()
                actionButtonOverlayManager.clearHighlight()
                speakTts("안내가 완료되었습니다.")

                guideEnabled = false
                currentState = GuideState.COMPLETE
                isPendingStop = true

                Log.d(TAG, "[디버그] Guide 완료 상태 진입 (정리 예약)")

                return
            }
            GuideState.IDLE -> {
                // 할 일 없음
            }
        }

        // TTS 안내 (동일 타겟/상태면 스킵하여 안내 중복 방지)
        if (response.tts.isNotBlank()) {
            if (isSameStateAndTargets) {
                Log.d(TAG, "[디버그] 동일 상태 및 동일 타겟 감지 -> TTS 재생 스킵 (중복 낭독 방지)")
            } else {
                speakTts(response.tts)
            }
        }
    }

    /**
     * 서버 실패 시 기존 Rule 기반 Fallback.
     * CandidateExtractor + ActionTargetFinder 를 사용한다.
     */
    private fun handleFallback(uiTreeJson: String) {
        Log.d(TAG, "[디버그] 로컬 Rule 기반 Fallback 분석 실행 시작")

        val elements = try {
            kotlinx.serialization.json.Json.decodeFromString<List<UiElement>>(uiTreeJson)
        } catch (e: Exception) {
            Log.e(TAG, "[디버그] Fallback UI Tree 파싱 실패: ${e.message}")
            return
        }

        // Rule 1: ActionTargetFinder 로 도착/결제/전송 등 액션 버튼 탐색
        val actionFinder = ActionTargetFinder()
        val actionTarget = actionFinder.findPrimaryAction(elements)

        if (actionTarget != null) {
            val b = actionTarget.bounds
            if (b.left >= b.right || b.top >= b.bottom) {
                Log.w(TAG, "[디버그] Fallback action bounds 유효하지 않아 무시: text=${actionTarget.text}, bounds=[l=${b.left}, t=${b.top}, r=${b.right}, b=${b.bottom}]")
            } else {
                val newTargets = listOf(
                    GuideTarget(
                        candidateId = actionTarget.id,
                        text = actionTarget.text,
                        bounds = actionTarget.bounds
                    )
                )

                if (currentState == GuideState.PRESS_ACTION && currentTargets == newTargets) {
                    Log.d(TAG, "[디버그] Fallback 동일 상태 및 동일 타겟 감지 — 중복 실행 방지")
                    return
                }

                Log.d(TAG, "[디버그] Fallback 상태 전이: $currentState → PRESS_ACTION (액션 버튼 탐색 성공)")
                currentState = GuideState.PRESS_ACTION
                currentTargets = newTargets

                candidateOverlayManager.clearOverlays()
                actionButtonOverlayManager.clearHighlight()

                Log.d(TAG, "[디버그] Fallback 버튼 선택 하이라이트 표시: ID=${actionTarget.id}, 텍스트=${actionTarget.text}, bounds=${actionTarget.bounds}")
                actionButtonOverlayManager.showActionButtonHighlight(
                    actionTarget.bounds,
                    actionTarget.text
                )
                speakTts("${actionTarget.text} 버튼을 눌러주세요.")
                return
            }
        }

        // Rule 2: CandidateExtractor 로 선택 가능한 후보 탐색
        val candidateExtractor = CandidateExtractor()
        val rawCandidates = candidateExtractor.extractCandidates(elements)
        val candidates = rawCandidates.filter { c ->
            val b = c.bounds
            if (b.left >= b.right || b.top >= b.bottom) {
                Log.w(TAG, "[디버그] Fallback candidate bounds 유효하지 않아 필터링: text=${c.text}, bounds=[l=${b.left}, t=${b.top}, r=${b.right}, b=${b.bottom}]")
                false
            } else {
                true
            }
        }

        if (candidates.isNotEmpty()) {
            val newTargets = candidates.map { c ->
                GuideTarget(
                    candidateId = c.id,
                    text = c.text,
                    bounds = c.bounds
                )
            }

            if (currentState == GuideState.SELECT_TARGET && currentTargets == newTargets) {
                Log.d(TAG, "[디버그] Fallback 동일 상태 및 동일 타겟 감지 — 중복 실행 방지")
                return
            }

            Log.d(TAG, "[디버그] Fallback 상태 전이: $currentState → SELECT_TARGET (후보 목록 탐색 성공)")
            currentState = GuideState.SELECT_TARGET
            currentTargets = newTargets

            candidateOverlayManager.clearOverlays()
            actionButtonOverlayManager.clearHighlight()

            Log.d(TAG, "[디버그] Fallback 후보 ${candidates.size}개 목록 표시")
            candidateOverlayManager.showCandidates(candidates) { selected ->
                Log.d(TAG, "[디버그] Fallback 후보 선택 완료: ID=${selected.id}, 텍스트=${selected.text}")
            }
            speakTts("선택해주세요.")
        }
    }

    /**
     * 후보 선택형 오버레이를 표시한다.
     */
    private fun showCandidateOverlays(targets: List<GuideTarget>) {
        if (targets.isEmpty()) return

        val candidates = targets.mapNotNull { t ->
            val b = t.bounds
            if (b.left >= b.right || b.top >= b.bottom) {
                Log.w(TAG, "[디버그] 유효하지 않은 candidate bounds 발견하여 스킵: text=${t.text}, bounds=[l=${b.left}, t=${b.top}, r=${b.right}, b=${b.bottom}]")
                null
            } else {
                Candidate(
                    id = t.candidateId,
                    text = t.text,
                    bounds = b
                )
            }
        }

        if (candidates.isEmpty()) return

        Log.d(TAG, "[디버그] 후보 선택 오버레이 표시: 후보 개수=${candidates.size}")
        candidateOverlayManager.showCandidates(candidates) { selected ->
            Log.d(TAG, "[디버그] 후보 선택 완료: ID=${selected.id}, 텍스트=${selected.text}")
            // 선택 후 다음 UI 변경을 기다림
        }
    }

    /**
     * 액션 버튼형 오버레이를 표시한다.
     */
    private fun showActionOverlay(targets: List<GuideTarget>) {
        if (targets.isEmpty()) return

        val first = targets.first()
        val b = first.bounds
        if (b.left >= b.right || b.top >= b.bottom) {
            Log.w(TAG, "[디버그] 유효하지 않은 action bounds 발견하여 오버레이 무시: text=${first.text}, bounds=[l=${b.left}, t=${b.top}, r=${b.right}, b=${b.bottom}]")
            return
        }

        Log.d(TAG, "[디버그] 버튼 선택 하이라이트 표시: ID=${first.candidateId}, 텍스트=${first.text}, bounds=$b")
        actionButtonOverlayManager.showActionButtonHighlight(
            b,
            first.text
        )
    }

    /**
     * TTS 안내를 실행한다.
     */
    private fun speakTts(message: String) {
        Log.d(TAG, "TTS: $message")
        val params = Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "guide_orchestrator_tts")
        textToSpeech?.speak(message, TextToSpeech.QUEUE_FLUSH, params, "guide_orchestrator_tts")
    }

    /**
     * 특정 클릭 이벤트의 좌표가 현재 가이드 타겟 영역 내에 포함되거나 겹치는지 검사한다.
     */
    fun isClickInsideTargets(clickedBounds: android.graphics.Rect): Boolean {
        if (currentTargets.isEmpty()) return false
        for (target in currentTargets) {
            val b = target.bounds
            val overlap = clickedBounds.left < b.right && clickedBounds.right > b.left &&
                          clickedBounds.top < b.bottom && clickedBounds.bottom > b.top
            if (overlap) {
                return true
            }
        }
        return false
    }

    /**
     * 리소스를 정리한다.
     */
    fun destroy() {
        analyzeJob?.cancel()
        analyzeJob = null
        guideEnabled = false
        isAnalyzing = false
        onAnalyzeStateChanged = null
        currentTargets = emptyList()
        candidateOverlayManager.clearOverlays()
        actionButtonOverlayManager.clearHighlight()
        Log.d(TAG, "[디버그] GuideOrchestrator destroy 정리 완료")
    }
}
