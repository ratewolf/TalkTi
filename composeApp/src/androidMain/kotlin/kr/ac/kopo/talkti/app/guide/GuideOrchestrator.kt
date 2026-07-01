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
            if (field != value) {
                field = value
                onAnalyzeStateChanged?.invoke(value)
            }
        }

    /** 분석 코루틴 Job */
    private var analyzeJob: Job? = null

    /** 현재 서버 응답의 타겟 목록 (오버레이 터치 콜백에서 사용) */
    private var currentTargets: List<GuideTarget> = emptyList()

    /** 현재 안내 중인 타겟 텍스트 (최종 버튼 여부 판단용) */
    var currentTargetText: String = ""
        private set

    /** ACTION_SET_TEXT 후속 분석 진행 중 외부 onUiChanged 호출 차단 플래그 */
    private var isPostSetTextAnalyzing: Boolean = false

    /** 마지막 서버 응답의 actionType (FINAL 여부 판단용) */
    var lastActionType: String? = null
        private set

    /** 경험 기반 학습 세션 ID (-1이면 미등록) */
    private var experienceSessionId: Long = -1L

    /** 현재 세션의 상태 전이 단계 카운터 */
    private var experienceStep: Int = 0

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

        // 경험 기반 학습 세션 시작
        experienceSessionId = -1L
        experienceStep = 0
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = client.post("$baseUrl/experience/session/start") {
                    contentType(ContentType.Application.Json)
                    header("bypass-tunnel-reminder", "true")
                    setBody("""{"userCommand": "$command"}""")
                }
                val body = response.body<String>()
                val idMatch = Regex("\"sessionId\"\\s*:\\s*(\\d+)").find(body)
                if (idMatch != null) {
                    experienceSessionId = idMatch.groupValues[1].toLong()
                    Log.d(TAG, "[Experience] 세션 시작됨: id=$experienceSessionId")
                }
            } catch (e: Exception) {
                Log.w(TAG, "[Experience] 세션 시작 실패 (무시): ${e.message}")
            }
        }
    }

    /** 가이드 종료 시 호출되는 콜백 */
    var onStopGuide: (() -> Unit)? = null

    /** COMPLETE 상태 진입 즉시 호출되는 콜백 (세션 완전 종료용) */
    var onGuideComplete: (() -> Unit)? = null

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
        currentTargetText = ""
        isPostSetTextAnalyzing = false
        lastActionType = null
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
        Log.d(TAG, "[디버그] 가이드 분석 작업 즉시 강제 취소 (cancelActiveAnalysis)")
        guideGeneration++
        analyzeJob?.cancel()
        analyzeJob = null
        isAnalyzing = false
        // 분석 진행 여부와 무관하게 항상 오버레이 즉시 제거 (화면 전환 시 잔류 방지)
        candidateOverlayManager.clearOverlays()
        actionButtonOverlayManager.clearHighlight()
    }

    fun onUiChanged(uiTreeJson: String, scope: CoroutineScope, isManualTrigger: Boolean = false) {
        if (!isActive) {
            Log.d(TAG, "[디버그] Guide 비활성 상태이므로 분석을 건너뜁니다.")
            return
        }

        // ACTION_SET_TEXT 후속 분석 진행 중이면 외부 호출 차단
        if (isPostSetTextAnalyzing && !isManualTrigger) {
            Log.d(TAG, "[디버그] ACTION_SET_TEXT 후속 분석 진행 중 → 외부 onUiChanged 호출 차단")
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
            val optBounds = if (newState == GuideState.SELECT_TARGET || newState == GuideState.SELECT_OPTION) {
                TalkTiAccessibilityService.instance?.findOptimizedBounds(target.bounds) ?: target.bounds
            } else {
                target.bounds // 버튼 클릭 안내(PRESS_ACTION/CONFIRM)일 때는 강제 확장하지 않고 원래 버튼 크기 유지
            }
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

        // 경험 기반 학습 — 상태 전이 기록
        val fromStateName = currentState.name
        val toStateName = newState.name
        val actionDesc = when (newState) {
            GuideState.SELECT_TARGET -> "후보 ${optimizedTargets.size}개 표시"
            GuideState.SELECT_OPTION -> "옵션 ${optimizedTargets.size}개 표시"
            GuideState.PRESS_ACTION -> "버튼 안내: ${optimizedTargets.firstOrNull()?.text ?: ""}"
            GuideState.COMPLETE -> "가이드 완료"
            GuideState.IDLE -> "가이드 종료"
            else -> newState.name
        }
        if (experienceSessionId != -1L) {
            experienceStep++
            val stepSnapshot = experienceStep
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    client.post("$baseUrl/experience/transition") {
                        contentType(ContentType.Application.Json)
                        header("bypass-tunnel-reminder", "true")
                        setBody("""{"sessionId": $experienceSessionId, "step": $stepSnapshot, "fromState": "$fromStateName", "toState": "$toStateName", "actionTaken": "$actionDesc"}""")
                    }
                    Log.d(TAG, "[Experience] 전이 기록: $fromStateName → $toStateName")
                } catch (e: Exception) {
                    Log.w(TAG, "[Experience] 전이 기록 실패 (무시): ${e.message}")
                }
            }
        }

        currentState = newState
        lastActionType = response.actionType
        currentTargets = optimizedTargets
        currentTargetText = optimizedTargets.firstOrNull()?.text ?: ""

        // 기존 오버레이 정리 및 재배치 (좌표가 변경되었을 수 있으므로 항상 실행)
        candidateOverlayManager.clearOverlays()
        actionButtonOverlayManager.clearHighlight()

        val actionArgs = response.actionArguments
        val isSetTextAction = response.actionType == "ACTION_SET_TEXT" && !actionArgs.isNullOrBlank()

        when (newState) {
            GuideState.SELECT_TARGET, GuideState.SELECT_OPTION -> {
                if (newState == GuideState.SELECT_OPTION && optimizedTargets.size >= 2) {
                    // 옵션 모달 + 액션 버튼이 함께 온 경우: 시간차 오버레이 전환
                    val optionTarget = optimizedTargets.first()
                    val actionTarget = optimizedTargets.last()
                    
                    // 1단계: 옵션 영역 오버레이
                    showActionOverlay(listOf(optionTarget), skipShrink = true)
                    
                    CoroutineScope(Dispatchers.Main).launch {
                        delay(3000) // 옵션 선택 시간 확보
                        // 같은 가이드 상태/타겟일 때만 전환 (중간에 취소/변경 안 됐을 때)
                        if (currentState == GuideState.SELECT_OPTION && currentTargets == optimizedTargets) {
                            actionButtonOverlayManager.clearHighlight()
                            showActionOverlay(listOf(actionTarget))
                        }
                    }
                } else {
                    showCandidateOverlays(optimizedTargets)
                }
            }
            GuideState.PRESS_ACTION, GuideState.PRESS_ACTION_EDIT_TEXT, GuideState.CONFIRM -> {
                if (!isSetTextAction) {
                    showActionOverlay(optimizedTargets)
                }
            }
            GuideState.COMPLETE -> {
                candidateOverlayManager.clearOverlays()
                actionButtonOverlayManager.clearHighlight()
                speakTts("안내를 시작합니다.")

                guideEnabled = false
                currentState = GuideState.COMPLETE
                isPendingStop = true

                // COMPLETE 진입 즉시 서비스에 완전 종료를 알린다.
                // (TTS 완료를 기다리는 isPendingStop 경로와 별개로, 세션을 곧바로 꺼서
                //  화면 변경으로 인한 좀비 분석 재실행을 차단한다.)
                onGuideComplete?.invoke()

                Log.d(TAG, "[디버그] Guide 완료 상태 진입 (정리 예약 + 즉시 세션 종료 통지)")

                return
            }
            GuideState.IDLE -> {
                // 서버가 IDLE을 반환했다는 건 더 이상 안내할 것이 없다는 의미
                // (예: 안내시작 후 내비게이션 화면으로 전환된 경우)
                // 가이드를 완전히 종료한다.
                Log.d(TAG, "[디버그] 서버 IDLE 응답 수신 → 가이드 종료")
                guideEnabled = false
                isPendingStop = false

                onGuideComplete?.invoke()
            }
        }

        // 자동 텍스트 입력 액션 실행
        if (isSetTextAction) {
            Log.d(TAG, "[디버그] 가이드 흐름 자동 텍스트 입력 실행 예약 (Silent): $actionArgs")
            val targetBounds = optimizedTargets.firstOrNull()?.bounds
                ?: RectDto(0, 0, 0, 0)
            CoroutineScope(Dispatchers.Main).launch {
                delay(300) // 안정화 대기
                val service = TalkTiAccessibilityService.instance
                val success = service?.performImmediateActionSetText(targetBounds, actionArgs!!)
                Log.d(TAG, "[디버그] 가이드 흐름 자동 텍스트 입력 결과: success=$success")
                if (success == true) {
                    // Kakao T 실시간 장소 리스트가 렌더링될 때까지 대기
                    // 300ms씩 최대 4번(1.2초) 대기하면서 클릭 가능 후보 3개 이상이면 렌더 완료로 판단
                    var placeListReady = false
                    var latestTree = service.extractScreenTree()
                    repeat(6) { attempt ->
                        if (!placeListReady) {
                            delay(300)
                            val tree = service.extractScreenTree()
                            latestTree = tree
                            val hasPlaceItems = try {
                                kotlinx.serialization.json.Json.decodeFromString<List<kr.ac.kopo.talkti.models.UiElement>>(tree)
                                    .filter { it.clickable && it.visibleToUser && it.enabled }
                                    .any { elem ->
                                        val t = elem.text.trim()
                                        t.isNotBlank() &&
                                        !setOf("검색", "뒤로", "닫기", "취소", "홈", "회사", "즐겨찾는 장소 추가").any { t.contains(it) } &&
                                        t.length >= 2
                                    }
                            } catch (e: Exception) { false }
                            Log.d(TAG, "[디버그] 장소 리스트 렌더 대기 (${attempt + 1}/6): hasPlaceItems=$hasPlaceItems")
                            if (hasPlaceItems) {
                                placeListReady = true
                            }
                        }
                    }
                    Log.d(TAG, "[디버그] 자동 입력 완료 → 후속 분석 트리거 (placeListReady=$placeListReady)")
                    isPostSetTextAnalyzing = true
                    // hasPlaceItems 확인에 쓴 마지막 트리를 재사용 (타이밍 불일치 방지)
                    val newTree = latestTree
                    onUiChanged(newTree, service.guideScope, isManualTrigger = true)
                    // 분석 완료 후 플래그 해제 (2초 후)
                    delay(2000)
                    isPostSetTextAnalyzing = false
                }
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

        val service = TalkTiAccessibilityService.instance
        val candidates = targets.mapNotNull { t ->
            val b = t.bounds
            if (b.left >= b.right || b.top >= b.bottom) {
                Log.w(TAG, "[디버그] 유효하지 않은 candidate bounds 발견하여 스킵: text=${t.text}, bounds=[l=${b.left}, t=${b.top}, r=${b.right}, b=${b.bottom}]")
                null
            } else {
                // 하나의 큰 bounds 안에 여러 클릭 요소가 있으면 타겟 텍스트에 맞게 좁힘
                val tightBounds = service?.shrinkBoundsIfMultipleElements(b, t.text) ?: b
                Candidate(
                    id = t.candidateId,
                    text = t.text,
                    bounds = tightBounds
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
    private fun showActionOverlay(targets: List<GuideTarget>, skipShrink: Boolean = false) {
        if (targets.isEmpty()) return

        val first = targets.first()
        val b = first.bounds
        if (b.left >= b.right || b.top >= b.bottom) {
            Log.w(TAG, "[디버그] 유효하지 않은 action bounds 발견하여 오버레이 무시: text=${first.text}, bounds=[l=${b.left}, t=${b.top}, r=${b.right}, b=${b.bottom}]")
            return
        }

        // 하나의 큰 bounds 안에 여러 클릭 요소가 있으면 타겟 텍스트에 맞게 좁힘
        // (예: 카카오톡 '+' 버튼 bounds가 하단 입력 바 전체를 덮는 경우)
        // skipShrink=true면 의도적으로 큰 영역(예: 키오스크 옵션 컨테이너)을 그대로 사용
        val service = TalkTiAccessibilityService.instance
        val tightBounds = if (skipShrink) b else (service?.shrinkBoundsIfMultipleElements(b, first.text) ?: b)

        Log.d(TAG, "[디버그] 버튼 선택 하이라이트 표시: ID=${first.candidateId}, 텍스트=${first.text}, 원본=${b}, 최종=${tightBounds}")
        actionButtonOverlayManager.showActionButtonHighlight(
            tightBounds,
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
     * 사용자가 성공/실패 버튼을 눌렀을 때 경험 세션을 완료 처리한다.
     */
    fun recordExperienceResult(success: Boolean) {
        val sessionId = experienceSessionId
        val steps = experienceStep
        if (sessionId == -1L) {
            Log.w(TAG, "[Experience] 기록할 세션 없음 (무시)")
            return
        }
        experienceSessionId = -1L
        experienceStep = 0
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 직전에 전송된 /experience/transition 요청이 서버에 먼저 도착하도록
                // 약간의 지연을 둔다 (마지막 단계 기록 누락/race condition 방지)
                delay(400)
                client.post("$baseUrl/experience/session/complete") {
                    contentType(ContentType.Application.Json)
                    header("bypass-tunnel-reminder", "true")
                    setBody("""{"sessionId": $sessionId, "success": $success, "totalSteps": $steps}""")
                }
                Log.d(TAG, "[Experience] 세션 완료 기록: id=$sessionId, success=$success, steps=$steps")
            } catch (e: Exception) {
                Log.w(TAG, "[Experience] 세션 완료 기록 실패 (무시): ${e.message}")
            }
        }
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
