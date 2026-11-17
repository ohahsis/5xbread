-- 아웃박스 실패 처리 (decisions.md 6장 "아웃박스 실패 한도")
-- attempts: 처리 실패 횟수, last_error: 마지막 실패(예외 클래스와 메시지, 500자에서 자름)
-- dead_at: 실패가 한도(fivexbread.outbox.max-attempts)에 닿은 시각. 채워지면 폴러·바로 처리가 더 집지 않는다
ALTER TABLE outbox_event
  ADD COLUMN attempts INT NOT NULL DEFAULT 0,
  ADD COLUMN last_error VARCHAR(500) NULL,
  ADD COLUMN dead_at DATETIME(3) NULL,
  DROP INDEX ix_outbox_pending,
  ADD INDEX ix_outbox_pending (published_at, dead_at, id);     -- 미발행·살아 있는 행을 id 순으로
