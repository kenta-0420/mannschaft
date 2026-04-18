-- F04.10: 委員会メンバーシップテーブル
CREATE TABLE committee_members (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    committee_id    BIGINT UNSIGNED NOT NULL            COMMENT 'FK → committees（ON DELETE CASCADE）',
    user_id         BIGINT UNSIGNED NOT NULL            COMMENT 'FK → users（ON DELETE CASCADE）',
    role            VARCHAR(20)     NOT NULL DEFAULT 'MEMBER' COMMENT '委員会内ロール: CHAIR / VICE_CHAIR / SECRETARY / MEMBER',
    joined_at       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '参加成立日時',
    left_at         DATETIME        NULL                COMMENT '離脱日時。NULL = 現役メンバー',
    invited_by      BIGINT UNSIGNED NULL                COMMENT 'FK → users（ON DELETE SET NULL）招集者',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_committee_members_active (committee_id, user_id, left_at) COMMENT '現役は1人1レコード、離脱後は再加入可',
    INDEX idx_committee_members_user (user_id, left_at)                     COMMENT '自分が所属する委員会一覧',
    INDEX idx_committee_members_committee_role (committee_id, role, left_at) COMMENT 'ロール別メンバー抽出',
    CONSTRAINT fk_cm_committee FOREIGN KEY (committee_id) REFERENCES committees (id) ON DELETE CASCADE,
    CONSTRAINT fk_cm_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_cm_invited_by FOREIGN KEY (invited_by) REFERENCES users (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='F04.10: 委員会メンバーシップ';
