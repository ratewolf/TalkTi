# TalkTi

**멀티모달 AI 에이전트 기반 고령층 특화 실시간 UI 분석 및 시각화 가이드 솔루션**

TalkTi는 고령층 사용자가 복잡한 모바일 앱 화면에서 원하는 기능을 더 쉽게 찾고 사용할 수 있도록 돕는 Android 기반 보조 서비스입니다. 사용자의 음성 명령을 입력으로 받고, 현재 앱 화면의 스크린샷과 Accessibility 기반 UI 트리를 함께 분석하여, 화면 위 오버레이와 음성 안내를 통해 다음 행동을 안내하는 것을 목표로 합니다.

현재는 카카오맵, 카카오T 등 카카오 계열 Android 앱에서의 활용을 우선 목표로 합니다.

---

## 프로젝트 목표

TalkTi의 최종 목표는 사용자가 앱 사용법을 직접 학습하지 않아도 자연어 음성 명령만으로 원하는 기능까지 도달할 수 있도록 돕는 것입니다.

예를 들어 사용자가 다음과 같이 말하면,

> “택시 호출하고 싶어”  
> “집으로 가는 길 알려줘”  
> “카카오맵에서 가까운 병원 찾아줘”

TalkTi는 현재 화면을 분석하고, 다음에 눌러야 할 버튼이나 입력해야 할 위치를 시각적으로 표시하며, 음성으로도 안내합니다.

---

## 핵심 아이디어

TalkTi는 단순한 챗봇이 아니라, 실제 Android 화면 상태를 이해하는 **멀티모달 UI 가이드 에이전트**를 지향합니다.

앱은 다음 정보를 서버로 전송합니다.

1. 사용자의 음성 명령
2. 현재 화면 스크린샷
3. AccessibilityNodeInfo 기반 UI 트리
4. 각 UI 요소의 텍스트, 클래스명, ID, 좌표 정보

서버는 이 정보를 기반으로 LLM 또는 규칙 기반 분석을 수행하고, Android 앱에 다음 안내 액션을 반환합니다.

---

## 주요 기능

### 현재 구현된 기능

- Android 앱 빌드 및 실행
- 마이크 권한 요청
- AccessibilityService 기반 화면 감지
- 기존 앱 화면 위에 TalkTi 호출 플로팅 버튼 표시
- Android STT를 이용한 한국어 음성 인식
- 음성 명령 수신 후 현재 화면 스크린샷 캡처
- AccessibilityNodeInfo 기반 UI 트리 추출
- 스크린샷과 UI 트리를 서버로 전송
- Ktor 서버에서 `/analyze` API로 데이터 수신
- 서버에서 스크린샷 이미지와 UI 트리 JSON 저장
- mock 안내 응답 반환
- Android 앱에서 서버 응답 메시지 Toast로 표시
- screenSessionId 기반 요청/응답 추적
- candidateId 포함 UI 요소 추출
- 응답 검증 후 하이라이트 표시
- ttsMessage 기반 Android TTS 안내

### 앞으로 구현할 기능

- 서버 응답의 `targetBounds` 좌표를 이용한 화면 하이라이트 표시
- 화살표, 말풍선, 반투명 박스 등 시각적 가이드 오버레이
- `ttsMessage` 기반 TTS 음성 안내
- 카카오맵, 카카오T 등 대상 앱 패키지 필터링
- LLM을 이용한 실제 UI 분석 및 다음 행동 결정
- 클릭, 스크롤, 입력 등 가이드 액션 타입 확장
- 고령층 친화 UI: 큰 글씨, 명확한 색상, 단순한 안내 문구
- 서버와 Android 간 모델 구조 정리
- 개인정보 보호를 위한 스크린샷 저장 정책 및 익명화 처리

---

## 기술 스택

### Android Client

- Kotlin
- Android Studio
- Jetpack Compose
- AccessibilityService
- AccessibilityNodeInfo
- WindowManager
- `TYPE_ACCESSIBILITY_OVERLAY`
- Android SpeechRecognizer
- Ktor Client
- Kotlinx Serialization

