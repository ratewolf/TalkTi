package kr.ac.kopo.talkti.backend.storage

import java.io.File
import java.util.Base64

/**
 * 백엔드 파트: 업로드된 스크린샷 및 UI 트리 파일 저장 담당
 */
class FileStorage(private val uploadDirName: String = "uploads") {
    private val uploadDir = File(uploadDirName).apply { if (!exists()) mkdirs() }

    fun saveScreenshot(sessionId: String, base64Image: String): File? {
        return try {
            val imageBytes = Base64.getDecoder().decode(base64Image)
            val file = File(uploadDir, "screenshot_${sessionId}.jpg")
            file.writeBytes(imageBytes)
            file
        } catch (e: Exception) {
            null
        }
    }

    fun saveUiTree(sessionId: String, uiTreeJson: String): File {
        val file = File(uploadDir, "uitree_${sessionId}.json")
        file.writeText(uiTreeJson)
        return file
    }
}
