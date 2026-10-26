-- V9.074: F01.5 フレンドチームコンテンツ転送履歴
-- is_revoked フラグ方式で冪等性担保（revoked_at NULL 判定の問題を回避）
--
-- 依存テーブル: timeline_posts (V4.001), teams (V2.004), users (V1.001)
-- 設計書: docs/features/F01.5_team_friend_relationships.md §4.4
--
-- Service 層のフロー（設計書 §6.2 に準拠するが、DB 整合性を優先して順序を調整）:
--   1. timeline_posts に新投稿を INSERT → 新投稿 ID を取得
--   2. friend_content_forwards に INSERT（forwarded_post_id に新投稿 ID を設定）
--   これにより forwarded_post_id は NOT NULL で INSERT 可能。
--   二重転送時の UNIQUE 制約違反は timeline_posts INSERT 前にトランザクション先頭で SELECT チェックし、
--   それでも競合した場合は Service 層で rollback（timeline_posts INSERT も取り消す）。

CREATE TABLE friend_content_forwards (
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    source_post_id     BIGINT UNSIGNED NOT NULL COMMENT 'FK -> timeline_posts.id（転送元）',
    source_team_id     BIGINT UNSIGNED NOT NULL COMMENT 'FK -> teams.id（投稿元チーム）',
    forwarding_team_id BIGINT UNSIGNED NOT NULL COMMENT 'FK -> teams.id（転送実行チーム）',
    forwarded_post_id  BIGINT UNSIGNED NOT NULL COMMENT 'FK -> timeline_posts.id（転送先投稿）',
    target             VARCHAR(30)     NOT NULL COMMENT 'MEMBER / MEMBER_AND_SUPPORTER (Phase 1 は MEMBER のみ)',
    comment            VARCHAR(500)    NULL,
    is_revoked         BOOLEAN         NOT NULL DEFAULT FALSE COMMENT '取消フラグ',
    forwarded_by       BIGINT UNSIGNED NOT NULL COMMENT 'FK -> users.id（転送実行者）',
    revoked_by         BIGINT UNSIGNED NULL     COMMENT 'FK -> users.id（取消実行者）',
    forwarded_at       DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    revoked_at         DATETIME(6)     NULL,
    created_at         DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at         DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    -- 同一ペアの二重転送を冪等性担保するため、is_revoked を UNIQUE に含める。
    -- 取消済み (TRUE) の古い行は UNIQUE 衝突を起こさず、再転送 (FALSE) を許容する。
    CONSTRAINT uq_fcf_active UNIQUE (source_post_id, forwarding_team_id, is_revoked),
    CONSTRAINT fk_fcf_source_post     FOREIGN KEY (source_post_id)     REFERENCES timeline_posts (id) ON DELETE CASCADE,
    CONSTRAINT fk_fcf_source_team     FOREIGN KEY (source_team_id)     REFERENCES teams (id)          ON DELETE CASCADE,
    CONSTRAINT fk_fcf_forwarding_team FOREIGN KEY (forwarding_team_id) REFERENCES teams (id)          ON DELETE CASCADE,
    CONSTRAINT fk_fcf_forwarded_post  FOREIGN KEY (forwarded_post_id)  REFERENCES timeline_posts (id) ON DELETE CASCADE,
    CONSTRAINT fk_fcf_forwarded_by    FOREIGN KEY (forwarded_by)       REFERENCES users (id)          ON DELETE RESTRICT,
    CONSTRAINT fk_fcf_revoked_by      FOREIGN KEY (revoked_by)         REFERENCES users (id)          ON DELETE SET NULL,
    INDEX idx_fcf_source_post      (source_post_id),
    INDEX idx_fcf_source_team      (source_team_id, forwarded_at DESC),
    INDEX idx_fcf_forwarding_team  (forwarding_team_id, forwarded_at DESC),
    INDEX idx_fcf_forwarded_by     (forwarded_by)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='フレンドチームコンテンツ転送履歴';
