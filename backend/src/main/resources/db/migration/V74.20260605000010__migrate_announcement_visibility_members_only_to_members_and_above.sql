-- W2 可視性ラダー正準化: announcement の「内輪」可視性を正準名へ移行する。
--
-- 背景:
--   お知らせフィード（F02.6）は独自 String 値で可視性を保持しており、
--   「応援者に見せない内輪」を旧値 'MEMBERS_ONLY' で表現していた。
--   この挙動（SUPPORTER 除外）は正準ラダー StandardVisibility.MEMBERS_AND_ABOVE
--   （hasRoleOrAbove(MEMBER) / SUPPORTER・GUEST 除外）と同一閾値である。
--   名称のみ正準ラダー名 'MEMBERS_AND_ABOVE' に揃える（挙動不変）。
--
-- 対象列:
--   1. announcement_feeds.visibility           VARCHAR(30)
--   2. announcement_range_templates.target_role ENUM(...)
--
-- 冪等性:
--   - announcement_feeds は VARCHAR のため UPDATE は二重実行しても無害
--     （対象行は初回で消える → 再実行は 0 行）。
--   - announcement_range_templates は ENUM。既存 DB では enum 定義に
--     'MEMBERS_ONLY' が残っているため、まず両値を含む拡張 ENUM へ ALTER して
--     UPDATE を有効化し、UPDATE 後に旧値を除いた最終 ENUM へ ALTER する。
--     新規 from-scratch 環境では V19.006 が既に最終 ENUM で列を作っているが、
--     下記 ALTER は最終形へ収束するため二重適用しても結果は同じ（収束的・冪等）。
--   - 既存 from-scratch 環境（V13.019 が新 DEFAULT で列作成）でも UPDATE は
--     0 行ヒットで無害。

-- 1) announcement_feeds.visibility: VARCHAR 値の置換 + DEFAULT 変更
UPDATE announcement_feeds
   SET visibility = 'MEMBERS_AND_ABOVE'
 WHERE visibility = 'MEMBERS_ONLY';

ALTER TABLE announcement_feeds
    MODIFY COLUMN visibility VARCHAR(30) NOT NULL DEFAULT 'MEMBERS_AND_ABOVE'
        COMMENT '閲覧可能範囲（元コンテンツから継承・同期）。値: PUBLIC / SUPPORTERS_AND_ABOVE / MEMBERS_AND_ABOVE（内輪・応援者除外）';

-- 2) announcement_range_templates.target_role: ENUM 値の移行
--    (a) 旧値 'MEMBERS_ONLY' と新値 'MEMBERS_AND_ABOVE' の両方を含む拡張 ENUM へ。
--        これにより既存行（'MEMBERS_ONLY'）を保持したまま UPDATE 可能にする。
ALTER TABLE announcement_range_templates
    MODIFY COLUMN target_role
        ENUM ('MEMBERS_ONLY', 'MEMBERS_AND_ABOVE', 'SUPPORTERS_AND_ABOVE', 'PUBLIC')
        NOT NULL DEFAULT 'MEMBERS_AND_ABOVE'
        COMMENT '告知対象ロール（移行中: 旧 MEMBERS_ONLY を一時的に許容）';

--    (b) 既存データを新値へ移行。
UPDATE announcement_range_templates
   SET target_role = 'MEMBERS_AND_ABOVE'
 WHERE target_role = 'MEMBERS_ONLY';

--    (c) 旧値を取り除いた最終 ENUM へ収束。
ALTER TABLE announcement_range_templates
    MODIFY COLUMN target_role
        ENUM ('MEMBERS_AND_ABOVE', 'SUPPORTERS_AND_ABOVE', 'PUBLIC')
        NOT NULL DEFAULT 'MEMBERS_AND_ABOVE'
        COMMENT '告知対象ロール（MEMBERS_AND_ABOVE=内輪・応援者除外）';
