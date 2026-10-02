-- F17.2 Wave3 機能⑥「村人ミニプロフィールの所属村一覧」骨格。
-- village_memberships に「この村への所属を所属村一覧に公開してよいか」の
-- 公開トグル列 profile_public を追加し、既存行を裁可済み条件で backfill する。
--
-- 【設計根拠】docs/features/F17.2_village_events_activation.md §9.2 / §13.1 / §13.4
--
-- 【採番】
--   major=160 = origin/main 全体の最大 major=159（V159 meetup_candidate_time）の次。
--   minor はタイムスタンプ（date -u '+%Y%m%d%H%M%S'）= 20260721095957。連番禁止（番人 FlywayTimestampNamingGuardTest）。
--
-- 【原則準拠】
--   - villages と village_memberships は同一 village ドメイン → JOIN UPDATE はクロスドメインでない（原則1/2）。
--   - 既定値は FALSE（非公開）。プライバシー保守的に「伏せる側」に倒す（§9.2 末尾の注記）。

-- ============================================================================
-- (1) 列追加
--     既定 FALSE = 非公開。既存の全行はまず DEFAULT FALSE で埋まる（＝どの村所属も
--     既定では所属村一覧に出さない）。この後 (2) の backfill で「最も開かれた村」の
--     現役所属だけを TRUE へ引き上げる。
-- ============================================================================
ALTER TABLE village_memberships
    ADD COLUMN profile_public BOOLEAN NOT NULL DEFAULT FALSE
        COMMENT 'この村への所属を所属村一覧（GET /users/{id}/villages）に公開してよいか（既定 FALSE=非公開）'
        AFTER banned_reason;

-- ============================================================================
-- (2) backfill（§9.2 の裁可済み条件）
--     所属先の村が「誰でも見つけて即入れる」= visibility=PUBLIC AND join_policy=FREE
--     の村の所属だけを既定 ON にする。それ以外（UNLISTED または APPROVAL）は
--     DEFAULT FALSE のまま据え置き、本人が能動的にトグル ON にしたときだけ公開する。
--
--     なぜ join_policy=FREE まで絞るか（§9.2 注記）:
--       visibility=PUBLIC だけを条件にすると承認制（APPROVAL）の村の所属も既定公開に
--       なる。承認制の村は「入るのに審査が要る＝所属自体がある程度センシティブ」なので、
--       backfill の既定 ON は「誰でも即入れる FREE 村」に限定する。
--
--     【論理削除済み行・BAN行の扱い — 本 migration で条件を設計書より狭める判断】
--       left_at IS NOT NULL（退村・論理削除）/ banned_at IS NOT NULL（村BAN）の membership は
--       backfill 対象から除外し、DEFAULT FALSE のままにする。根拠:
--         - §13.4 の前例で、退会（UserAnonymizedEvent）時の membership は
--           left_at=now・banned_reason='ANONYMIZED' の匿名化マーカーで残置される。
--           これらを TRUE にすると「退会・匿名化済みの元村人が今もこの村に居る」ことを
--           所属村一覧へ公開してしまい、プライバシー侵害になる。
--         - 退村者・BAN 者は現役の村人ではないため、そもそも「所属村一覧」に載せる対象でない。
--       設計書 §9.2 の backfill 条件（村軸のみ）を、現役所属（left_at IS NULL AND banned_at IS NULL）
--       に限定して「狭める」方向のみで適用する。これは公開範囲を広げる変更ではなく、
--       伏せる側に倒す調整であり、G4 プライバシーガードレールと整合する。
-- ============================================================================
UPDATE village_memberships vm
    JOIN villages v ON v.id = vm.village_id
    SET vm.profile_public = TRUE
    WHERE v.visibility = 'PUBLIC'
      AND v.join_policy = 'FREE'
      AND vm.left_at IS NULL
      AND vm.banned_at IS NULL;
