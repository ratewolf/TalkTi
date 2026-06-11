package kr.ac.kopo.talkti.models

/**
 * 행동 중심 가이드 상태.
 *
 * 앱 종속 상태(PLACE_SELECTION, ROUTE_SELECTION 등)를 사용하지 않고
 * 사용자가 **지금 해야 할 행동**만 표현한다.
 *
 * 전이 예시 (카카오맵 길찾기):
 *   IDLE → SELECT_TARGET (검색 결과 등장)
 *        → PRESS_ACTION  (도착 버튼 등장)
 *        → SELECT_OPTION (경로 목록 등장)
 *        → PRESS_ACTION  (안내 시작 등장)
 *        → COMPLETE
 */
enum class GuideState {
    /** 가이드 비활성 */
    IDLE,

    /** 사용자가 여러 후보 중 하나를 선택해야 함 (장소, 채팅방, 택시 종류 등) */
    SELECT_TARGET,

    /** 사용자가 특정 버튼을 눌러야 함 (도착, 호출, 전송, 안내 시작 등) */
    PRESS_ACTION,

    /** 사용자가 옵션 중 하나를 선택해야 함 (경로 추천 등) */
    SELECT_OPTION,

    /** 사용자가 최종 확인을 해야 함 */
    CONFIRM,

    /** 가이드 완료 */
    COMPLETE
}
