-- F03.11 Phase5a: キャンセル料決済リトライ回数カラム追加
ALTER TABLE recruitment_cancellation_records
    ADD COLUMN payment_retry_count TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '決済リトライ回数（最大3回）';
