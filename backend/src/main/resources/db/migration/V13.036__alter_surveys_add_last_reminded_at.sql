-- F05.4 督促 API: 最終督促時刻を追加
-- クールダウン判定（連続督促の抑制）に利用する。
-- 既存レコードは NULL のまま（一度も督促していない状態）でよい。
ALTER TABLE surveys
    ADD COLUMN last_reminded_at TIMESTAMP NULL DEFAULT NULL
        COMMENT '最終督促送信時刻。NULL = 一度も督促していない'
        AFTER manual_remind_count;
