-- F04.10: 委員会招集トークンテーブル
CREATE TABLE committee_invitations (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    committee_id    BIGINT UNSIGNED NOT NULL            COMMENT 'FK → committees（ON DELETE CASCADE）',
    invitee_user_id BIGINT UNSIGNED NOT NULL            COMMENT 'FK → users（ON DELETE CASCADE）被招集者',
    proposed_role   VARCHAR(20)     NOT NULL DEFAULT 'MEMBER' COMMENT '提案ロール',
    invite_token    VARCHAR(36)     NOT NULL            COMMENT 'UUID。承諾 URL で使用',
    invited_by      BIGINT UNSIGNED NULL                COMMENT 'FK → users（ON DELETE SET NULL）招集者',
    message         TEXT            NULL                COMMENT '招集時のメッセージ',
    expires_at      DATETIME        NOT NULL            COMMENT '承諾期限（デフォルト 7 日後）',
    resolved_at     DATETIME        NULL                COMMENT '承諾/辞退/期限切れが確定した日時',
    resolution      VARCHAR(20)     NULL                COMMENT '結果: ACCEPTED / DECLINED / EXPIRED / CANCELLED',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_committee_invitations_token (invite_token),
    INDEX idx_committee_invitations_pending (committee_id, resolved_at)     COMMENT '未解決招集の抽出',
    INDEX idx_committee_invitations_invitee (invitee_user_id, resolved_at)  COMMENT '自分宛て招集一覧',
    INDEX idx_committee_invitations_expiry (resolved_at, expires_at)        COMMENT '期限切れバッチ',
    CONSTRAINT fk_ci_committee FOREIGN KEY (committee_id) REFERENCES committees (id) ON DELETE CASCADE,
    CONSTRAINT fk_ci_invitee FOREIGN KEY (invitee_user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_ci_invited_by FOREIGN KEY (invited_by) REFERENCES users (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='F04.10: 委員会招集トークン';
