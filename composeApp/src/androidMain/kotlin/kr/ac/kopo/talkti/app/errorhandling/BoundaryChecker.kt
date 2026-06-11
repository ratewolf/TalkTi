package kr.ac.kopo.talkti.app.errorhandling

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 예외 처리 #2: 오터치로 인한 외부 앱 이탈 감지 (Boundary Check)
 *
 * 두 가지 이탈 상황을 감지합니다:
 *   (A) 외부 앱 이탈: 가이드 중인 앱과 다른 패키지로 넘어간 경우
 *   (B) 앱 내부 이탈: 같은 앱이지만 엉뚱한 화면(탭, 채팅방 등)으로 넘어간 경우
 *       → LLM 검증과 연계하여 판단 (타이머 만료 시 수행)
 */
class BoundaryChecker {

    companion object {
        private const val TAG = "BoundaryChecker"

        /**
         * 시스템 UI 등 이탈로 간주하면 안 되는 패키지 목록
         */
        private val SYSTEM_PACKAGES = setOf(
            "com.android.systemui",
            "com.samsung.android.app.cocktailbarservice",
            "com.samsung.android.app.aodservice",
            "com.android.launcher",
            "com.sec.android.app.launcher",
            "com.google.android.inputmethod.latin",  // 키보드
            "com.samsung.android.honeyboard",          // 삼성 키보드
            "kr.ac.kopo.talkti"                        // 똑띠 자체
        )
    }

    /** 현재 가이드 대상 앱의 패키지명 (null이면 가이드 중이 아님) */
    var targetPackageName: String? = null
        private set

    /** 현재 가이드 대상 명령어/목표 (LLM 검증 시 사용) */
    var currentGoal: String? = null
        private set

    /** 이탈이 감지된 후 복귀 안내를 이미 했는지 여부 (중복 안내 방지) */
    private var alreadyNotified = false

    /**
     * 가이드를 시작할 때 대상 앱 패키지명과 목표를 등록합니다.
     */
    fun setTarget(packageName: String, goal: String) {
        // 초기에 전달된 패키지가 런처나 똑띠 등 시스템/자기자신인 경우,
        // 실제 실행될 목표 앱의 패키지명을 아직 모르는 상태이므로 대기 상태로 설정합니다.
        if (SYSTEM_PACKAGES.any { packageName.contains(it) }) {
            targetPackageName = "WAITING_FOR_REAL_APP"
            Log.d(TAG, "가이드 대상 등록 대기 (런처/시스템 뷰에서 시작됨): init_pkg=$packageName, goal=$goal")
        } else {
            targetPackageName = packageName
            Log.d(TAG, "가이드 대상 등록: pkg=$packageName, goal=$goal")
        }
        currentGoal = goal
        alreadyNotified = false
    }

    /**
     * 가이드 종료 시 대상을 초기화합니다.
     */
    fun clearTarget() {
        targetPackageName = null
        currentGoal = null
        alreadyNotified = false
        Log.d(TAG, "가이드 대상 초기화")
    }

    /**
     * 접근성 이벤트로부터 외부 앱 이탈 여부를 검사합니다.
     *
     * @param event 접근성 이벤트
     * @return CheckResult 이탈 상태 정보
     */
    fun checkBoundary(event: AccessibilityEvent): CheckResult {
        val target = targetPackageName ?: return CheckResult.NO_GUIDE_ACTIVE
        val eventPackage = event.packageName?.toString() ?: return CheckResult.NO_GUIDE_ACTIVE

        // 시스템 UI 패키지는 무시 (알림 바, 키보드 등)
        if (SYSTEM_PACKAGES.any { eventPackage.contains(it) }) {
            return CheckResult.SYSTEM_UI_IGNORED
        }

        // 실제 목표 앱이 열리기를 기다리던 상태였다면, 처음 감지된 비시스템 앱을 타겟으로 확정합니다.
        if (target == "WAITING_FOR_REAL_APP") {
            targetPackageName = eventPackage
            Log.d(TAG, "실제 목표 앱 감지됨 -> 타겟 패키지 자동 갱신: $eventPackage")
            alreadyNotified = false
            return CheckResult.ON_TRACK
        }

        // 패키지명이 일치하면 정상
        if (eventPackage == target) {
            alreadyNotified = false
            return CheckResult.ON_TRACK
        }

        // 패키지명 불일치 → 외부 앱 이탈 감지
        if (!alreadyNotified) {
            alreadyNotified = true
            Log.d(TAG, "외부 앱 이탈 감지: target=$target, current=$eventPackage")
            return CheckResult.EXTERNAL_APP_DEVIATION(
                targetPackage = target,
                currentPackage = eventPackage
            )
        }

        return CheckResult.ALREADY_NOTIFIED
    }

    /**
     * 앱 내부 이탈 감지를 위해 현재 화면의 텍스트를 수집합니다.
     * 이 데이터는 LLM에게 전달되어 "현재 사용자가 올바른 화면에 있는지" 판단에 사용됩니다.
     *
     * @param rootNode 현재 화면의 루트 노드
     * @return 화면에서 수집한 텍스트 리스트
     */
    fun collectScreenTexts(rootNode: AccessibilityNodeInfo?): List<String> {
        if (rootNode == null) return emptyList()

        val texts = mutableListOf<String>()
        val queue = mutableListOf(rootNode)

        while (queue.isNotEmpty()) {
            val node = queue.removeAt(0)

            if (node.isVisibleToUser) {
                val text = node.text?.toString()?.trim()
                val desc = node.contentDescription?.toString()?.trim()

                if (!text.isNullOrBlank()) texts.add(text)
                if (!desc.isNullOrBlank() && desc != text) texts.add(desc)
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }

        return texts
    }

    /**
     * 외부 앱 이탈 시 원래 앱으로 복귀시킵니다 (뒤로가기 수행).
     *
     * @param service 접근성 서비스 인스턴스
     * @return true = 뒤로가기 성공
     */
    fun navigateBack(service: AccessibilityService): Boolean {
        return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }

    /**
     * 이탈 확인 후 복귀 알림 상태를 리셋합니다 (사용자가 복귀한 경우).
     */
    fun resetNotification() {
        alreadyNotified = false
    }

    /**
     * 이탈 검사 결과를 나타내는 Sealed Class
     */
    sealed class CheckResult {
        /** 가이드가 활성화되지 않은 상태 (검사 불필요) */
        object NO_GUIDE_ACTIVE : CheckResult()

        /** 시스템 UI 이벤트 (무시해야 함) */
        object SYSTEM_UI_IGNORED : CheckResult()

        /** 정상 상태 (올바른 앱에 있음) */
        object ON_TRACK : CheckResult()

        /** 이미 이탈 안내를 한 상태 (중복 안내 방지) */
        object ALREADY_NOTIFIED : CheckResult()

        /** 외부 앱으로 이탈됨 */
        data class EXTERNAL_APP_DEVIATION(
            val targetPackage: String,
            val currentPackage: String
        ) : CheckResult()
    }
}
