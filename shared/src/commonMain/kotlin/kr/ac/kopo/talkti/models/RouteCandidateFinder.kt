package kr.ac.kopo.talkti.models

class RouteCandidateFinder {

    fun findRouteCandidates(
        elements: List<UiElement>
    ): List<Candidate> {

        return elements.mapNotNull { element ->

            val text = when {
                element.text.isNotBlank() ->
                    element.text.trim()

                element.contentDescription.isNotBlank() ->
                    element.contentDescription.trim()

                else ->
                    ""
            }

            if (
                text.contains("분") &&
                element.clickable &&
                element.visibleToUser
            ) {
                Candidate(
                    id = element.candidateId,
                    text = text,
                    bounds = element.bounds
                )
            } else {
                null
            }
        }
    }
}