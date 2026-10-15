-- F08.7 Phase 9-γ: TODO/プロジェクト 予算紐付テーブル
-- 設計書 F08.7 (v1.2) §5.4 / §5.7 に準拠。
--
-- 主な設計ポイント:
-- - project_id と todo_id は排他（CHECK 制約 chk_tbl_target_xor）
-- - 同一 (project_id, allocation_id) / 同一 (todo_id, allocation_id) は重複禁止
-- - link_amount と link_percentage は排他（両方 NULL = 全額紐付）
-- - currency は Phase 9 では JPY 固定（Phase 10 多通貨対応の拡張ポイント）
--
-- マスター御裁可:
-- - V3.149 (shift_schedules.linked_project_id) は F03.5 軍が先行投入済（PR #282 / 3572d6e0）
-- - 本マイグレーション (V11.032) と V11.035 は数値順で V3.149 の後ろに走る

CREATE TABLE todo_budget_links (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    project_id BIGINT UNSIGNED DEFAULT NULL,
    todo_id BIGINT UNSIGNED DEFAULT NULL,
    allocation_id BIGINT UNSIGNED NOT NULL,
    link_amount DECIMAL(12,0) DEFAULT NULL,
    link_percentage DECIMAL(5,2) DEFAULT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'JPY',
    created_by BIGINT UNSIGNED NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),

    -- インデックス（設計書 §5.4 準拠）
    INDEX idx_tbl_project (project_id),
    INDEX idx_tbl_todo (todo_id),
    INDEX idx_tbl_allocation (allocation_id),

    -- UNIQUE 制約（設計書 §5.4 準拠）
    -- project_id NULL / todo_id NULL の行同士は MySQL 仕様で別扱いとなる。
    -- 排他制約 (chk_tbl_target_xor) で「project_id と todo_id どちらか一方のみ NOT NULL」を
    -- 強制しているため、UNIQUE は実質的に
    --   (project_id, allocation_id) — project 紐付の場合
    --   (todo_id, allocation_id)    — todo 紐付の場合
    -- として機能する。アプリ層で SELECT を併用してダブルチェックする方針は §6.2.4 / Service 層に委ねる。
    UNIQUE KEY uq_tbl_project_alloc (project_id, allocation_id),
    UNIQUE KEY uq_tbl_todo_alloc (todo_id, allocation_id),

    -- CHECK 制約（設計書 §5.4 準拠）
    -- target は project / todo のどちらか一方のみ NOT NULL（XOR）
    CONSTRAINT chk_tbl_target_xor CHECK (
        (project_id IS NOT NULL AND todo_id IS NULL)
        OR
        (project_id IS NULL AND todo_id IS NOT NULL)
    ),
    -- link_amount と link_percentage は排他（両方 NULL = 割当全額）
    CONSTRAINT chk_tbl_link_xor CHECK (
        link_amount IS NULL OR link_percentage IS NULL
    ),
    CONSTRAINT chk_tbl_amount CHECK (
        link_amount IS NULL OR link_amount >= 0
    ),
    CONSTRAINT chk_tbl_percentage CHECK (
        link_percentage IS NULL
        OR (link_percentage >= 0 AND link_percentage <= 100)
    ),

    -- FK 制約
    -- project_id: F02.3 projects テーブル。プロジェクト削除時に紐付も消える
    CONSTRAINT fk_tbl_project FOREIGN KEY (project_id)
        REFERENCES projects(id) ON DELETE CASCADE,
    -- todo_id: F02.3 todos テーブル。TODO 削除時に紐付も消える
    CONSTRAINT fk_tbl_todo FOREIGN KEY (todo_id)
        REFERENCES todos(id) ON DELETE CASCADE,
    -- allocation_id: 紐付が残っている割当の削除を禁止（経理整合性保護）
    CONSTRAINT fk_tbl_allocation FOREIGN KEY (allocation_id)
        REFERENCES shift_budget_allocations(id) ON DELETE RESTRICT,
    -- created_by: ユーザー削除を防止（監査履歴保持）
    CONSTRAINT fk_tbl_creator FOREIGN KEY (created_by)
        REFERENCES users(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