### Server

- Kotlin
- Ktor Server
- Netty
- Kotlinx Serialization
- 로컬 LLM 연동 예정

---

## 전체 동작 흐름

```text
사용자
  │
  │ 1. TalkTi 호출 버튼 클릭
  ▼
Android AccessibilityService
  │
  │ 2. STT 실행
  ▼
음성 명령 인식
  │
  │ 3. 현재 화면 스크린샷 캡처
  │ 4. UI 트리 추출
  ▼
Android Client
  │
  │ 5. 서버 /analyze 로 데이터 전송
  ▼
Ktor Server
  │
  │ 6. 스크린샷 + UI 트리 + 음성 명령 저장 및 분석
  │ 7. LLM 또는 mock 로 GuideActionResponse 생성
  ▼
Android Client
  │
  │ 8. 응답 수신
  │ 9. 화면 오버레이 및 음성 안내 제공
  ▼
사용자
```

---

## 현재 API 구조

### Android → Server 요청

```kotlin
@Serializable
data class ScreenStateRequest(
    val userVoiceCommand: String,
    val uiTreeJson: String,
    val screenshotBase64: String? = null,
    val screenSessionId: String? = null
)
```

### Server → Android 응답

```kotlin
@Serializable
data class GuideActionResponse(
    val actionType: String,
    val targetBounds: RectDto?,
    val ttsMessage: String,
    val targetCandidateId: String? = null,
    val confidence: Double? = null,
    val screenSessionId: String? = null
)

@Serializable
data class RectDto(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)
```

---

## 현재 서버 엔드포인트

### `POST /analyze`

Android 앱에서 전송한 화면 상태 데이터를 분석하는 API입니다.

현재 서버는 다음 작업을 수행합니다.

1. `ScreenStateRequest` 수신
2. `uploads/` 폴더 생성
3. Base64 스크린샷을 `screenshot_${screenSessionId}.jpg` 형식으로 저장 (없으면 timestamp fallback)
4. UI 트리를 `uitree_${screenSessionId}.json` 형식으로 저장
5. mock `GuideActionResponse` 반환

예시 응답:

```json
{
  "actionType": "CLICK",
  "targetBounds": {
    "left": 500,
    "top": 1200,
    "right": 800,
    "bottom": 1400
  },
  "ttsMessage": "화면 하단의 노란색 호출 버튼을 눌러주세요."
}
```

---

## 프로젝트 구조

현재 업로드된 코드 기준 주요 파일은 다음과 같습니다.

```text
composeApp/
└── src/
    └── androidMain/
        ├── AndroidManifest.xml
        ├── kotlin/
        │   └── kr/ac/kopo/talkti/
        │       ├── App.kt
        │       ├── MainActivity.kt
        │       └── TalkTiAccessibilityService.kt
        └── res/
            └── xml/
                └── accessibility_service_config.xml

server/
└── Application.kt

shared/models/
└── TalkTiModels.kt

NetworkClient.kt
```

> 실제 프로젝트 구조는 Kotlin Multiplatform 설정에 따라 달라질 수 있습니다.

---

## 주요 파일 설명

### `MainActivity.kt`

Android 앱의 진입점입니다.

현재 역할:

- Edge-to-edge 화면 설정
- 마이크 권한 확인 및 요청
- Compose 기반 `App()` 실행

---

### `TalkTiAccessibilityService.kt`

TalkTi의 핵심 Android 서비스입니다.

현재 역할:

- AccessibilityService 연결 시 플로팅 버튼 생성
- `TYPE_ACCESSIBILITY_OVERLAY` 기반 TalkTi 호출 버튼 표시
- 버튼 클릭 시 Android SpeechRecognizer 실행
- 한국어 음성 명령 인식
- 현재 화면 스크린샷 캡처
- AccessibilityNodeInfo 기반 UI 트리 추출
- Ktor Client를 통해 서버 `/analyze`로 데이터 전송
- 서버 응답 수신 후 Toast 안내 표시

