-- F09.17 Phase 11-b δ: 自動 NG 辞書テーブル
-- AdContentModerator が submit 時に body_markdown を照合する辞書を保持する。
-- 将来 SYSTEM_ADMIN が UI から CRUD できる拡張余地を確保。
-- クロスドメインFK禁止: created_by_user_id は FK なし INDEX のみ。
CREATE TABLE ad_ng_words (
    id                  BINARY(16)       NOT NULL,
    word                VARCHAR(100)     NOT NULL COMMENT '照合対象ワード (大文字小文字無視)',
    category            VARCHAR(50)      NOT NULL COMMENT 'PHARMA / SUPERLATIVE / FINANCIAL_RISK / DISCRIMINATION / OTHER',
    severity            ENUM('WARN','BLOCK') NOT NULL DEFAULT 'WARN' COMMENT 'WARN=AUTO_FLAGGED, BLOCK=自動 BLOCKED',
    note                VARCHAR(500)     NULL     COMMENT '辞書登録理由 (運用メモ)',
    is_active           BOOLEAN          NOT NULL DEFAULT TRUE COMMENT '有効/無効フラグ (論理削除代替)',
    created_by_user_id  BIGINT UNSIGNED  NULL     COMMENT 'SYSTEM_ADMIN user_id (FKなし)',
    created_at          DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_ang_word (word),
    INDEX idx_ang_active_category (is_active, category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='F09.17 自動 NG 辞書 (薬機法・景表法・金融・差別・雑カテゴリ)';
