-- ストレージサブスクリプション（スコープ別プラン紐付け + リアルタイム使用量追跡）テーブルを作成
-- 各スコープ（組織・チーム・個人）が現在契約しているプランと使用量をリアルタイムで管理する
-- 設計書: docs/cross-cutting/storage_quota.md 参照
--
-- 依存テーブル: storage_plans（V9.069 で作成済み）
CREATE TABLE storage_subscriptions (
    id                       BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    scope_type               VARCHAR(20)      NOT NULL COMMENT 'スコープ種別: ORGANIZATION / TEAM / PERSONAL',
    scope_id                 BIGINT UNSIGNED  NOT NULL COMMENT 'スコープ ID（organizations.id / teams.id / users.id）',
    plan_id                  BIGINT UNSIGNED  NOT NULL COMMENT 'FK → storage_plans',
    used_bytes               BIGINT UNSIGNED  NOT NULL DEFAULT 0 COMMENT '現在の使用量（bytes）。ファイルアップロード・削除時にアトミック更新',
    file_count               INT UNSIGNED     NOT NULL DEFAULT 0 COMMENT '現在のファイル数。アップロード・削除時にアトミック更新',
    last_notified_threshold  SMALLINT         NULL     COMMENT '最後に通知した閾値（%）: 80 / 90 / 100。同じ閾値の重複通知を防止。使用率が閾値を下回ったらリセット',
    created_at               DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_ss_scope (scope_type, scope_id),
    INDEX idx_ss_plan (plan_id),
    CONSTRAINT fk_ss_plan FOREIGN KEY (plan_id) REFERENCES storage_plans (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='スコープ別ストレージサブスクリプション・使用量追跡テーブル';

-- ストレージ使用量変動履歴テーブルを作成
-- デバッグ・監査・使用量推移グラフに使用する。INSERT のみ（UPDATE / DELETE 不可）
-- 保持期間: 1年（日次バッチで1年以上前のレコードを物理削除）
CREATE TABLE storage_usage_logs (
    id               BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    subscription_id  BIGINT UNSIGNED  NOT NULL COMMENT 'FK → storage_subscriptions',
    delta_bytes      BIGINT           NOT NULL COMMENT '変動量（正=増加、負=減少）',
    after_bytes      BIGINT UNSIGNED  NOT NULL COMMENT '変動後の使用量（bytes）',
    feature_type     VARCHAR(30)      NOT NULL COMMENT '機能種別: FILE_SHARING / CIRCULATION / BULLETIN / CHAT / TIMELINE / CMS / GALLERY',
    reference_type   VARCHAR(50)      NOT NULL COMMENT '参照先テーブル名（例: shared_files, circulation_attachments）',
    reference_id     BIGINT UNSIGNED  NOT NULL COMMENT '参照先レコード ID',
    action           VARCHAR(20)      NOT NULL COMMENT '操作種別: UPLOAD / DELETE / VERSION_ADD / VERSION_DELETE / DRIFT_CORRECTION',
    actor_id         BIGINT UNSIGNED  NULL     COMMENT 'FK → users（操作者。バッチ処理の場合は NULL）',
    created_at       DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_sul_sub (subscription_id, created_at DESC),
    INDEX idx_sul_feature (feature_type, created_at DESC),
    CONSTRAINT fk_sul_subscription FOREIGN KEY (subscription_id) REFERENCES storage_subscriptions (id) ON DELETE CASCADE,
    CONSTRAINT fk_sul_actor FOREIGN KEY (actor_id) REFERENCES users (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='ストレージ使用量変動履歴テーブル（INSERT のみ、1年保持）';
