package kr.ac.kopo.talkti.models

class ActionTargetFinder {

    private val primaryActionKeywords = setOf(
        "도착",
        "길찾기",
        "안내 시작",
        "출발",
        "결제",
        "결제하기",
        "전송",
        "확인",
        "예약",
        "다음",
        "계속"
    )

    fun findPrimaryAction(
        elements: List<UiElement>
    ): Candidate? {

        return elements.firstOrNull { element ->

            val targetText = when {
                element.text.isNotBlank() ->
                    element.text.trim()

                element.contentDescription.isNotBlank() ->
                    element.contentDescription.trim()

                else ->
                    ""
            }

            targetText in primaryActionKeywords &&
                    element.clickable &&
                    element.enabled &&
                    element.visibleToUser

        }?.let { element ->

            Candidate(
                id = element.candidateId,
                text = if (element.text.isNotBlank())
                    element.text
                else
                    element.contentDescription,
                bounds = element.bounds
            )
        }
    }
}