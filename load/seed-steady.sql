-- load/seed-steady.sql : 일정 부하(steady.sh) 데이터. 실행마다 다시 넣는다 (seed.sql 과 같은 이유로 마이그레이션이 아니다)
-- 입력: @d = 첫 판매일 (steady.sh 가 앞에 SET @d = 'YYYY-MM-DD' 를 붙인다). @d ~ @d+4 의 5일을 쓴다
--
-- 손님 10,000명: 010-0100-0001 ~ 010-0101-0000 (seed.sql 의 1,000명과 겹치지 않는 대역, 같은 비밀번호 Load-test-2026!)
-- 하루 3건(부록 B)을 바꾸지 않고 초당 N건 × 60초를 성공 경로로 유지하려고 손님을 늘렸다. 손님 1명은 판매일 하나에만 최대 3건 보낸다
-- 빵 1~20을 5일 모두 판다. 총량은 스키마 상한(ck_stock_range: produced_qty <= 500)인 500, 1인 한도 없음.
-- spread 는 100행 × 500 = 50,000개라 측정 중 매진되지 않는다. hot 은 한 행이라 처음 500건 뒤로는 매진(409) 요청이 몰린다
-- (인기 빵이 열자마자 팔리고 그 뒤로도 요청이 계속 오는 상황. decisions.md 6장 "예약 v1/v2 비교")

SET SESSION cte_max_recursion_depth = 20000;
SET @hash = '$2a$10$gJ.E6JV/bO/GPJGH3isbqumnIYh.PpzZG8WsY.77eGGGfbz5Jzjvm';

INSERT INTO member (phone, password_hash, name)
WITH RECURSIVE n (i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 10000)
SELECT CONCAT('0100', LPAD(1000000 + i, 7, '0')), @hash, CONCAT('부하', i) FROM n
ON DUPLICATE KEY UPDATE password_hash = VALUES(password_hash), status = 'ACTIVE', reservation_blocked_until = NULL;

-- 이전 실행의 예약을 지운다 (이 5일만)
DELETE h FROM reservation_history h JOIN reservation r ON r.id = h.reservation_id WHERE r.sale_date BETWEEN @d AND @d + INTERVAL 4 DAY;
DELETE i FROM reservation_item i JOIN reservation r ON r.id = i.reservation_id WHERE r.sale_date BETWEEN @d AND @d + INTERVAL 4 DAY;
DELETE FROM reservation WHERE sale_date BETWEEN @d AND @d + INTERVAL 4 DAY;
DELETE FROM daily_stock WHERE sale_date BETWEEN @d AND @d + INTERVAL 4 DAY;
DELETE FROM pickup_slot WHERE sale_date BETWEEN @d AND @d + INTERVAL 4 DAY;
DELETE FROM sale_date WHERE sale_date BETWEEN @d AND @d + INTERVAL 4 DAY;

INSERT INTO sale_date (sale_date, open_at, close_at, holiday)
WITH RECURSIVE n (k) AS (SELECT 0 UNION ALL SELECT k + 1 FROM n WHERE k < 4)
SELECT @d + INTERVAL k DAY, NOW() - INTERVAL 1 HOUR, TIMESTAMP(@d + INTERVAL k DAY - INTERVAL 1 DAY, '20:00:00'), FALSE FROM n;
INSERT INTO pickup_slot (sale_date, pickup_at)
SELECT sale_date, TIMESTAMP(sale_date, '11:00:00') FROM sale_date WHERE sale_date BETWEEN @d AND @d + INTERVAL 4 DAY;
INSERT INTO daily_stock (sale_date, bread_id, produced_qty, reservable_qty, reserved_qty, per_user_limit)
SELECT s.sale_date, b.id, 500, 500, 0, NULL
FROM sale_date s JOIN bread b ON b.id BETWEEN 1 AND 20
WHERE s.sale_date BETWEEN @d AND @d + INTERVAL 4 DAY;

-- steady.sh 가 읽는다: "판매일 시간대id" 5줄
SELECT sale_date, id FROM pickup_slot WHERE sale_date BETWEEN @d AND @d + INTERVAL 4 DAY ORDER BY sale_date;
