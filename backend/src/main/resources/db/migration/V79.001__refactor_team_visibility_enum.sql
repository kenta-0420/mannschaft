-- チームの visibility ENUM をロールベース設計に修正
-- PRIVATE / ORGANIZATION_ONLY は組織概念に依存した誤った設計だったため、
-- チーム内のロール（メンバー/サポーター/ゲスト/パブリック）で分けた値に統一する。
--
-- 移行ルール:
--   PRIVATE          → GUESTS_AND_ABOVE（GUESTを含む全所属メンバーが見られる）
--   ORGANIZATION_ONLY → GUESTS_AND_ABOVE（同上）
--   PUBLIC           → そのまま
--
-- F00 StandardVisibility マッピング:
--   PUBLIC             → StandardVisibility.PUBLIC
--   GUESTS_AND_ABOVE   → StandardVisibility.SCOPE_AFFILIATED
--   SUPPORTERS_AND_ABOVE → StandardVisibility.SUPPORTERS_AND_ABOVE
--   MEMBERS_AND_ABOVE  → StandardVisibility.MEMBERS_AND_ABOVE

-- Step1: 既存データを新しい値に変換（ENUM変更前に必須）
-- 論理削除済みを含むすべての行を変換する
UPDATE teams
SET visibility = 'GUESTS_AND_ABOVE',
    updated_at = NOW()
WHERE visibility IN ('ORGANIZATION_ONLY', 'PRIVATE');

-- Step2: ENUM 定義を変更
ALTER TABLE teams
    MODIFY COLUMN visibility ENUM('PUBLIC', 'GUESTS_AND_ABOVE', 'SUPPORTERS_AND_ABOVE', 'MEMBERS_AND_ABOVE')
        NOT NULL DEFAULT 'GUESTS_AND_ABOVE';
