-- F22.1 市（Market）統一決済 R1: 手数料パターンのマスタ表（率%＋固定額¥・自然キー policy_key）
-- 設計書: docs/features/F22.1_market/payment/01_data_model.md §3.6
--
-- CLAUDE.md「マスタテーブル例外」に該当（全テナント共通の参照データ・書込はシスアド運用のみ・税率表と同型）
-- ため、主キーは UUIDv7 でなく自然キー policy_key とする（原則6 例外）。
-- escrow_transactions.fee_policy_key の焼き付け参照先（FK は張らず論理参照＝料率改定で過去取引が壊れない不変性優先）。
CREATE TABLE fee_policies (
    policy_key      VARCHAR(40)     NOT NULL COMMENT 'PK・自然キー（DEFAULT / RECRUITMENT_HELPER 等）',
    display_name    VARCHAR(80)     NOT NULL COMMENT '管理画面表示名（管理者向け）',
    percent_rate    DECIMAL(6,4)    NOT NULL COMMENT '総手数料の率（0.0500=5%・0 ≤ rate < 1）',
    flat_fee_minor  INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '総手数料の固定額（円・最小単位・0 で率のみ）',
    enabled         BOOLEAN         NOT NULL DEFAULT TRUE COMMENT '無効化フラグ（新規割当・解決から除外）',
    description     VARCHAR(500)    NULL     COMMENT '補足説明（運用メモ）',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '料率改定時刻（改定は新規徴収のみ反映・遡及しない）',
    PRIMARY KEY (policy_key),
    CONSTRAINT chk_fp_percent CHECK (percent_rate >= 0 AND percent_rate < 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='F22.1 手数料パターンのマスタ（率%＋固定額¥・マスタ例外）';

-- 初期シード: DEFAULT（率5%＋固定0＝既存挙動と完全一致・後方互換・解決フォールバックの終端・削除不可）。
INSERT INTO fee_policies (policy_key, display_name, percent_rate, flat_fee_minor, enabled, description)
VALUES ('DEFAULT', '標準（率5%・折半）', 0.0500, 0, TRUE, '既定の手数料パターン。総手数料=額面×5%、支払者・受取側で折半');
