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
--
-- 【根治の経緯（本番クラッシュ防止）】
--   旧実装は「旧CHECK制約（chk_teams_visibility = PUBLIC/ORGANIZATION_ONLY/PRIVATE、V2.004 由来）を
--   DROP せずに新値 GUESTS_AND_ABOVE へ UPDATE」していたため、CHECK 違反で初回適用がクラッシュした。
--   from-scratch CI は teams が 0 行のため UPDATE が 0 行となり制約違反に当たらず素通りし、
--   既存データを持つ本番/staging でのみ破綻する盲点だった。
--   本根治では「旧CHECK を先に DROP → 値を移行 → 列型を ENUM 最終形へ収束」の順序とし、
--   空DB（from-scratch）・既存データDB（本番/staging）の双方で初回適用が必ず成功するようにする。

-- Step1: 旧 CHECK 制約を先に DROP する（新値への UPDATE が制約違反にならないようにする）
-- chk_teams_visibility は V2.004（teams 作成時）で全環境に作成済みのため存在前提でよい。
-- MySQL 8.0.16+ は CHECK 制約を実効化するため、DROP せずに範囲外の値を入れると 3819 で失敗する。
ALTER TABLE teams
    DROP CHECK chk_teams_visibility;

-- Step2: 既存データを新しい値に変換（ENUM変更前に必須）
-- 論理削除済みを含むすべての行を変換する
-- PUBLIC は新 ENUM にも存在するためそのまま保持する。
UPDATE teams
SET visibility = 'GUESTS_AND_ABOVE',
    updated_at = NOW()
WHERE visibility IN ('ORGANIZATION_ONLY', 'PRIVATE');

-- Step3: 列型を最終形（ENUM 新4値）へ収束させる
-- ENUM 自体が許容値を強制するため、別途 CHECK 制約は追加しない（冗長 CHECK を作らない）。
-- dev は既にこの形（success・移行済）であり、本番/staging もここへ収束させて環境間を整合させる。
ALTER TABLE teams
    MODIFY COLUMN visibility ENUM('PUBLIC', 'GUESTS_AND_ABOVE', 'SUPPORTERS_AND_ABOVE', 'MEMBERS_AND_ABOVE')
        NOT NULL DEFAULT 'GUESTS_AND_ABOVE';
