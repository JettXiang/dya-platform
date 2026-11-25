-- A01/A03：run 业务日、作废终态，day_final 记录作废与额外 run

ALTER TABLE runs
  ADD COLUMN voided_at DATETIME(3) DEFAULT NULL AFTER clock_skew_flag,
  ADD COLUMN void_reason VARCHAR(255) DEFAULT NULL AFTER voided_at,
  ADD COLUMN void_request_id VARCHAR(64) DEFAULT NULL AFTER void_reason,
  ADD COLUMN void_request_hash CHAR(64) DEFAULT NULL AFTER void_request_id,
  ADD UNIQUE KEY uk_runs_void_request (account_id, void_request_id);

ALTER TABLE day_finals
  ADD COLUMN voided_runs JSON DEFAULT NULL AFTER complete,
  ADD COLUMN unexpected_runs JSON DEFAULT NULL AFTER voided_runs;
