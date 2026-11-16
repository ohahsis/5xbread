# 엣지 케이스 E-01 ~ E-15

정의서 엣지 케이스 표의 15개 상황을 무엇으로 증명하는지 정리한 표입니다.
테스트 이름은 `클래스#메서드`이며 모두 `backend/src/test/java/com/fivexbread/` 아래에 있습니다.
기대 결과가 정의서와 다른 곳은 비고에 `docs/decisions.md`의 행 이름을 적었습니다(decisions.md가 우선).

`EdgeCaseApiTest`는 이 표를 채우려고 1차 마무리에서 추가한 테스트입니다. 기존 테스트가 이미 증명하던 항목도 같은 표에 함께 적었습니다.

| E | 상황 | 기대 결과 | 증명하는 테스트 | 비고 |
|---|---|---|---|---|
| E-01 | 잔여 1개에 두 손님이 동시에 1개씩 요청 | 1명만 201, 다른 1명은 409 `OUT_OF_STOCK` | `reservation.ReservationApiTest#마지막_1개를_동시에_2건_예약하면_1건_성공_1건_409`<br>`reservation.ReservationApiTest#동시_1000건이어도_예약_수량_합은_총량을_넘지_않고_Redis_잔여는_DB_계산값과_같다` | 앱 3개 + Nginx는 `load/run.sh`(1,000건 → 201 120 / 409 880 / 5xx 0) |
| E-02 | 한도 3개인 빵을 같은 손님이 두 탭에서 동시에 2개씩 요청 | 한쪽만 201, 다른 쪽은 409 `PER_USER_LIMIT_EXCEEDED`, 1인 누적 2 | `reservation.EdgeCaseApiTest#E02_한도_3개_빵을_같은_손님이_두_탭에서_동시에_2개씩_요청하면_한쪽만_성공하고_다른_쪽은_PER_USER_LIMIT_EXCEEDED`<br>`reservation.ReservationApiTest#한도를_넘으면_409와_남은_1인_한도` | |
| E-03 | 예약 버튼 연타·네트워크 재전송 | 같은 `Idempotency-Key`면 예약 1건. 다시 보내면 같은 201, 처리 중이면 409 `IDEMPOTENCY_IN_PROGRESS` | `reservation.EdgeCaseApiTest#E03_같은_Idempotency_Key로_동시에_연타해도_예약은_1건이고_나머지는_같은_201_또는_처리_중_409`<br>`reservation.ReservationApiTest#같은_Idempotency_Key로_다시_보내면_같은_201_응답이고_예약은_1건`<br>`reservation.ReservationApiTest#실패한_요청의_키는_지워져_같은_키로_다시_시도할_수_있고_처리_중이면_409` | 처리 중 409는 정의서 N-06. 실패한 요청의 키는 지워져 다시 시도할 수 있다 |
| E-04 | 빵 3종 중 1종만 매진 | 예약 전체 409 `OUT_OF_STOCK`, 나머지 2종은 Redis 잔여·1인 누적·DB 모두 그대로 | `reservation.EdgeCaseApiTest#E04_빵_3종_중_1종이_매진이면_전체_실패하고_나머지_2종은_Redis도_DB도_그대로` | Lua가 모든 빵을 검사한 뒤에만 차감한다 |
| E-05 | 취소 후 같은 빵 재예약 | 취소한 수량만큼 1인 누적이 줄어 다시 한도까지 예약 가능 | `reservation.ReservationCancelApiTest#한도까지_예약하고_취소하면_같은_빵을_다시_한도까지_예약할_수_있다`<br>`reservation.ReservationCancelApiTest#하루_3건이_찬_손님이_1건_취소하면_1건_더_예약할_수_있고_그다음은_422` | |
| E-06 | 픽업 11:00 예약을 10:00:00 / 10:00:01에 취소 | 10:00:00은 200, 10:00:01은 422 `CANCEL_DEADLINE_PASSED` | `reservation.ReservationCancelApiTest#픽업_60분_전_정각까지_취소할_수_있고_1초_뒤는_422`<br>`reservation.ReservationCancelableTest#마감_정각에는_취소할_수_있다`<br>`reservation.ReservationCancelableTest#마감_1초_뒤에는_취소할_수_없다` | 시각은 주입한 `Clock`으로 고정 |
| E-07 | 취소 마감 직전 화면을 열어두고 마감 후 취소 | 서버가 요청 시각으로 다시 판단해 422 | `reservation.ReservationCancelApiTest#픽업_60분_전_정각까지_취소할_수_있고_1초_뒤는_422`<br>`reservation.MyReservationApiTest#상세의_cancelable은_마감_정각까지_true이고_1초_뒤는_false` | 상세의 `cancelable`은 응답 시점의 참고값이고, 취소 API는 그 값을 믿지 않는다 |
| E-08 | 취소 마감이 예약 시점보다 이미 지남 (10:30에 11:00 픽업 예약) | 생기지 않게 막는다: 예약 마감은 첫 픽업 1시간 전까지만 설정 가능, 마감 직전 예약도 취소 가능, 마감부터는 예약 422 `RESERVATION_CLOSED` | `reservation.EdgeCaseApiTest#E08_마감이_첫_픽업_1시간_전이라_마감_직전_예약도_취소할_수_있고_마감부터는_예약이_안_된다`<br>`saledate.SaleDateAdminApiTest#규칙을_어기면_422와_위반_항목_전부` (`CLOSE_TOO_LATE`) | decisions.md 5장 Q-03 |
| E-09 | 손님 취소와 사장님 수령이 동시에 도착 | 먼저 커밋된 쪽만 성공, 다른 쪽은 409 `INVALID_STATUS_TRANSITION`. 취소가 이긴 예약만 재고 복구 | `reservation.PickupApiTest#손님_취소와_사장님_수령이_동시에_오면_한쪽만_성공하고_취소가_이긴_예약만_재고가_돌아온다`<br>`reservation.AdminReservationCancelApiTest#손님_취소와_직권_취소가_동시에_오면_예약마다_한쪽만_성공한다` | 상태 조건부 UPDATE |
| E-10 | 사장님이 예약된 수량보다 총량을 낮춤 | 422 `INVALID_STOCK_ROWS`(틀린 행 전부), 아무것도 저장하지 않음 | `saledate.StockAdminApiTest#예약_수량보다_적게_줄이는_등_틀린_행이_있으면_422와_틀린_행_전부이고_아무것도_저장하지_않는다`<br>`saledate.StockAdminApiTest#검사_뒤_UPDATE_전에_예약이_들어와_총량보다_많아지면_422이고_버전까지_전부_롤백`<br>`saledate.StockAdminApiTest#줄이기가_Redis에서_막히면_422이고_DB와_Redis_모두_바뀌지_않는다` | R-07. decisions.md 6장 "R-07 수량 축소" |
| E-11 | 예약 후 사장님이 가격 변경 | 기존 예약은 예약 당시 가격, 새 예약은 새 가격 | `reservation.EdgeCaseApiTest#E11_예약_뒤_가격을_바꾸면_기존_예약은_예약_당시_가격이고_새_예약은_새_가격`<br>`saledate.SaleBreadsCacheApiTest#예약_금액은_캐시가_아니라_DB_가격이다` | `reservation_item.unit_price`에 저장 |
| E-12 | Redis 차감은 성공, DB 저장 실패 | Redis 차감을 되돌리고 500 `INTERNAL_ERROR`, 같은 키로 다시 시도 가능 | `reservation.EdgeCaseApiTest#E12_Redis_차감_뒤_DB_저장이_실패하면_Redis를_되돌리고_500이며_같은_키로_다시_시도할_수_있다`<br>`reservation.ReservationApiTest#Redis_잔여가_DB보다_많아_DB_조건부_UPDATE가_0행이면_409이고_DB와_Redis는_예약_전_그대로` | DB 조건부 UPDATE 0행(DB 기준 재고 부족)은 **정의서와 다르게 409 `OUT_OF_STOCK`**: decisions.md 6장 "C-04 DB 조건부 UPDATE 0행". 보상 실패 시 N-05가 고친다 |
| E-13 | Redis 장애 | 예약은 503 `SERVICE_UNAVAILABLE`이고 DB는 그대로, 목록은 DB 값으로 200(`version` 0), 복구되면 다시 예약 | `reservation.EdgeCaseApiTest#E13_Redis가_멈추면_예약은_503이고_DB는_그대로이며_목록은_DB_기준으로_200이고_복구되면_다시_예약된다`<br>`saledate.SaleBreadsFacadeTest#Redis_연결_실패면_DB_값으로_200이고_적재를_시도하지_않는다`<br>`common.ApiExceptionHandlerTest#Redis_명령_시간_초과는_연결_실패처럼_503` | 정의서의 두 안 중 "예약 일시 중단": decisions.md 5장 Q-05, 6장 "C-02 잔여 읽기". E13 테스트는 Redis 컨테이너를 실제로 `docker pause`한다 |
| E-14 | 판매 중지된 빵을 예약 시도 | 수량 행이 있어도 422 `BREAD_NOT_AVAILABLE`(`errors[].breadId`), 목록에서 빠짐 | `reservation.EdgeCaseApiTest#E14_판매_중지된_빵은_수량_행이_있어도_예약하면_422_BREAD_NOT_AVAILABLE이고_목록에서_빠진다`<br>`saledate.SaleDateQueryApiTest#판매_중지된_빵은_목록에서_빠진다`<br>`saledate.SaleBreadsCacheApiTest#사장님이_빵을_저장하면_목록에_바로_반영되고_판매_중지도_빠진다`<br>`reservation.ReservationApiTest#업무_규칙을_어기면_정해진_순서로_404_422` | |
| E-15 | 화면(WebSocket) 잔여보다 실제 잔여가 줄어 예약 시 부족 | 409 `OUT_OF_STOCK` + `errors[]`에 빵별 최신 잔여. 화면 수량은 참고용 | `reservation.EdgeCaseApiTest#E15_화면보다_잔여가_줄었으면_409_OUT_OF_STOCK과_최신_잔여를_준다`<br>`realtime.StockRealtimeApiTest#목록의_버전보다_작거나_같은_메시지는_버려도_되고_큰_메시지는_적용하면_Redis_와_같다` | 실시간 잔여의 버전 규칙: decisions.md 6장 "C-03 버전"·"C-03 스냅샷" |

## 함께 지키는 것

- **시간대**: `common.TimeZoneGuardTest`가 JVM·Hibernate·JDBC·MySQL 시간대가 모두 KST인지 지킨다. 테스트 JVM을 UTC로 돌리면 4개 중 3개가 실패한다(decisions.md 3장 "시간대 설정 일치").
- **앱 3개 정합성**: `load/run.sh`(동시 예약 1,000건), `load/run.sh --with-cancel`(예약·취소 섞음), `load/realtime-check.sh`(다른 앱의 구독자도 같은 버전을 받는지). 측정값은 README.