---

### `Application.kt`

Ktor 기반 서버 코드입니다.

현재 역할:

- Netty 서버 실행
- `/analyze` POST API 제공
- Android 앱에서 받은 스크린샷과 UI 트리 저장
- mock 안내 응답 반환

---

### `NetworkClient.kt`

Android 클라이언트에서 사용하는 Ktor HTTP Client 설정 파일입니다.

현재 역할:

- Ktor Client 생성
- JSON 직렬화/역직렬화 설정
- 알 수 없는 JSON 필드 무시
- prettyPrint 설정

---

### `TalkTiModels.kt`

Android 앱과 서버가 공유하는 요청/응답 데이터 모델입니다.

현재 포함 모델:

- `ScreenStateRequest`
- `GuideActionResponse`
- `RectDto`

---

## 실행 전 준비 사항

### Android 권한

TalkTi는 다음 권한과 설정이 필요합니다.

- 마이크 권한
- 접근성 서비스 활성화
- Android 11, API 30 이상 권장  
  - 현재 스크린샷 캡처 방식은 `AccessibilityService.takeScreenshot()`을 사용하므로 Android 11 이상에서 동작합니다.

### 접근성 서비스 활성화

Android 기기에서 다음 경로로 이동해 TalkTi 접근성 서비스를 켜야 합니다.

```text
설정 → 접근성 → 설치된 앱 또는 다운로드한 앱 → TalkTi → 사용
```

기기 제조사나 Android 버전에 따라 메뉴 이름은 다를 수 있습니다.

---

## 서버 실행

Ktor 서버는 기본적으로 다음 주소에서 실행됩니다.

```text
0.0.0.0:8080
```

Android 앱에서는 현재 다음 주소로 요청을 보냅니다.

```kotlin
val serverUrl = "http://192.168.0.6:8080/analyze"
```

개발 환경에 따라 Android 기기와 서버 PC가 같은 네트워크에 연결되어 있어야 하며, 위 IP 주소는 본인의 서버 PC IP로 수정해야 합니다.

---

## 현재 개발 단계

현재 TalkTi는 **MVP 프로토타입 단계**입니다.

완성된 부분은 다음과 같습니다.

- Android에서 사용자의 음성 명령을 받을 수 있음
- 현재 화면의 스크린샷을 캡처할 수 있음
- Accessibility UI 트리를 추출할 수 있음
- 화면 상태 데이터를 서버로 전송할 수 있음
- 서버에서 데이터를 저장하고 응답을 반환할 수 있음

아직 구현이 필요한 부분은 다음과 같습니다.

- LLM을 이용한 실제 화면 분석
- 반환된 좌표를 활용한 시각적 가이드 표시
- TTS 안내
- 카카오 앱 한정 동작 필터링
- 안정적인 에러 처리
- 개인정보 보호 설계
- 발표용 시나리오 및 데모 플로우 정리

---

## 향후 개발 로드맵

### 1단계: 화면 가이드 MVP 완성

- 서버 응답 좌표 기반 하이라이트 오버레이 구현
- 안내 문구 말풍선 표시
- TTS 안내 추가
- 서버 연결 실패 시 사용자 안내 개선

### 2단계: UI 트리 품질 개선

- clickable, enabled, focused 등 속성 추가
- bounds 좌표 정규화
- 불필요한 노드 필터링
- 화면 요소 간 계층 구조 보존
- 스크린샷과 UI 트리의 좌표 매칭 정확도 개선

### 3단계: LLM 분석 연동

- 로컬 LLM에 전달할 프롬프트 설계
- 사용자의 음성 명령과 UI 트리를 함께 분석
- 다음 행동을 `GuideActionResponse` 형태로 구조화
- 응답 검증 및 fallback 규칙 추가

### 4단계: 카카오 앱 시나리오 구현

