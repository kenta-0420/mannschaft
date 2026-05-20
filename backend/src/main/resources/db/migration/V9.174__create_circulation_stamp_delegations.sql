-- F05.2 Phase 11 第三陣 3-B
-- 押印委任テーブル。受信者が他のユーザー（代理人）に押印を委任する。
-- 委任後、代理人は委任者の代わりに押印できる。委任は1人の代理人に対して1回まで。
--
-- CLAUDE.md 原則 6 適用: 新規テーブルは UUIDv7 主キー（BINARY(16)）。
-- 子は同一ドメインのため CASCADE 削除を許可。代理人 user_id はクロスドメイン参照のため
-- FK は張らずインデックスのみ（CLAUDE.md 原則 1）。

CREATE TABLE circulation_stamp_delegations (
    id BINARY(16) NOT NULL COMMENT 'UUIDv7 主キー',
    document_id BIGINT UNSIGNED NOT NULL COMMENT 'circulation_documents.id',
    delegator_user_id BIGINT UNSIGNED NOT NULL COMMENT '委任者（元の受信者）の user_id',
    delegatee_user_id BIGINT UNSIGNED NOT NULL COMMENT '代理人（押印を委ねられた者）の user_id',
    reason VARCHAR(255) NULL COMMENT '委任理由（任意）',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / REVOKED / FULFILLED',
    revoked_at DATETIME NULL,
    fulfilled_at DATETIME NULL COMMENT '代理人が押印を完了した日時',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_csd_doc_delegator (document_id, delegator_user_id),
    KEY idx_csd_delegatee (delegatee_user_id),
    KEY idx_csd_document (document_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='F05.2: 押印委任';
