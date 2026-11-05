-- load/seed.sql : 동시 예약 부하 테스트 데이터 (run.sh 가 실행마다 다시 넣는다)
-- 운영 마이그레이션(db/migration)에는 넣지 않는다. 매 실행 전에 상태를 처음으로 되돌려야 해서 Flyway(한 번만 적용)보다 스크립트가 맞다
-- 입력: @d = 테스트 판매일 (run.sh 가 앞에 SET @d = 'YYYY-MM-DD' 를 붙인다)
--
-- 손님 1,000명: 010-0000-0001 ~ 010-0000-1000. 010-0xxx 국번은 실제로 배정되지 않는 대역이라 실제 번호와 겹치지 않는다
-- 비밀번호는 모두 Load-test-2026! (아래는 그 BCrypt cost 10 해시. 부하 테스트 전용 계정이라 저장소에 둔다)

SET @hash = '$2a$10$gJ.E6JV/bO/GPJGH3isbqumnIYh.PpzZG8WsY.77eGGGfbz5Jzjvm';

INSERT INTO member (phone, password_hash, name)
WITH RECURSIVE n (i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 1000)
SELECT CONCAT('0100000', LPAD(i, 4, '0')), @hash, CONCAT('부하손님', i) FROM n
ON DUPLICATE KEY UPDATE password_hash = VALUES(password_hash), status = 'ACTIVE', reservation_blocked_until = NULL;

-- 이전 실행의 예약을 지운다 (이 판매일만)
DELETE h FROM reservation_history h JOIN reservation r ON r.id = h.reservation_id WHERE r.sale_date = @d;
DELETE i FROM reservation_item i JOIN reservation r ON r.id = i.reservation_id WHERE r.sale_date = @d;
DELETE FROM reservation WHERE sale_date = @d;
DELETE FROM daily_stock WHERE sale_date = @d;
DELETE FROM pickup_slot WHERE sale_date = @d;
DELETE FROM sale_date WHERE sale_date = @d;

-- 지금 예약할 수 있는 판매일: 1시간 전에 열렸고 전날 20:00에 마감 (V2 시드의 1~7일 뒤와 겹치지 않게 run.sh 가 10일 뒤로 정함)
INSERT INTO sale_date (sale_date, open_at, close_at, holiday)
VALUES (@d, NOW() - INTERVAL 1 HOUR, TIMESTAMP(@d - INTERVAL 1 DAY, '20:00:00'), FALSE);
INSERT INTO pickup_slot (sale_date, pickup_at) VALUES (@d, TIMESTAMP(@d, '11:00:00'));

-- 소금빵 1종만 판다 (부록 A 값: 생산 200, 예약 가능 120, 1인 4개)
INSERT INTO daily_stock (sale_date, bread_id, produced_qty, reservable_qty, reserved_qty, per_user_limit)
VALUES (@d, 1, 200, 120, 0, 4);

SELECT id AS pickup_slot_id FROM pickup_slot WHERE sale_date = @d;
