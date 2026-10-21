# 5XBread

빵 예약 서비스 백엔드. 초과 예약 0건(Redis Lua + DB 조건부 UPDATE)과
앱 3개 분산 환경이 핵심. 프론트는 이 저장소 범위 밖(나중에 별도 작업).

## 기준 문서
- 요구사항: docs/requirements.pdf (장 번호로 참조. 예: 13-1 = 예약하기)
- 확정 결정·1주 범위·제외 목록: docs/decisions.md
- 정의서와 decisions.md가 다르면 decisions.md가 우선

## 스택
Java 21, Spring Boot 3.5.16, Gradle 8.14(Kotlin DSL), MySQL 8, Redis 7, Flyway,
Spring Security + JWT, STOMP WebSocket, Testcontainers

## 명령어
- 인프라: `docker compose up -d` (루트)
- 빌드·테스트: `cd backend && ./gradlew build`
- 앱 실행: `./gradlew bootRun --args='--spring.profiles.active=local'`

## 구조 (정의서 14장)
- 도메인별 패키지: common, auth, member, catalog, saledate, reservation, realtime, admin, batch
- 계층: Controller(형식 검증·DTO) → Facade(Redis·DB 순서·보상) → Service(@Transactional) → Domain(업무 규칙) → Repository
- 트랜잭션 안에서 Redis·외부 호출 금지

## 규칙
- 시간: DB DATETIME(KST) / 엔티티 LocalDateTime / API OffsetDateTime(+09:00)
- 현재 시각은 반드시 주입된 Clock 사용. LocalDateTime.now() 직접 호출 금지
- DTO는 record. 엔티티를 컨트롤러 밖으로 내보내지 않음
- 모든 업무 예외는 BusinessException(ErrorCode). 응답은 RFC 9457 ProblemDetail + code
- 스키마 변경은 Flyway 새 버전 파일로만. 기존 마이그레이션 파일 수정 금지
- Enum은 EnumType.STRING
- 동시성 관련 기능은 동시 요청 테스트를 함께 작성

## Git
- main 직접 커밋 금지. 브랜치: feat/{기능ID}-{이름}, fix/, chore/, test/
- 커밋 메시지: feat:, fix:, test:, docs:, chore:
- 기능 커밋에는 그 기능의 테스트를 함께 넣는다. test: 는 테스트만 추가·수정할 때 사용