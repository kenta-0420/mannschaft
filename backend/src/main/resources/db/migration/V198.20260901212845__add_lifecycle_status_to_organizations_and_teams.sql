-- 柱②: 販促プロビジョニング②-1（DDL/エンティティ骨格のみ・挙動不変）
-- organizations / teams に lifecycle_status を追加する。
--
-- 本 PR では PROVISIONED を生成するコードは一切含まない（既定値 ACTIVE のみ）。
-- 作成 API とゲート（PROVISIONED 状態のチーム/組織を通常導線から隠す等）は後続 PR で
-- provisioning_invitations の消化ロジックと同時に入る設計（.claude/campaigns/2026-09-01-org-governance.md 柱②）。
--
-- 既存行は全て ACTIVE として扱われる（DEFAULT 'ACTIVE' により既存データへの影響なし）。

ALTER TABLE organizations
    ADD COLUMN lifecycle_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
        COMMENT 'PROVISIONED/ACTIVE。承諾前の事前作成状態';

ALTER TABLE teams
    ADD COLUMN lifecycle_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
        COMMENT 'PROVISIONED/ACTIVE。承諾前の事前作成状態';
