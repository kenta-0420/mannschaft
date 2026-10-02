-- Issue #2871: fan-out 経路の i18n 化
--
-- 目的: fan-out 耐久ジョブの通知文面を「描画済みの 1 組（日本語固定）」から
--       「配信ロケール 6 種ぶんの描画済み文面」へ広げる。
--
-- 設計（issue #2871 のコメント https://github.com/kenta-0420/mannschaft/issues/2871#issuecomment-5364374661）:
--   - 受信者は enqueue 時点で未確定だが、配信ロケールは ja/en/zh/ko/es/de の 6 種しかない。
--     よって受信者スナップショットを取らずとも「起こりうる文面」は 6 通りで尽きる。
--   - 親表の title / body 列は撤去する（二経路を残さない）。本番に未処理データが存在しないことを
--     マスター確認済みのため、後方互換の移行列は設けない。
--   - 親子とも notification ドメイン内であるため FK / ON DELETE CASCADE を張ってよい
--     （CLAUDE.md アーキテクチャ思想 原則1・原則2。同一ドメイン内の CASCADE は許可）。
--   - 照合順序はスキーマ統一済みの utf8mb4_0900_ai_ci を明示する（V175 の統一以降の規約）。
--     ⚠ 親表 V173 は統一前に作られたため utf8mb4_unicode_ci と書かれているが、
--       V175 がスキーマ全体を変換済みであり、V173 を前例として真似てはならない。

CREATE TABLE notification_fanout_job_messages (
    id        BINARY(16)    NOT NULL          COMMENT 'UUIDv7 主キー',
    job_id    BINARY(16)    NOT NULL          COMMENT '親 notification_fanout_jobs.id（同一ドメイン内 FK）',
    locale    VARCHAR(10)   NOT NULL          COMMENT '配信ロケール（ja/en/zh/ko/es/de の 6 種）',
    title     VARCHAR(200)  NOT NULL          COMMENT '描画済みタイトル（enqueue 時にコードポイント境界で切り詰め済み）',
    body      VARCHAR(1000) NULL              COMMENT '描画済み本文（enqueue 時にコードポイント境界で切り詰め済み）',

    PRIMARY KEY (id),
    UNIQUE KEY uk_fanout_job_message_locale (job_id, locale)
        COMMENT '1 ジョブ × 1 ロケールにつき文面は 1 行（再入・再開時の二重挿入を DB で拒否）',
    CONSTRAINT fk_fanout_job_message_job FOREIGN KEY (job_id)
        REFERENCES notification_fanout_jobs (id) ON DELETE CASCADE
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='fan-out ジョブのロケール別・描画済み文面（Issue #2871）';

-- 親表の描画済み文面列を撤去する（文面の正本を子表 1 箇所へ統一）。
ALTER TABLE notification_fanout_jobs DROP COLUMN title;
ALTER TABLE notification_fanout_jobs DROP COLUMN body;
