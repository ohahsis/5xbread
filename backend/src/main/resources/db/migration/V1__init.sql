-- 정의서 13장 "테이블 설계" 기준. 제약 이름은 오류 → 에러 코드 변환에 사용
-- 변경: 모든 테이블 InnoDB·utf8mb4 명시, bread.image_id → bread_image.id FK 추가(bread_image를 먼저 생성)

CREATE TABLE member (
  id                        BIGINT AUTO_INCREMENT PRIMARY KEY,
  phone                     VARCHAR(11) NULL,                    -- 탈퇴 시 NULL (UNIQUE는 NULL 여러 개 허용)
  password_hash             CHAR(60)    NOT NULL,                -- BCrypt
  name                      VARCHAR(20) NOT NULL,
  role                      VARCHAR(10) NOT NULL DEFAULT 'CUSTOMER',
  status                    VARCHAR(10) NOT NULL DEFAULT 'ACTIVE',
  must_change_password      BOOLEAN     NOT NULL DEFAULT FALSE,
  reservation_blocked_until DATE        NULL,                    -- R-17
  withdrawn_phone_hash      CHAR(64)    NULL,                    -- 탈퇴 30일 재가입 제한
  created_at                DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  withdrawn_at              DATETIME(3) NULL,
  CONSTRAINT uk_member_phone UNIQUE (phone),                     -- 중복 가입 방지 + 로그인 조회
  INDEX ix_member_withdrawn_phone (withdrawn_phone_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE member_agreement (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  member_id BIGINT NOT NULL, terms_version VARCHAR(10) NOT NULL, privacy_version VARCHAR(10) NOT NULL,
  agreed_at DATETIME(3) NOT NULL,
  CONSTRAINT fk_agreement_member FOREIGN KEY (member_id) REFERENCES member (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE bread_image (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  file_name  CHAR(41)    NOT NULL,                               -- {uuid}.webp
  status     VARCHAR(10) NOT NULL DEFAULT 'TEMP',                -- TEMP / USED
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  INDEX ix_image_cleanup (status, created_at)                    -- 고아 이미지 정리 배치
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE bread (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  name          VARCHAR(30)  NOT NULL,
  category      VARCHAR(20)  NOT NULL,
  price         INT          NOT NULL,
  description   VARCHAR(200) NULL,
  allergens     VARCHAR(100) NULL,                               -- "WHEAT,MILK,EGG"
  image_id      BIGINT       NULL,
  display_order INT          NOT NULL DEFAULT 999,
  active        BOOLEAN      NOT NULL DEFAULT TRUE,
  active_name   VARCHAR(30) GENERATED ALWAYS AS (IF(active, LOWER(REPLACE(name, ' ', '')), NULL)) STORED,
  CONSTRAINT uk_bread_active_name UNIQUE (active_name),          -- 판매 중인 빵끼리만 이름 중복 금지
  CONSTRAINT ck_bread_price CHECK (price BETWEEN 100 AND 100000 AND price % 100 = 0),
  CONSTRAINT fk_bread_image FOREIGN KEY (image_id) REFERENCES bread_image (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE sale_date (
  sale_date DATE     PRIMARY KEY,
  open_at   DATETIME NOT NULL,
  close_at  DATETIME NOT NULL,
  holiday   BOOLEAN  NOT NULL DEFAULT FALSE,
  version   INT      NOT NULL DEFAULT 0,                         -- 수량 설정 ETag
  CONSTRAINT ck_sale_date_window CHECK (open_at < close_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE pickup_slot (
  id        BIGINT AUTO_INCREMENT PRIMARY KEY,
  sale_date DATE     NOT NULL,
  pickup_at DATETIME NOT NULL,
  CONSTRAINT uk_pickup_slot UNIQUE (sale_date, pickup_at),
  CONSTRAINT fk_slot_date FOREIGN KEY (sale_date) REFERENCES sale_date (sale_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE daily_stock (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  sale_date      DATE   NOT NULL,
  bread_id       BIGINT NOT NULL,
  produced_qty   INT    NOT NULL,
  reservable_qty INT    NOT NULL,
  reserved_qty   INT    NOT NULL DEFAULT 0,
  per_user_limit INT    NULL,                                    -- NULL = 한도 없음
  CONSTRAINT uk_daily_stock UNIQUE (sale_date, bread_id),        -- 예약 조건부 UPDATE가 정확히 1행만 잠금
  INDEX ix_daily_stock_catalog (sale_date, bread_id, per_user_limit, reservable_qty),  -- 빵 목록 커버링
  CONSTRAINT ck_stock_range CHECK (reserved_qty >= 0 AND reserved_qty <= reservable_qty
                                   AND reservable_qty <= produced_qty AND produced_qty <= 500),
  CONSTRAINT ck_stock_limit CHECK (per_user_limit IS NULL OR per_user_limit BETWEEN 1 AND 20),
  CONSTRAINT fk_stock_date  FOREIGN KEY (sale_date) REFERENCES sale_date (sale_date),
  CONSTRAINT fk_stock_bread FOREIGN KEY (bread_id)  REFERENCES bread (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE reservation (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  reservation_no  CHAR(9)     NOT NULL,                          -- MMDD-NNNN
  member_id       BIGINT      NOT NULL,
  phone_last4     CHAR(4)     NOT NULL,                          -- 수령 검색용 역정규화
  sale_date       DATE        NOT NULL,
  pickup_slot_id  BIGINT      NOT NULL,
  pickup_at       DATETIME    NOT NULL,                          -- 취소 마감 계산용 복사
  status          VARCHAR(10) NOT NULL,                          -- RESERVED/CANCELED/PICKED_UP/NO_SHOW
  total_price     INT         NOT NULL,
  idempotency_key CHAR(36)    NOT NULL,
  canceled_at DATETIME(3) NULL, canceled_by BIGINT NULL, cancel_reason VARCHAR(100) NULL,
  picked_up_at    DATETIME(3) NULL,
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT uk_reservation_no   UNIQUE (sale_date, reservation_no),        -- 수령 검색
  CONSTRAINT uk_reservation_idem UNIQUE (idempotency_key),                  -- 중복 제출 최종 방어
  INDEX ix_resv_member_list   (member_id, sale_date DESC, id DESC),         -- 내 예약 목록(커서)
  INDEX ix_resv_member_status (member_id, status, sale_date),               -- 하루 3건·30일 노쇼 수(커버링)
  INDEX ix_resv_date_status   (sale_date, status),                          -- 노쇼 배치, 대시보드 건수
  INDEX ix_resv_date_slot     (sale_date, pickup_slot_id, status),          -- 픽업 준비 목록
  INDEX ix_resv_phone4        (sale_date, phone_last4),                     -- 뒷 4자리 검색
  CONSTRAINT fk_resv_member FOREIGN KEY (member_id)      REFERENCES member (id),
  CONSTRAINT fk_resv_slot   FOREIGN KEY (pickup_slot_id) REFERENCES pickup_slot (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE reservation_item (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  reservation_id BIGINT NOT NULL, bread_id BIGINT NOT NULL,
  quantity       INT    NOT NULL, unit_price INT NOT NULL,      -- 예약 당시 가격 스냅샷
  CONSTRAINT uk_item UNIQUE (reservation_id, bread_id),
  CONSTRAINT ck_item_qty CHECK (quantity >= 1),
  CONSTRAINT fk_item_resv  FOREIGN KEY (reservation_id) REFERENCES reservation (id),
  CONSTRAINT fk_item_bread FOREIGN KEY (bread_id)       REFERENCES bread (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE reservation_history (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  reservation_id BIGINT NOT NULL, from_status VARCHAR(10) NULL, to_status VARCHAR(10) NOT NULL,
  actor_id BIGINT NULL, changed_at DATETIME(3) NOT NULL,
  INDEX ix_history_resv (reservation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE outbox_event (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  event_type VARCHAR(40) NOT NULL, payload JSON NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), published_at DATETIME(3) NULL,
  INDEX ix_outbox_pending (published_at, id)                     -- 아직 안 보낸 이벤트 순서대로
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE daily_sales_stat (
  sale_date DATE NOT NULL, bread_id BIGINT NOT NULL,
  reservable_qty INT NOT NULL, reserved_qty INT NOT NULL, canceled_qty INT NOT NULL,
  picked_up_qty INT NOT NULL, no_show_qty INT NOT NULL, sold_out_at DATETIME NULL,
  PRIMARY KEY (sale_date, bread_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE admin_action_log (                                  -- 임시 비밀번호, 직권 취소, 제한 해제, 재계산
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  admin_id BIGINT NOT NULL, action VARCHAR(30) NOT NULL, target_type VARCHAR(20) NOT NULL, target_id VARCHAR(20) NOT NULL,
  detail JSON NULL, created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  INDEX ix_admin_log (target_type, target_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
