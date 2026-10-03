-- issue #2616 ブログ予約公開バッチ: 公開時刻に達した予約記事の走査用 索引
--
-- 予約中の記事は status = 'DRAFT' のまま published_at に未来時刻を持つ（PostStatus.SCHEDULED は新設しない）。
-- 予約公開バッチ（BlogScheduledPublishService.findDuePostIds・1分間隔）は
--   WHERE status = 'DRAFT' AND published_at IS NOT NULL AND published_at <= NOW() AND deleted_at IS NULL
--   ORDER BY published_at ASC, id ASC LIMIT 500
-- で走査する。
--
-- 既存索引（idx_bp_team_status / idx_bp_org_status 等）はいずれも team_id・organization_id といった
-- スコープ列が先頭にあり、スコープ横断で status から入る本クエリでは使えない（全表走査になる）。
-- 等値列（status）→ 範囲・ソート列（published_at）の順に並べた複合索引を足し、
-- 1分ごとの走査を「対象が無ければ索引の先頭数行を見るだけ」で終わらせる。
--
-- 同一ドメイン内・追加のみ・低リスク（DROP/変更なし）。クロスドメイン FK は張らない（原則1）。
CREATE INDEX idx_bp_status_published_at
    ON blog_posts (status, published_at);
