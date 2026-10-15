-- F09.14 Phase 3-A: 重説書自動削除バッチログテーブル
--
-- 用途:
--   disclosure_exports.expires_at を過ぎた出力履歴を自動削除（論理削除 + R2 物理ファイル削除）する
--   バッチの実行履歴を記録する。Phase 3-E（自動削除バッチ部隊）で使用予定。
--
-- 設計書 §5.7 出力ファイル保管期間:
--   デフォルト 90日、ADMIN による延長で最大 7年。期限到来時に自動削除する。
CREATE TABLE disclosure_auto_delete_batch_logs (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    batch_run_at    DATETIME        NOT NULL,
    total_expired   INT             NOT NULL DEFAULT 0,
    total_deleted   INT             NOT NULL DEFAULT 0,
    failed_count    INT             NOT NULL DEFAULT 0,
    error_details   TEXT            NULL,
    created_at      DATETIME        NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_dadbl_batch_run (batch_run_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
