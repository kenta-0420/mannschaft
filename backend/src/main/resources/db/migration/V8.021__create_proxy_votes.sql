-- F08.3: 議決権行使・委任状 — 投票回答テーブル
CREATE TABLE proxy_votes (
    id              BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    motion_id       BIGINT UNSIGNED  NOT NULL COMMENT 'FK → proxy_vote_motions',
    user_id         BIGINT UNSIGNED  NOT NULL COMMENT 'FK → users。投票権の所有者（本人投票 = 本人 / 代理投票 = 委任者）',
    vote_type       VARCHAR(20)      NOT NULL COMMENT '投票内容（APPROVE / REJECT / ABSTAIN）',
    is_proxy_vote   BOOLEAN          NOT NULL DEFAULT FALSE COMMENT '委任による代理投票か',
    delegation_id   BIGINT UNSIGNED  NULL     COMMENT 'FK → proxy_delegations。代理投票の場合に紐付け',
    voted_at        DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '投票日時',

    PRIMARY KEY (id),
    UNIQUE KEY uq_pv_motion_user (motion_id, user_id),
    INDEX idx_pv_motion (motion_id, vote_type),
    INDEX idx_pv_user (user_id),

    CONSTRAINT fk_pv_motion FOREIGN KEY (motion_id) REFERENCES proxy_vote_motions(id) ON DELETE CASCADE,
    CONSTRAINT fk_pv_user   FOREIGN KEY (user_id)   REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='F08.3 投票回答';

-- F08.3: 議決権行使・委任状 — 委任状テーブル
CREATE TABLE proxy_delegations (
    id                  BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    session_id          BIGINT UNSIGNED  NOT NULL COMMENT 'FK → proxy_vote_sessions',
    delegator_id        BIGINT UNSIGNED  NOT NULL COMMENT 'FK → users。委任者',
    delegate_id         BIGINT UNSIGNED  NULL     COMMENT 'FK → users。代理人。NULL = 白紙委任',
    is_blank            BOOLEAN          NOT NULL DEFAULT FALSE COMMENT '白紙委任フラグ',
    electronic_seal_id  BIGINT UNSIGNED  NULL     COMMENT 'FK → electronic_seals',
    reason              VARCHAR(500)     NULL     COMMENT '委任理由',
    status              VARCHAR(20)      NOT NULL DEFAULT 'SUBMITTED' COMMENT 'ステータス（SUBMITTED / ACCEPTED / REJECTED / CANCELLED）',
    reviewed_by         BIGINT UNSIGNED  NULL     COMMENT 'FK → users。承認/却下した管理者',
    reviewed_at         DATETIME         NULL     COMMENT '承認/却下日時',
    created_at          DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    UNIQUE KEY uq_pd_session_delegator (session_id, delegator_id),
    INDEX idx_pd_session (session_id, status),
    INDEX idx_pd_delegate (delegate_id),

    CONSTRAINT fk_pd_session   FOREIGN KEY (session_id)   REFERENCES proxy_vote_sessions(id) ON DELETE CASCADE,
    CONSTRAINT fk_pd_delegator FOREIGN KEY (delegator_id) REFERENCES users(id),
    CONSTRAINT fk_pd_delegate  FOREIGN KEY (delegate_id)  REFERENCES users(id),
    CONSTRAINT fk_pd_reviewer  FOREIGN KEY (reviewed_by)  REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='F08.3 委任状';

-- proxy_votes.delegation_id への FK（proxy_delegations テーブル作成後に追加）
ALTER TABLE proxy_votes
    ADD CONSTRAINT fk_pv_delegation FOREIGN KEY (delegation_id) REFERENCES proxy_delegations(id);
