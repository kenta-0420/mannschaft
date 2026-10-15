-- F02.5 Phase 4-α: 組織スコープ投稿フィールドを action_memos に追加
--
-- organization_id: 組織スコープで共有する先の組織 ID（NULL = 個人/チームスコープのみ）
-- org_visibility:  'TEAM_ONLY' | 'ORG_WIDE'（NULL = 未指定 = 個人スコープ）
--
-- 修正: organization_id を BIGINT UNSIGNED に変更（organizations.id は BIGINT UNSIGNED のため型一致が必須）

ALTER TABLE action_memos
    ADD COLUMN organization_id BIGINT UNSIGNED NULL COMMENT '組織スコープ投稿先組織 ID',
    ADD COLUMN org_visibility  VARCHAR(20)      NULL COMMENT '組織公開範囲（TEAM_ONLY/ORG_WIDE）';

ALTER TABLE action_memos
    ADD CONSTRAINT fk_action_memos_organization
        FOREIGN KEY (organization_id) REFERENCES organizations (id)
            ON DELETE SET NULL;
