-- A-05 판매일 예약 목록 (decisions.md 6장 "A-05 판매일 예약 목록 인덱스")
-- 픽업 시각 → 예약번호 순서를 인덱스가 그대로 가져, 인덱스 힌트 없이 정렬(filesort) 없이 커서로 끊어 읽는다.
-- 상태를 거를 때와 거르지 않을 때 순서가 맞는 인덱스가 각각 필요하다:
--   - 상태 없음: (sale_date, pickup_at, reservation_no)
--   - 상태 있음: (sale_date, status, pickup_at, reservation_no). 없으면 옵티마이저가 ix_resv_date_status 로 읽고 정렬한다
-- ix_resv_date_status (sale_date, status) 는 두지 않으면 노쇼 배치의 ORDER BY id 가 정렬을 하게 되어 그대로 둔다
-- ix_resv_date_slot (sale_date, pickup_slot_id, status) 는 시간대 id 순이라 픽업 시각 순서를 보장하지 않는다
-- (시간대를 나중에 더하면 id 가 크지만 시각은 앞설 수 있음)
CREATE INDEX ix_resv_date_pickup ON reservation (sale_date, pickup_at, reservation_no);
CREATE INDEX ix_resv_date_status_pickup ON reservation (sale_date, status, pickup_at, reservation_no);
