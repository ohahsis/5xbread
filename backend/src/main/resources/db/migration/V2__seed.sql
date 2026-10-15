-- 개발·테스트용 초기 데이터 (정의서 부록 A, decisions.md Q-01)
-- 날짜는 적용 시점의 KST "오늘"을 기준으로 계산. 세션 타임존(UTC 등)과 무관하게 같은 결과

SET @today = DATE(CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+09:00'));

-- 부록 A 표 (no = 표 순서 = bread.id = display_order, per_user_limit NULL = 한도 없음)
CREATE TEMPORARY TABLE seed_bread (
  no             INT         NOT NULL PRIMARY KEY,
  name           VARCHAR(30) NOT NULL,
  category       VARCHAR(20) NOT NULL,
  price          INT         NOT NULL,
  produced_qty   INT         NOT NULL,
  reservable_qty INT         NOT NULL,
  per_user_limit INT         NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 분류: BUTTER_ROLL 버터롱, PASTRY 페이스트리, BAGEL 베이글, HARD_BREAD 하드빵, LOAF 식빵,
--       SWEET_BUN 단과자, SAVORY 조리빵, DONUT 도넛, BAKED_SWEET 구움과자
INSERT INTO seed_bread (no, name, category, price, produced_qty, reservable_qty, per_user_limit) VALUES
  ( 1, '소금빵',              'BUTTER_ROLL', 3500, 200, 120,    4),
  ( 2, '크로와상',            'PASTRY',      4200,  80,  50,    3),
  ( 3, '뺑오쇼콜라',          'PASTRY',      4800,  60,  36,    2),
  ( 4, '크로플',              'PASTRY',      4000,  40,  20,    2),
  ( 5, '플레인 베이글',       'BAGEL',       4500, 100,  60,    4),
  ( 6, '쪽파 크림치즈 베이글', 'BAGEL',       6500,  60,  40,    2),
  ( 7, '바게트',              'HARD_BREAD',  4500,  60,  30,    2),
  ( 8, '명란 바게트',         'HARD_BREAD',  6000,  40,  24,    2),
  ( 9, '치아바타',            'HARD_BREAD',  4000,  40,  20, NULL),
  (10, '무화과 깜빠뉴',       'HARD_BREAD',  8500,  30,  18,    1),
  (11, '우유식빵',            'LOAF',        6500,  50,  30,    2),
  (12, '버터 브리오슈 식빵',  'LOAF',        9000,  30,  20,    1),
  (13, '단팥빵',              'SWEET_BUN',   3000,  60,  30, NULL),
  (14, '앙버터',              'SWEET_BUN',   5500,  60,  40,    3),
  (15, '크림빵',              'SWEET_BUN',   3500,  50,  25, NULL),
  (16, '소보로빵',            'SWEET_BUN',   2800,  50,  20, NULL),
  (17, '피자빵',              'SAVORY',      3800,  50,  25, NULL),
  (18, '육쪽마늘빵',          'SAVORY',      6500,  50,  30,    2),
  (19, '시나몬롤',            'SWEET_BUN',   4800,  50,  30,    3),
  (20, '꽈배기',              'DONUT',       2000,  80,  30, NULL),
  (21, '에그타르트',          'BAKED_SWEET', 3200,  80,  50,    4),
  (22, '까눌레',              'BAKED_SWEET', 3800,  60,  40,    4),
  (23, '휘낭시에',            'BAKED_SWEET', 2800, 100,  60,    6),
  (24, '스콘',                'BAKED_SWEET', 3800,  60,  36,    4),
  (25, '버터바',              'BAKED_SWEET', 3800,  60,  40,    4);

INSERT INTO bread (id, name, category, price, display_order)
SELECT no, name, category, price, no
FROM seed_bread;

-- 내일부터 7일. 오픈 = 판매일 3일 전 10:00, 마감 = 전날 20:00, 월요일 휴무
INSERT INTO sale_date (sale_date, open_at, close_at, holiday)
WITH RECURSIVE d (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM d WHERE n < 7)
SELECT t.day,
       TIMESTAMP(t.day - INTERVAL 3 DAY, '10:00:00'),
       TIMESTAMP(t.day - INTERVAL 1 DAY, '20:00:00'),
       WEEKDAY(t.day) = 0
FROM (SELECT @today + INTERVAL n DAY AS day FROM d) t;

-- 휴무가 아닌 날마다 10:00~18:00, 1시간 간격 9개
INSERT INTO pickup_slot (sale_date, pickup_at)
WITH RECURSIVE h (hour) AS (SELECT 10 UNION ALL SELECT hour + 1 FROM h WHERE hour < 18)
SELECT sd.sale_date, TIMESTAMP(sd.sale_date, MAKETIME(h.hour, 0, 0))
FROM sale_date sd
CROSS JOIN h
WHERE sd.holiday = FALSE
ORDER BY sd.sale_date, h.hour;

-- 휴무가 아닌 날마다 빵 25종. 토·일은 생산량·예약 가능 총량 1.5배(소수점 버림), 1인 한도는 그대로
INSERT INTO daily_stock (sale_date, bread_id, produced_qty, reservable_qty, per_user_limit)
SELECT sd.sale_date,
       sb.no,
       IF(WEEKDAY(sd.sale_date) >= 5, sb.produced_qty * 3 DIV 2, sb.produced_qty),
       IF(WEEKDAY(sd.sale_date) >= 5, sb.reservable_qty * 3 DIV 2, sb.reservable_qty),
       sb.per_user_limit
FROM sale_date sd
CROSS JOIN seed_bread sb
WHERE sd.holiday = FALSE
ORDER BY sd.sale_date, sb.no;

DROP TEMPORARY TABLE seed_bread;
