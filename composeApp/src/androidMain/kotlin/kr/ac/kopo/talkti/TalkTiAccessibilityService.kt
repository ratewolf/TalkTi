package kr.ac.kopo.talkti

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import androidx.annotation.RequiresApi
import android.graphics.Bitmap
import android.util.Base64
import android.view.Display
import java.io.ByteArrayOutputStream
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.Toast

class TalkTiAccessibilityService : AccessibilityService() {

    private val TAG = "TalkTiService"

    // 오버레이 뷰를 관리할 변수
    private var windowManager: WindowManager? = null
    private var floatingButton: Button? = null

    // 서비스가 시스템에 성공적으로 연결되었을 때 호출됨 (오버레이 생성의 최적 타이밍)
    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "접근성 서비스 연결됨 - 플로팅 버튼 생성 시작")
        createFloatingButton()
    }

    private fun createFloatingButton() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // 1. 버튼 UI 생성 (별도 XML 없이 코드로 뷰 생성)
        floatingButton = Button(this).apply {
            text = "TalkTi 호출"
            setBackgroundColor(Color.parseColor("#FEE500")) // 카카오 옐로우
            setTextColor(Color.BLACK)
            textSize = 16f

            // 버튼 클릭 이벤트 설정
            setOnClickListener {
                Toast.makeText(this@TalkTiAccessibilityService, "화면 캡처 중...", Toast.LENGTH_SHORT).show()
                captureScreenForLLM()
            }
        }

        // 2. 오버레이 레이아웃 속성 설정
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            // 중요: 접근성 서비스 전용 오버레이 타입 (설정에서 다른 앱 위에 그리기 권한 불필요)
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // 버튼 밖의 영역은 터치가 통과하도록 설정
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END // 우측 상단 배치
            x = 50 // 우측 여백
            y = 300 // 상단 여백
        }

        // 3. 화면에 버튼 추가
        windowManager?.addView(floatingButton, params)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // 화면이 바뀌거나 내용이 변경될 때마다 호출됩니다.
        val rootNode = rootInActiveWindow ?: return

        Log.d(TAG, "새로운 화면 감지: ${event.packageName}")

        // 화면 트리 순회 시작
        traverseNodes(rootNode)
    }

    private fun traverseNodes(node: AccessibilityNodeInfo?) {
        if (node == null) return

        // 텍스트가 있는 요소라면 로그에 출력 (버튼, 텍스트뷰 등)
        val text = node.text?.toString() ?: node.contentDescription?.toString()
        if (!text.isNullOrBlank()) {
            val viewId = node.viewIdResourceName ?: "no_id"
            Log.d(TAG, "발견된 UI 요소: [$viewId] -> $text")
        }

        // 자식 노드들을 재귀적으로 탐색
        for (i in 0 until node.childCount) {
            traverseNodes(node.getChild(i))
        }
    }

    override fun onInterrupt() {
        Log.e(TAG, "서비스가 중단되었습니다.")
    }

    // 화면 캡처를 실행하는 함수
    fun captureScreenForLLM() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                @RequiresApi(Build.VERSION_CODES.R)
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshotResult: ScreenshotResult) {
                        Log.d(TAG, "스크린샷 캡처 성공!")

                        val hardwareBuffer = screenshotResult.hardwareBuffer
                        val colorSpace = screenshotResult.colorSpace

                        // 1. HardwareBuffer를 Bitmap으로 변환
                        val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)

                        if (bitmap != null) {
                            // 2. Bitmap을 Base64 String으로 변환 (서버 전송용)
                            val base64Image = bitmapToBase64(bitmap)
                            Log.d(TAG, "Base64 이미지 변환 완료 (문자열 길이: ${base64Image.length})")

                            // TODO: 이 시점에 앞서 수집한 UI 트리(로그로 찍던 텍스트들)와
                            // base64Image를 묶어서 Ktor 서버로 전송하면 됩니다!
                        }

                        // 중요: 메모리 누수 방지를 위해 반드시 close() 호출
                        hardwareBuffer.close()
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.e(TAG, "스크린샷 캡처 실패, 에러 코드: $errorCode")
                    }
                }
            )
        } else {
            Log.e(TAG, "이 스크린샷 방식은 안드로이드 11(API 30) 이상에서만 지원됩니다.")
        }
    }

    // Bitmap을 Base64 문자열로 압축 및 변환하는 헬퍼 함수
    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        // 용량 최적화를 위해 JPEG 포맷 사용 및 품질 80%로 압축 (필요시 조절)
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
        val byteArray = outputStream.toByteArray()
        // NO_WRAP: 줄바꿈 없이 한 줄의 긴 문자열로 생성 (서버에서 파싱하기 좋음)
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }
}