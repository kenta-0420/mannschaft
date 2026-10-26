-- F05.1 掲示板: 返信ネスト深さ（depth）カラム追加 + 既存データの backfill
--
-- 背景:
--   設計書 F05.1 §5 はネスト返信を最大5階層に制限する（depth 0〜4。6階層目で 400）。
--   しかし V5.003 の bulletin_replies には depth カラムが無く、createReply に深さ検証も無いため、
--   無制限ネストが可能だった。再帰コスト爆発・DoS の温床となるため depth カラムを追加し、
--   アプリ層（BulletinReplyService）で depth > 4 を 400 エラーとして弾く。
--
-- 対応:
--   1. depth TINYINT UNSIGNED NOT NULL DEFAULT 0 を追加（スレッド直下 = 0）。
--   2. 既存返信の depth を parent_id ツリーから段階 UPDATE で backfill する。
--      最大5階層（depth 0〜4）なので depth1〜4 を順に 4 回の UPDATE で埋める。
--      論理削除済み行（deleted_at IS NOT NULL）も含めて全行を埋める
--      （子返信の depth 計算は親の物理的存在に依存するため、削除フラグでは除外しない）。

-- 1. depth カラム追加（既定 0 = スレッド直下）
ALTER TABLE bulletin_replies
    ADD COLUMN depth TINYINT UNSIGNED NOT NULL DEFAULT 0 AFTER parent_id;

-- 2. 既存データの backfill（段階 UPDATE。depth = 親の depth + 1）
--    parent_id IS NULL の行はステップ 1 で既定 0 のまま。
--    自己結合で「親が depthN なら子を depth(N+1)」に順に更新する。最大4段で打ち止め。

-- depth 1: 親が depth 0（= ルート返信）の子
UPDATE bulletin_replies c
    JOIN bulletin_replies p ON c.parent_id = p.id
SET c.depth = 1
WHERE c.parent_id IS NOT NULL AND p.depth = 0;

-- depth 2: 親が depth 1 の子
UPDATE bulletin_replies c
    JOIN bulletin_replies p ON c.parent_id = p.id
SET c.depth = 2
WHERE c.parent_id IS NOT NULL AND p.depth = 1;

-- depth 3: 親が depth 2 の子
UPDATE bulletin_replies c
    JOIN bulletin_replies p ON c.parent_id = p.id
SET c.depth = 3
WHERE c.parent_id IS NOT NULL AND p.depth = 2;

-- depth 4: 親が depth 3 の子（最深 = 5階層目）
UPDATE bulletin_replies c
    JOIN bulletin_replies p ON c.parent_id = p.id
SET c.depth = 4
WHERE c.parent_id IS NOT NULL AND p.depth = 3;

-- 注意: depth 制限導入前に作られた depth 5 以上（6階層目以降）の既存返信があり得る。
--       それらは backfill 後 depth = 4 で頭打ちになる（TINYINT UNSIGNED で値は保持されるが
--       上記 UPDATE は depth 3 の親までしか辿らないため depth 4 止まり）。
--       新規作成時のみ depth > 4 を 400 で弾く方針（既存データは表示可能なまま温存）。