- 카카오맵 목적지 검색 안내
- 카카오맵 길찾기 안내
- 카카오T 택시 호출 안내
- 화면 전환별 단계 추적
- 사용자가 잘못 누른 경우 복구 안내

### 5단계: 고령층 사용성 개선

- 큰 글씨 안내
- 명확한 대비 색상
- 짧고 쉬운 문장
- 반복 안내 기능
- “다시 설명해줘”, “천천히 알려줘” 같은 보조 명령 지원

---

## 개인정보 및 보안 고려사항

TalkTi는 현재 화면 스크린샷과 UI 트리를 서버로 전송하기 때문에 개인정보 보호 설계가 중요합니다.

향후 고려해야 할 항목은 다음과 같습니다.

- 스크린샷 저장 여부를 사용자가 선택 가능하게 만들기
- 서버 저장 데이터 자동 삭제 정책 추가
- 전화번호, 주소, 결제 정보 등 민감 정보 마스킹
- 로컬 네트워크 통신 시 HTTPS 또는 안전한 터널링 검토
- 연구/발표용 데이터와 실제 사용자 데이터를 분리

---

## 데모 시나리오 예시

### 시나리오 1: 카카오맵 목적지 검색

1. 사용자가 카카오맵을 실행한다.
2. TalkTi 호출 버튼을 누른다.
3. “가까운 병원 찾아줘”라고 말한다.
4. TalkTi가 현재 화면을 분석한다.
5. 검색창 위치를 하이라이트한다.
6. “상단 검색창을 눌러 병원을 검색하세요.”라고 안내한다.

### 시나리오 2: 카카오T 택시 호출

1. 사용자가 카카오T를 실행한다.
2. TalkTi 호출 버튼을 누른다.
3. “집으로 택시 불러줘”라고 말한다.
4. TalkTi가 현재 화면과 UI 트리를 서버로 보낸다.
5. LLM이 다음 행동을 결정한다.
6. TalkTi가 눌러야 할 버튼을 화면 위에 표시하고 음성으로 안내한다.

---

## 프로젝트 한줄 요약

TalkTi는 고령층 사용자가 복잡한 Android 앱을 음성 명령과 화면 위 안내만으로 쉽게 사용할 수 있도록 돕는 멀티모달 AI 기반 UI 가이드 솔루션입니다.
---

## MVP 안정화 변경 사항 (LLM 연동 전)

- `screenSessionId`를 Android 캡처 단위로 생성(`screen_<timestamp>`)하여 요청/응답/서버 저장 파일을 연결합니다.
- UI 트리 노드에 `candidateId`(`candidate_0`, `candidate_1` ...)를 부여해 응답의 대상 후보 추적이 가능해졌습니다.
- Android는 서버 응답에 대해 다음을 검증한 뒤에만 하이라이트를 표시합니다.
  - `actionType == CLICK`이면 `targetBounds` 필수
  - 좌표가 화면 범위 내에 있어야 함
  - `confidence`가 있으면 0.3 이상
  - 응답 `screenSessionId`가 현재 요청과 일치
- 검증 실패 시 Toast로 `화면을 다시 분석해 주세요.`를 표시하고 하이라이트를 생략합니다.
- `ttsMessage`는 Android `TextToSpeech`로 재생하며, TTS 초기화 실패 시에도 앱이 중단되지 않도록 예외/실패를 안전 처리합니다.
- 서버 `/analyze`는 `AnalyzeService` 중심으로 동작하며, `uiTreeJson`에서 `candidateId`가 있는 후보를 파싱한 뒤 `clickable && enabled && visibleToUser && bounds != null` 조건을 만족하는 후보만 사용합니다.
- mock 응답은 고정 좌표 대신 첫 번째 유효 후보의 `candidateId`와 `bounds`를 `targetCandidateId`/`targetBounds`로 반환합니다. 유효 후보가 없으면 `actionType=ASK_USER`, `confidence=0.0`, `targetBounds=null`로 응답합니다.
