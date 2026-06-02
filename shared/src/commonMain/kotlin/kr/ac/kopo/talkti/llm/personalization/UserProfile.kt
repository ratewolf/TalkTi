package kr.ac.kopo.talkti.llm.personalization

/**
 * 앱 로컬 DB에서 불러온 어르신의 개인화 정보(설정)를 담는 데이터 클래스.
 */
data class UserProfile(
    val userName: String = "어르신",
    val preferredTransport: String? = null, // 예: "택시", "버스" 등
    val homeAddress: String? = null,        // 예: "서울시 노원구 상계동"
    val preferredHospital: String? = null,  // 예: "상계 백병원"
    val otherPreferences: String? = null    // 기타 특이사항 메모
)

/**
 * 개인화 정보를 LLM 프롬프트 상단에 주입할 수 있는 "자연어 텍스트"로 변환하는 유틸리티
 */
fun buildPersonalizationContext(profile: UserProfile): String {
    val builder = StringBuilder()
    builder.append("[사용자 개인화 정보]\n")
    builder.append("- 사용자를 호칭할 때는 항상 친절하고 공손하게 '${profile.userName}'(이)라고 불러주세요.\n")
    
    if (!profile.preferredTransport.isNullOrBlank()) {
        builder.append("- 사용자는 이동 시 주로 '${profile.preferredTransport}'(을)를 선호합니다. 수단을 제안할 때 이 점을 최우선으로 고려하세요.\n")
    }
    
    if (!profile.homeAddress.isNullOrBlank()) {
        builder.append("- 사용자의 집 주소는 '${profile.homeAddress}' 입니다. 길찾기 시 참고하세요.\n")
    }
    
    if (!profile.preferredHospital.isNullOrBlank()) {
        builder.append("- 사용자의 단골 병원은 '${profile.preferredHospital}' 입니다. 병원 관련 탐색 시 이 병원을 먼저 우선순위에 두세요.\n")
    }
    
    if (!profile.otherPreferences.isNullOrBlank()) {
        builder.append("- 기타 참고사항: ${profile.otherPreferences}\n")
    }
    
    return builder.toString()
}
