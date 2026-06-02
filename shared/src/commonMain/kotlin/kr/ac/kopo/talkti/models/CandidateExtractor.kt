package kr.ac.kopo.talkti.models

class CandidateExtractor {

    /**
     * 후보에서 제외할 UI 텍스트 목록
     *
     * 현재 단계에서는:
     * - 뒤로가기
     * - 검색 버튼
     * - 설정 버튼
     * - 현재 위치
     * 등 목적지/경로 선택과 관계없는 UI를 제외합니다.
     *
     * 추후 실제 테스트 결과에 따라 조금씩 추가 가능합니다.
     */
    private val excludedTexts = setOf(
        "뒤로",
        "검색",
        "현재 위치",
        "내 위치",
        "공유",
        "메뉴",
        "설정",
        "닫기",
        "취소",
        "확인"
    )

    private val excludedKeywords = listOf(
        "맛집",
        "카페",
        "날씨",
        "주차장",
        "후기",
        "리뷰",
        "사진",
        "블로그"
    )

    /**
     * UiElement 리스트에서 사용자 선택 후보가 될 수 있는 항목만 추출하여
     * Candidate 리스트로 변환합니다.
     *
     * [현재 단계 필터링 규칙]
     * 1. text가 비어있지 않으면 우선 사용
     * 2. text가 비어있을 경우 contentDescription 사용
     * 3. 둘 다 비어있으면 제외
     * 4. excludedTexts 목록에 포함되면 제외
     * 5. bounds와 candidateId는 그대로 재사용
     */
    fun extractCandidates(elements: List<UiElement>): List<Candidate> {

        return elements.mapNotNull { element ->

            val targetText = when {
                element.text.isNotEmpty() -> element.text
                element.contentDescription.isNotEmpty() -> element.contentDescription
                else -> null
            }?.trim()

            if (
                targetText != null &&
                targetText.isNotBlank() &&
                targetText !in excludedTexts &&
                element.clickable &&
                element.enabled &&
                element.visibleToUser
            ) {
                Candidate(
                    id = element.candidateId,
                    text = targetText,
                    bounds = element.bounds
                )
            } else {
                null
            }
        }
    }

}