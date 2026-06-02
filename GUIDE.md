# TalkTi 협업 패키지 구조 가이드

이 문서는 4인 병렬 개발을 위한 모듈/패키지 경계를 정의합니다.

## 1) 앱 파트 (Android Expert)
**Scope:** `composeApp/src/androidMain/kotlin/kr/ac/kopo/talkti/app/**`

- `accessibility/`: 접근성 이벤트, 노드 수집, 캡처 트리거
- `overlay/`: 플로팅 버튼, 모드 전환, 하이라이트 표시
- `guide/`: TTS/최종 가이드 실행
- `network/`: 앱 전송 전용 클라이언트 어댑터

## 2) 데이터 파트 (Data Specialist)
**Scope:** `shared/src/**/kotlin/kr/ac/kopo/talkti/data/**`

- `embedding/`: ONNX Runtime 연동 및 임베딩 추론
- `parser/`: 노드 텍스트 정규화
- `vector/`: Top-K 검색 및 유사도 계산
- `localdb/`: 로컬 저장소(Room/대체 저장소) 추상화

## 3) 백엔드 파트 (Backend Developer)
**Scope:** `server/src/main/kotlin/kr/ac/kopo/talkti/backend/**`

- `api/`: 라우팅/엔드포인트
- `service/`: 분석 오케스트레이션
- `validator/`: 요청 검증
- `storage/`: 업로드 파일 저장
- `logging/`: 모니터링 및 로그 유틸

## 4) LLM 파트 (AI Engineer)
**Scope:** `shared/src/commonMain/kotlin/kr/ac/kopo/talkti/llm/**` + `server/.../backend/service/llm/**`

- `prompt/`: RAG 프롬프트 템플릿
- `schema/`: Structured Output 스키마
- `personalization/`: 이력 기반 개인화 규칙

---

## 현재 코드와의 연결 원칙
- 기존 MVP 동작 경로(`AccessibilityService -> /analyze -> mock 응답`)는 유지합니다.
- 신규 패키지는 **확장 포인트**로 먼저 분리하고, 기능 이관은 단계적으로 진행합니다.
- 공통 DTO는 `shared/.../models`를 유지합니다.
