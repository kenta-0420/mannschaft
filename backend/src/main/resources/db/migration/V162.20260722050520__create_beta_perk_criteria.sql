-- =====================================================================
-- F20.3 ベータ特典: beta_perk_criteria（付与条件マスタ）
-- =====================================================================
-- 設計書: docs/features/F20.3_beta_perks/01_data_model.md §2
-- マスタ例外（全テナント共通・書き込みはシスアド運用のみ・全シャード複製）ゆえ複合自然キー
-- （beta_phase, grant_kind）で UUID 化しない（CLAUDE.md 原則 6 例外）。
-- 全指標 NULL 可＝「機構として指標を固定し、有効化は運用値」。全指標 NULL の無条件付与は
-- シスアド CRUD のバリデーション（BETA_PERK_009）で拒否する（DB では許容）。
-- =====================================================================
CREATE TABLE beta_perk_criteria (
    beta_phase TINYINT UNSIGNED NOT NULL COMMENT 'ベータ段階（1〜4）',
    grant_kind VARCHAR(12) NOT NULL COMMENT 'INDIVIDUAL / TEAM_ORG',
    evaluation_window_days INT UNSIGNED NOT NULL DEFAULT 60 COMMENT 'activeDays の評価ウィンドウ（日）',
    min_active_days INT UNSIGNED NULL COMMENT 'アクティブ日数の下限。NULL=この指標を評価しない（F10.8 実装前は NULL 運用）',
    min_membership_tenure_days INT UNSIGNED NULL COMMENT '所属経過日数の下限。NULL=評価しない',
    min_active_members INT UNSIGNED NULL COMMENT 'アクティブ人数の下限（TEAM_ORG のみ意味を持つ）。NULL=評価しない',
    enabled BOOLEAN NOT NULL DEFAULT TRUE COMMENT 'false=このフェーズ×種別の付与を停止（自動バッチ・手動とも）',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (beta_phase, grant_kind),
    CONSTRAINT chk_bpc_phase CHECK (beta_phase BETWEEN 1 AND 4),
    CONSTRAINT chk_bpc_kind CHECK (grant_kind IN ('INDIVIDUAL','TEAM_ORG'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='ベータ特典の付与条件（マスタ例外・複合自然キー・閾値は運用値）';

-- ---------------------------------------------------------------------
-- 初期シード（8 行=4 フェーズ × 2 種別・値は例示/運用値・設計書 01 §2）
--   INDIVIDUAL: window=60, min_active_days=NULL, min_tenure=30, min_members=NULL
--   TEAM_ORG  : window=60, min_active_days=NULL, min_tenure=30, min_members=5
--   min_active_days は F10.8（活動計測）実装後に運用で設定する（それまでは自動付与バッチを本番有効化しない）。
-- ---------------------------------------------------------------------
INSERT INTO beta_perk_criteria
    (beta_phase, grant_kind, evaluation_window_days, min_active_days, min_membership_tenure_days, min_active_members, enabled)
VALUES
    (1, 'INDIVIDUAL', 60, NULL, 30, NULL, TRUE),
    (1, 'TEAM_ORG',   60, NULL, 30, 5,    TRUE),
    (2, 'INDIVIDUAL', 60, NULL, 30, NULL, TRUE),
    (2, 'TEAM_ORG',   60, NULL, 30, 5,    TRUE),
    (3, 'INDIVIDUAL', 60, NULL, 30, NULL, TRUE),
    (3, 'TEAM_ORG',   60, NULL, 30, 5,    TRUE),
    (4, 'INDIVIDUAL', 60, NULL, 30, NULL, TRUE),
    (4, 'TEAM_ORG',   60, NULL, 30, 5,    TRUE);
