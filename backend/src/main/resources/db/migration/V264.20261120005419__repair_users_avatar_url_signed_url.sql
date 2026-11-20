-- CMP-261120-0200: users.avatar_url が署名付き URL（入れ子を含む）で上書きされた破損の修復（DDL なし・データ修復のみ）
--
-- 原因: 旧 FE（設定 > 言語）が GET /users/me の署名付き avatarUrl を PUT /users/me へ送り返し、BE が生キーとして保存していた。
-- 次の GET で再署名されて入れ子になり、VARCHAR(500) を超えると 500 になる。PUT /users/me は avatarUrl を受け付けない
-- よう改めたため、既存の破損行をここで修復する。
--
-- 最終的に残してよいのは「本人の正規アイコンキー」だけ（ProfileMediaService#commit が許す形式: user/{id}/icon/<ファイル名>）。
--   1. 既に本人の生キー                      → 不変
--   2. http(s) URL でパスがちょうど /<bucket>/<本人の正規キー>（その後は ? か終端）→ キーへ復元（R2 の path-style 署名付き URL）
--   3. 上記以外（クエリ内だけの一致・別パスの部分一致・他人キー・入れ子・解析不能）    → NULL
-- 'c' フラグで大文字小文字を区別する（照合順序 utf8mb4_0900_ai_ci の REGEXP は既定で大小無視のため）。
--
-- 想定件数・影響: users 全行の走査（avatar_url が非 NULL の行のみ。件数はユーザー数規模）。更新対象は破損行のみで、
-- ロックは該当 users 行の行ロック。単一文 UPDATE のため途中失敗しても部分適用にならない。ロールバック（元の署名付き URL への復元）は
-- 署名付き URL が失効済みで意味を持たないため用意しない。
UPDATE users
   SET avatar_url = CASE
         WHEN REGEXP_LIKE(avatar_url,
              CONCAT('^https?://[^/?#]+/[^/?#]+/user/', id, '/icon/[^?/#]+([?].*)?$'), 'c')
           THEN REGEXP_SUBSTR(avatar_url, CONCAT('user/', id, '/icon/[^?/#]+'), 1, 1, 'c')
         ELSE NULL
       END
 WHERE avatar_url IS NOT NULL
   AND NOT REGEXP_LIKE(avatar_url, CONCAT('^user/', id, '/icon/[^?/#]+$'), 'c');
